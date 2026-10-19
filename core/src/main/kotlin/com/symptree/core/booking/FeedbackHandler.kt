package com.symptree.core.booking

/** Sink for the post-visit feedback score (and optional free text). */
interface FeedbackSink {
    fun recordScore(bookingId: Long, score: Int, text: String?)
}

/** Outcome of interpreting a post-visit feedback reply. */
sealed interface FeedbackResult {
    data class Recorded(val score: Int) : FeedbackResult
    data object Reprompt : FeedbackResult
    data object WelcomeMenu : FeedbackResult
}

/**
 * Interprets the message that lands after a `post_visit_feedback` reminder:
 * an integer 1..5 is recorded; otherwise one reprompt, then the welcome menu.
 *
 * Stubbed for AC-W03362.1 compile; fleshed out in AC-W03362.10.
 */
class FeedbackHandler(private val sink: FeedbackSink) {
    private var invalidAttempts = 0

    fun handle(bookingId: Long, userText: String): FeedbackResult {
        val score = userText.trim().toIntOrNull()
        if (score != null && score in 1..5) {
            invalidAttempts = 0
            sink.recordScore(bookingId, score, null)
            return FeedbackResult.Recorded(score)
        }
        invalidAttempts += 1
        return if (invalidAttempts >= 2) {
            invalidAttempts = 0
            FeedbackResult.WelcomeMenu
        } else {
            FeedbackResult.Reprompt
        }
    }
}
