package com.symptree.messaging

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Layer-2 de-dup guard for the kotlin-telegram-bot lib's surfaced
 * double-dispatch behavior (reproduced live on callback_query updates;
 * lib root cause not isolated). [isDuplicateUpdateId] keeps a bounded set
 * of recently-seen Telegram `update_id`s — these are guaranteed unique per
 * bot by Telegram's contract, so the same id arriving twice can only mean
 * the dispatcher fired twice for one wire update.
 */
class MessagingMainDedupTest {

    @BeforeEach
    fun setUp() {
        resetSeenUpdateIds()
    }

    @Test
    fun `first sight of an update_id is not a duplicate`() {
        assertFalse(isDuplicateUpdateId(42L))
    }

    @Test
    fun `second sight of the same update_id IS a duplicate`() {
        isDuplicateUpdateId(42L)
        assertTrue(
            isDuplicateUpdateId(42L),
            "second dispatch of update_id=42 must be flagged as duplicate — this is the live-bug regression",
        )
    }

    @Test
    fun `distinct update_ids are independent`() {
        assertFalse(isDuplicateUpdateId(1L))
        assertFalse(isDuplicateUpdateId(2L))
        assertFalse(isDuplicateUpdateId(3L))
        assertTrue(isDuplicateUpdateId(1L))
        assertTrue(isDuplicateUpdateId(2L))
        assertTrue(isDuplicateUpdateId(3L))
    }

    @Test
    fun `set stays bounded under high update volume — eldest entries expire first`() {
        // Push 2 * the limit so the oldest ~half should evict, but the
        // most-recent half must still be considered duplicates.
        val limit = 1024
        for (i in 0L until 2L * limit) {
            isDuplicateUpdateId(i)
        }
        // Recent ids: their second sight is still a duplicate.
        assertTrue(isDuplicateUpdateId(2L * limit - 1))
        assertTrue(isDuplicateUpdateId(limit + 100L))
        // Oldest ids should have been evicted — second sight is a fresh sighting.
        assertFalse(isDuplicateUpdateId(0L))
    }

    @Test
    fun `resetSeenUpdateIds wipes the set`() {
        isDuplicateUpdateId(99L)
        assertTrue(isDuplicateUpdateId(99L))
        resetSeenUpdateIds()
        assertFalse(isDuplicateUpdateId(99L), "after reset, the id is a fresh sighting again")
    }
}
