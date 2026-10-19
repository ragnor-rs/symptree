package com.symptree.core.scheduler

import com.symptree.core.Fixtures
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ReminderRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.integration.PostgresTestSupport
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-W03362.8 + .11 — `reminders` schema/index + the `ReminderScheduler` poll.
 *
 * The scheduler claims due `pending` rows with `FOR UPDATE SKIP LOCKED LIMIT 50`,
 * builds the semantic payload (platform_user_id + language + kind + doctor_name +
 * slot_at + clinic_zone), dispatches it (POST to messaging `/bot-internal/outgoing`),
 * and marks the row `sent`. AC.11 = two coroutines over 100 due rows, each sent once.
 *
 * `Clock` is injected per testing-spec § 5.4; the outbound HTTP is a fake.
 */
class ReminderSchedulerIT {

    private lateinit var clinicRepo: ClinicRepo
    private lateinit var reminderRepo: ReminderRepo
    private var clinicId: Long = 0L
    private var sessionId: Long = 0L

    private val now = Instant.parse("2026-07-10T09:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        PostgresTestSupport.freshSchema()
        val db = PostgresTestSupport.database()
        clinicRepo = ClinicRepo(db)
        reminderRepo = ReminderRepo(db)
        clinicId = Fixtures.fixtureClinic(clinicRepo, slug = "rem").clinic.id
        sessionId = SessionRepo(db).insertActive(clinicId, "TELEGRAM", "tg-rem", "ru")
    }

    /** Counts dispatches per reminder id; thread-safe so the two-coroutine test can assert exactly-once. */
    private class RecordingDispatcher : ReminderDispatcher {
        val perId = ConcurrentHashMap<Long, AtomicInteger>()
        val lastPayloads = ConcurrentHashMap<Long, ReminderPayload>()
        override suspend fun dispatch(reminderId: Long, payload: ReminderPayload) {
            perId.computeIfAbsent(reminderId) { AtomicInteger(0) }.incrementAndGet()
            lastPayloads[reminderId] = payload
        }
    }

    private fun seedReminder(kind: ReminderKind, dueAt: Instant): Long =
        reminderRepo.insert(
            clinicId = clinicId,
            sessionId = sessionId,
            kind = kind,
            dueAt = dueAt,
            doctorName = "Dr A",
            slotAt = now,
            clinicZone = "UTC",
            platformUserId = "tg-rem",
            language = "ru",
        )

