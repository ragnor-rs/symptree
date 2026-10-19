package com.symptree.core.booking

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-W03362.10 — `FeedbackHandler` (NOT the LLM router) interprets the message
 * that lands after a `post_visit_feedback` reminder:
 *  - integer 1..5 → record `feedback_score` (+ optional text), produce a thank-you,
 *  - out-of-range / non-numeric → one reprompt, then welcome menu.
 */
class FeedbackHandlerTest {

    /** Records score writes so the handler's persistence side effect can be asserted. */
    private class RecordingSink : FeedbackSink {
        val scores: MutableList<Pair<Long, Int>> = mutableListOf()
        var texts: MutableList<Pair<Long, String>> = mutableListOf()
        override fun recordScore(bookingId: Long, score: Int, text: String?) {
            scores += bookingId to score
            if (text != null) texts += bookingId to text
        }
    }

    private val bookingId = 42L

    @Test
    fun `valid score 1 to 5 is recorded and acknowledged`() {
        val sink = RecordingSink()
        val handler = FeedbackHandler(sink)

        val result = handler.handle(bookingId = bookingId, userText = "5")

        assertEquals(listOf(bookingId to 5), sink.scores, "score 5 must be persisted")
        assertTrue(result is FeedbackResult.Recorded, "a valid score produces a Recorded result")
        assertEquals(5, (result as FeedbackResult.Recorded).score)
    }

    @Test
    fun `boundary scores 1 and 5 are both accepted`() {
        val sink = RecordingSink()
        val handler = FeedbackHandler(sink)
        assertTrue(handler.handle(bookingId, "1") is FeedbackResult.Recorded)
        assertTrue(handler.handle(bookingId, "5") is FeedbackResult.Recorded)
        assertEquals(listOf(bookingId to 1, bookingId to 5), sink.scores)
    }

    @Test
    fun `out-of-range score triggers exactly one reprompt`() {
        val sink = RecordingSink()
        val handler = FeedbackHandler(sink)

        val result = handler.handle(bookingId = bookingId, userText = "9")

        assertTrue(sink.scores.isEmpty(), "out-of-range value must not be persisted")
        assertTrue(result is FeedbackResult.Reprompt, "first out-of-range reply → reprompt, got $result")
    }

    @Test
    fun `non-numeric reply triggers a reprompt then welcome menu on the second failure`() {
        val sink = RecordingSink()
        val handler = FeedbackHandler(sink)

        val first = handler.handle(bookingId = bookingId, userText = "great!")
        assertTrue(first is FeedbackResult.Reprompt, "first non-numeric reply → reprompt")

        val second = handler.handle(bookingId = bookingId, userText = "still text")
        assertTrue(
            second is FeedbackResult.WelcomeMenu,
            "second consecutive invalid reply must fall back to the welcome menu, got $second",
        )
        assertTrue(sink.scores.isEmpty(), "no score recorded across two invalid replies")
    }
}
