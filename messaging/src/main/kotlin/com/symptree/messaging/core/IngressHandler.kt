package com.symptree.messaging.core

import com.symptree.messaging.client.CoreClient
import com.symptree.messaging.port.IncomingMessage
import com.symptree.messaging.port.MessagingPort
import com.symptree.messaging.port.OutgoingAction
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.symptree.messaging.core.IngressHandler")

/**
 * AC-W3WDWH.2 / FR-VWRNDR — bot dispatch loop's per-update handler.
 *
 * Posts the [IncomingMessage] to the backend's `bot/ingress` endpoint (via
 * [CoreClient]), which returns a **semantic** `IngressResponse`. The
 * [IngressRenderer] maps each typed outcome — using the resolved session language
 * — into platform-neutral [OutgoingAction]s, which are replayed through the
 * configured [MessagingPort] in order. All view assembly lives in the frontend.
 *
 * The handler is platform-agnostic: it only needs the configured clinic slug
 * (one bot per clinic for M2 per AC.2 spec) and depends on the [MessagingPort]
 * port, not on platform-specific APIs.
 */
class IngressHandler(
    private val coreClient: CoreClient,
    private val port: MessagingPort,
    private val clinicSlug: String,
    private val renderer: IngressRenderer,
) {
    suspend fun handle(incoming: IncomingMessage) {
        logger.info(
            "ingress.handle invoked: user={}, text={}, callback={}",
            incoming.user.id, incoming.text, incoming.callbackData,
        )
        val response = try {
            coreClient.botIngress(clinicSlug, incoming)
        } catch (e: Exception) {
            logger.error("bot ingress call failed: {}", e.message, e)
            return
        }
        for (action in renderer.render(response.lang, response.outcomes)) {
            replay(incoming, action)
        }
    }

    private suspend fun replay(incoming: IncomingMessage, action: OutgoingAction) {
        when (action) {
            is OutgoingAction.SendText -> port.sendText(
                user = incoming.user,
                text = action.text,
                disableLinkPreview = action.disableLinkPreview,
            )
            is OutgoingAction.SendMenu -> port.sendMenu(
                user = incoming.user,
                text = action.text,
                options = action.options,
                layout = action.layout,
                disableLinkPreview = action.disableLinkPreview,
            )
            is OutgoingAction.SendPhoto -> port.sendPhoto(
                user = incoming.user,
                photo = action.photoBytes,
                mimeType = action.mimeType,
                caption = action.caption,
                options = action.options,
            )
            is OutgoingAction.SendTyping -> port.sendTypingIndicator(incoming.user)
            is OutgoingAction.RemoveButtons -> port.removeButtons(incoming.user, action.messageId)
            is OutgoingAction.UpdateButtons -> port.updateButtons(
                user = incoming.user,
                messageId = action.messageId,
                options = action.options,
                layout = action.layout,
            )
        }
    }
}