    /** Insert a pre-FR-VWRNDR row whose payload is the legacy `catalog_key` shape (no `kind`). */
    private fun seedLegacyReminder(dueAt: Instant): Long {
        val payload =
            """{"platform_user_id":"tg-rem","language":"ru","catalog_key":"bot.reminder.same_day","params":{"doctor":"Dr A"}}"""
        return PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "INSERT INTO reminders (clinic_id, session_id, kind, due_at, payload) " +
                    "VALUES (?, ?, ?, ?, ?::jsonb) RETURNING id",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, sessionId)
                ps.setString(3, "same_day")
                ps.setTimestamp(4, Timestamp.from(dueAt))
                ps.setString(5, payload)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
    }

    // ---------------- AC.8: schema ----------------

    @Test
    fun `AC-8 reminders table has the required columns`() {
        val cols = PostgresTestSupport.database().getConnection().use { conn ->
            val seen = mutableMapOf<String, String>()
            conn.prepareStatement(
                "SELECT column_name, data_type FROM information_schema.columns " +
                    "WHERE table_schema='public' AND table_name='reminders'",
            ).use { ps -> ps.executeQuery().use { rs -> while (rs.next()) seen[rs.getString(1)] = rs.getString(2) } }
            seen
        }
        for (col in listOf("id", "clinic_id", "session_id", "due_at", "kind", "payload", "status", "sent_at", "created_at")) {
            assertTrue(cols.containsKey(col), "reminders missing column $col; saw: ${cols.keys}")
        }
        assertEquals("jsonb", cols["payload"], "payload must be jsonb")
    }

    @Test
    fun `AC-8 due-pending partial index exists`() {
        val hasIndex = PostgresTestSupport.database().getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE tablename='reminders'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    var found = false
                    while (rs.next()) {
                        val def = rs.getString(1).lowercase()
                        if (def.contains("due_at") && def.contains("status") && def.contains("pending")) found = true
                    }
                    found
                }
            }
        }
        assertTrue(hasIndex, "expected a partial index on (due_at) WHERE status='pending'")
    }

    // ---------------- AC.8: single-instance happy + same-day key ----------------

    @Test
    fun `AC-8 pollOnce dispatches a due pending reminder and marks it sent`() = runBlocking {
        val id = seedReminder(ReminderKind.PRE_VISIT_24H, now.minusSeconds(10))
        val dispatcher = RecordingDispatcher()
        val scheduler = ReminderScheduler(reminderRepo = reminderRepo, dispatcher = dispatcher, clock = clock)

        scheduler.pollOnce()

        assertEquals(1, dispatcher.perId[id]?.get(), "the due reminder must be dispatched once")
        assertEquals(ReminderKind.PRE_VISIT_24H, dispatcher.lastPayloads[id]?.kind)
        assertEquals("sent", reminderRepo.statusOf(id), "row must be marked sent after dispatch")
    }

    @Test
    fun `AC-8 future-dated reminders are not dispatched`() = runBlocking {
        val id = seedReminder(ReminderKind.PRE_VISIT_24H, now.plusSeconds(3600))
        val dispatcher = RecordingDispatcher()
        val scheduler = ReminderScheduler(reminderRepo = reminderRepo, dispatcher = dispatcher, clock = clock)

        scheduler.pollOnce()

        assertTrue(dispatcher.perId[id] == null, "a not-yet-due reminder must not be dispatched")
        assertEquals("pending", reminderRepo.statusOf(id))
    }

    @Test
    fun `AC-8 same_day reminder carries the same_day kind in its semantic payload`() = runBlocking {
        val id = seedReminder(ReminderKind.SAME_DAY, now.minusSeconds(5))
        val dispatcher = RecordingDispatcher()
        val scheduler = ReminderScheduler(reminderRepo = reminderRepo, dispatcher = dispatcher, clock = clock)

        scheduler.pollOnce()

        assertEquals(ReminderKind.SAME_DAY, dispatcher.lastPayloads[id]?.kind)
    }

    @Test
    fun `FR-VWRNDR a legacy catalog_key payload row is skipped, not crashing the poll`() = runBlocking {
        // A pre-VWRNDR row (no `kind`) must not abort the batch: it is skipped +
        // logged, the well-formed reminder still dispatches, and both rows are
        // consumed (`sent`) by the atomic claim so the bad row never re-polls.
        val legacy = seedLegacyReminder(now.minusSeconds(20))
        val good = seedReminder(ReminderKind.PRE_VISIT_24H, now.minusSeconds(10))
        val dispatcher = RecordingDispatcher()
        val scheduler = ReminderScheduler(reminderRepo = reminderRepo, dispatcher = dispatcher, clock = clock)

        scheduler.pollOnce() // must not throw

        assertEquals(1, dispatcher.perId[good]?.get(), "the well-formed reminder must still dispatch")
        assertTrue(dispatcher.perId[legacy] == null, "the legacy-payload row must be skipped, not dispatched")
        assertEquals("sent", reminderRepo.statusOf(legacy), "the legacy row is consumed (sent) by the atomic claim")
        assertEquals("sent", reminderRepo.statusOf(good))
    }

    // ---------------- AC.11: two-coroutine exactly-once ----------------

    @Test
    fun `AC-11 two concurrent schedulers send 100 due reminders exactly once each`() = runBlocking {
        val ids = (1..100).map { seedReminder(ReminderKind.PRE_VISIT_24H, now.minusSeconds(30)) }
        val dispatcher = RecordingDispatcher()
        val s1 = ReminderScheduler(reminderRepo = reminderRepo, dispatcher = dispatcher, clock = clock)
        val s2 = ReminderScheduler(reminderRepo = reminderRepo, dispatcher = dispatcher, clock = clock)

        // Drain the backlog with both instances racing (LIMIT 50 per poll → several rounds).
        listOf(
            async { repeat(5) { s1.pollOnce() } },
            async { repeat(5) { s2.pollOnce() } },
        ).awaitAll()

        assertEquals(ids.toSet(), dispatcher.perId.keys, "every due reminder must be dispatched")
        val overSent = dispatcher.perId.filterValues { it.get() != 1 }
        assertTrue(overSent.isEmpty(), "FOR UPDATE SKIP LOCKED must guarantee exactly-once; offenders: $overSent")
    }
}
