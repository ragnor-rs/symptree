package com.symptree.core.scheduler

import com.symptree.core.db.ReminderRepo
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * FR-VWRNDR — the semantic reminder kind. `core` emits this typed discriminator;
 * the `messaging` frontend maps it to its own catalog key and renders. The DB
 * `reminders.kind` column stores [name].lowercase() (e.g. `pre_visit_24h`).
 */
enum class ReminderKind {
    PRE_VISIT_24H,
    SAME_DAY,
    POST_VISIT_FEEDBACK,
    ;

    /** The wire / DB token (`pre_visit_24h`, `same_day`, `post_visit_feedback`). */
    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(token: String): ReminderKind = valueOf(token.uppercase())
    }
}

/**
 * FR-VWRNDR — the **semantic** outbound payload a due reminder dispatches. Carries
 * concrete data fields (no catalog key, no view format); the frontend decides how
 * to render the [kind] and formats [slotAt] in [clinicZone].
 */
data class ReminderPayload(
    val platformUserId: String,
    val language: String,
    val kind: ReminderKind,
    val doctorName: String,
    val slotAt: Instant,
    val clinicZone: String,
)

/** Delivers a reminder's [ReminderPayload] (POST to messaging `/bot-internal/outgoing`). */
interface ReminderDispatcher {
    suspend fun dispatch(reminderId: Long, payload: ReminderPayload)
}

/**
 * Polls the `reminders` table for due `pending` rows, dispatches each via the
 * [ReminderDispatcher], and marks it `sent`. Concurrency-safe via
 * `FOR UPDATE SKIP LOCKED`.
 *
 * Stubbed for AC-W03362.1 compile; fleshed out in AC-W03362.8/.11.
 */
class ReminderScheduler(
    private val reminderRepo: ReminderRepo,
    private val dispatcher: ReminderDispatcher,
    private val clock: Clock,
) {
    /**
     * Claim one batch of due `pending` reminders (`FOR UPDATE SKIP LOCKED
     * LIMIT 50`, atomically flipped to `sent`), then dispatch each via the
     * [ReminderDispatcher]. The atomic claim guarantees exactly-once across
     * concurrent instances; a dispatch failure flips the row back to `failed`.
     * The claim window is `now()+60s` so the poll catches reminders coming due
     * before the next tick.
     */
    suspend fun pollOnce() {
        val window = clock.instant().plus(POLL_WINDOW)
        val claimed = reminderRepo.claimDueBatch(nowPlusWindow = window, limit = BATCH_LIMIT)
        for (reminder in claimed) {
            try {
                dispatcher.dispatch(reminder.id, reminder.payload)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                reminderRepo.markFailed(reminder.id)
            }
        }
    }

    private companion object {
        val POLL_WINDOW: Duration = Duration.ofSeconds(60)
        const val BATCH_LIMIT = 50
    }
}
