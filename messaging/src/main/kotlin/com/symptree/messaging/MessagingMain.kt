package com.symptree.messaging

import com.symptree.messaging.adapter.TelegramAdapter
import com.symptree.messaging.client.CoreClient
import com.symptree.messaging.config.MessagingConfig
import com.symptree.messaging.core.IngressHandler
import com.symptree.messaging.core.IngressRenderer
import com.symptree.messaging.core.TelegramUpdateNormalizer
import com.symptree.messaging.i18n.MessageCatalog
import com.symptree.messaging.outgoing.botInternalOutgoingRoutes
import com.symptree.messaging.port.IncomingMessage
import com.symptree.messaging.port.Platform
import com.symptree.messaging.port.PlatformUser
import com.github.kotlintelegrambot.bot
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.Collections

private val logger = LoggerFactory.getLogger("com.symptree.messaging.MessagingMain")

/**
 * FR-W3WDWH entry point. Wires:
 *  - `CoreClient` for the bot → backend `/bot/ingress` channel (carries the
 *    shared secret for auth).
 *  - `kotlin-telegram-bot` `Bot` configured for long polling.
 *  - `TelegramAdapter` (injected with that same `Bot`) replays backend
 *    `OutgoingAction`s back to Telegram.
 *  - `IngressHandler` orchestrates per-update flow: normalize → POST →
 *    replay actions.
 *
 * Single `Bot` instance pattern: the dispatcher captures `ingress` via a
 * `lateinit var` forward reference so handlers can use the adapter that
 * wraps this very Bot. An earlier two-bot variant (placeholder for adapter,
 * second bot for polling) leaked phantom updates and surfaced duplicate
 * responses — one Bot, one polling loop, one offset advance.
 *
 * Long polling matches the M2 spec (AC-W3WDWH.2); webhook mode is deferred
 * to a later deployment milestone (FR-W3WDWH § Notes).
 */
fun main() {
    val config = MessagingConfig.fromEnv()
    logger.info(
        "Starting symptree bot — clinicSlug={}, backendUrl={}, telegramEnabled={}, whatsappEnabled={}",
        config.clinicSlug, config.backendUrl, config.telegramEnabled, config.whatsappEnabled,
    )

    if (!config.telegramEnabled) {
        logger.info("Telegram disabled (no TELEGRAM_BOT_TOKEN). Bot module idle.")
        return
    }
    val telegramBotToken = requireNotNull(config.telegramBotToken) {
        "telegramBotToken is null despite telegramEnabled=true"
    }

    val coreClient = CoreClient(
        baseUrl = config.backendUrl,
        apiKey = config.botIngressSharedSecret,
    )

    // Forward-reference so the dispatcher block can capture `ingress` before
    // the adapter (which needs the Bot) and the handler (which needs the
    // adapter) exist. The lambda only RUNS at update dispatch time, by which
    // point `ingress` is initialized.
    lateinit var ingress: IngressHandler

    val tgBot = bot {
        token = telegramBotToken
        dispatch {
            text {
                if (isDuplicateUpdateId(update.updateId)) {
                    logger.warn("dropping duplicate text dispatch for update_id={}", update.updateId)
                    return@text
                }
                val message = update.message ?: return@text
                val from = message.from ?: return@text
                val txt = message.text ?: return@text
                val user = PlatformUser(
                    platform = Platform.TELEGRAM,
                    id = message.chat.id.toString(),
                    displayName = from.firstName,
                    languageCode = from.languageCode,
                )
                runBlocking {
                    ingress.handle(IncomingMessage(user = user, text = txt))
                }
            }

            callbackQuery {
                if (isDuplicateUpdateId(update.updateId)) {
                    logger.warn("dropping duplicate callback_query dispatch for update_id={}", update.updateId)
                    return@callbackQuery
                }
                val cb = callbackQuery
                // 1. Acknowledge so Telegram dismisses the per-button loading
                //    spinner immediately, regardless of backend latency.
                bot.answerCallbackQuery(cb.id)

                // 2. Mark the user's pick on the original message: append
                //    "\n✅ <label>" and remove the inline_keyboard. Prevents
                //    scroll-up + re-tap of stale buttons and gives clean
                //    visual confirmation of which option was chosen.
                val originalMessage = cb.message
                if (originalMessage != null) {
                    val markup = originalMessage.replyMarkup
                    val pickedLabel = markup?.inlineKeyboard?.flatten()
                        ?.filterIsInstance<InlineKeyboardButton.CallbackData>()
                        ?.firstOrNull { it.callbackData == cb.data }
                        ?.text
                        ?: cb.data
                    val originalText = originalMessage.text.orEmpty()
                    try {
                        bot.editMessageText(
                            chatId = ChatId.fromId(originalMessage.chat.id),
                            messageId = originalMessage.messageId,
                            text = "$originalText\n${selectedConfirmation(pickedLabel)}",
                        )
                    } catch (e: Exception) {
                        logger.warn("Failed to edit message after callback: ${e.message}")
                    }
                }

                // 3. Forward to backend.
                val normalized = TelegramUpdateNormalizer.normalize(update) ?: return@callbackQuery
                runBlocking {
                    ingress.handle(normalized)
                }
            }
        }
    }

    // FR-VWRNDR — the single view-layer catalog backing both the ingress renderer
    // and the reminder/feedback outgoing route.
    val catalog = MessageCatalog.loadFromClasspath()
    val telegramAdapter = TelegramAdapter(tgBot)
    ingress = IngressHandler(
        coreClient = coreClient,
        port = telegramAdapter,
        clinicSlug = config.clinicSlug,
        renderer = IngressRenderer(catalog),
    )

    // FR-VWRNDR — serve POST /bot-internal/outgoing so the core reminder
    // scheduler can push due reminders to this clinic's bot (this module maps the
    // semantic kind to its catalog key). Started only when the shared secret is set.
    if (config.botInternalSharedSecret.isNotBlank()) {
        embeddedServer(Netty, port = config.healthPort, host = "0.0.0.0") {
            routing {
                botInternalOutgoingRoutes(catalog, telegramAdapter, config.botInternalSharedSecret)
            }
        }.start(wait = false)
        logger.info("Internal outgoing endpoint serving on :{}", config.healthPort)
    } else {
        logger.warn("BOT_INTERNAL_SHARED_SECRET unset — /bot-internal/outgoing not served (reminder push disabled)")
    }

    logger.info("Starting Telegram long polling for clinic '{}'…", config.clinicSlug)
    tgBot.startPolling()
}

/**
 * Recently-seen Telegram `update_id`s — used to drop duplicate dispatches
 * before they reach `ingress.handle()`. The kotlin-telegram-bot library has
 * surfaced bursts of double-dispatch on callback_query updates in production
 * (root cause not isolated yet); this set is a layer-2 guard that makes the
 * symptom impossible regardless of the lib's internal behavior.
 *
 * Telegram guarantees `update_id`s are strictly unique per bot, so the set
 * doubles as a deterministic de-dup key. Bounded to [MAX_RECENT_UPDATE_IDS]
 * with FIFO eviction so memory stays flat at high update volume.
 */
private val seenUpdateIds: MutableSet<Long> =
    Collections.synchronizedSet(LinkedHashSet<Long>())
private const val MAX_RECENT_UPDATE_IDS: Int = 1024

internal fun isDuplicateUpdateId(updateId: Long): Boolean {
    synchronized(seenUpdateIds) {
        if (!seenUpdateIds.add(updateId)) {
            return true
        }
        if (seenUpdateIds.size > MAX_RECENT_UPDATE_IDS) {
            val iter = seenUpdateIds.iterator()
            iter.next()
            iter.remove()
        }
        return false
    }
}

/**
 * The confirmation line appended to a message after its button is pressed. Adds a
 * leading ✅ as the "you picked this" marker — but NOT when [label] already starts
 * with its own emoji (menu labels like `🩺 …`, `📅 …`, `✅ Да`, `🔄 …`, `ℹ️ …`),
 * so we don't stack two emoji at the line start.
 */
internal fun selectedConfirmation(label: String): String =
    if (startsWithEmoji(label)) label else "✅ $label"

/**
 * True if [label] (after leading whitespace) begins with an emoji / pictographic
 * symbol rather than a letter, digit, or plain text character. Covers the Unicode
 * blocks the bot's button labels draw from: Misc Symbols + Dingbats (incl. ✅ U+2705,
 * ⚠ U+26A0), arrows, the letterlike ℹ U+2139, and the Supplemental/Extended
 * pictographic planes (U+1F000–U+1FAFF, e.g. 🩺 📅 🔄 🚨 🏠).
 */
internal fun startsWithEmoji(label: String): Boolean {
    val trimmed = label.trimStart()
    if (trimmed.isEmpty()) return false
    return when (trimmed.codePointAt(0)) {
        in 0x1F000..0x1FAFF, // supplemental + extended-A pictographs
        in 0x2600..0x27BF,   // misc symbols + dingbats (✅ ⚠ etc.)
        in 0x2B00..0x2BFF,   // misc symbols & arrows
        in 0x2190..0x21FF,   // arrows
        in 0x1F1E6..0x1F1FF, // regional indicators
        0x2139,              // ℹ information source
        -> true
        else -> false
    }
}

/** Test-only: wipe the de-dup set so each test starts clean. */
internal fun resetSeenUpdateIds() {
    synchronized(seenUpdateIds) { seenUpdateIds.clear() }
}
