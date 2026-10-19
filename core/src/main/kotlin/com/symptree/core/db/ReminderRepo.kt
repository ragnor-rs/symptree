package com.symptree.core.db

import com.symptree.core.booking.ReminderSink
import com.symptree.core.scheduler.ReminderKind
import com.symptree.core.scheduler.ReminderPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

private val logger = LoggerFactory.getLogger("com.symptree.core.db.ReminderRepo")

/**
 * A reminder row claimed by [claimDueBatch], carrying its id alongside the
 * already-assembled [ReminderPayload] read from the `payload` JSONB column.
 */
data class ClaimedReminder(val id: Long, val payload: ReminderPayload)

/**
 * Raw-JDBC repository for the `reminders` table.
 *
 * FR-VWRNDR — the `payload` JSONB column stores **semantic** fields only:
 * `platform_user_id`, `language`, `kind`, `doctor_name`, `slot_at` (epoch millis)
 * and `clinic_zone`. No `catalog_key`, no view format — the `messaging` frontend
 * maps `kind` to its own catalog key and formats `slot_at` at dispatch time. The
 * payload is self-contained so the every-60s poll builds the outbound message
 * without re-reading the session. `kind` is also a top-level column for
 * filtering/auditing.
 */
class ReminderRepo(private val database: PostgresDatabase) {

    @Suppress("LongParameterList")
    fun insert(
        clinicId: Long,
        sessionId: Long,
        kind: ReminderKind,
        dueAt: Instant,
        doctorName: String,
        slotAt: Instant,
        clinicZone: String,
        platformUserId: String,
        language: String,
    ): Long {
        database.getConnection().use { conn ->
            return insert(
                conn, clinicId, sessionId, kind, dueAt, doctorName, slotAt, clinicZone, platformUserId, language,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun insert(
        conn: Connection,
        clinicId: Long,
        sessionId: Long,
        kind: ReminderKind,
        dueAt: Instant,
        doctorName: String,
        slotAt: Instant,
        clinicZone: String,
        platformUserId: String,
        language: String,
    ): Long {
        val payload = buildJsonObject {
            put("platform_user_id", JsonPrimitive(platformUserId))
            put("language", JsonPrimitive(language))
            put("kind", JsonPrimitive(kind.wire))
            put("doctor_name", JsonPrimitive(doctorName))
            put("slot_at", JsonPrimitive(slotAt.toEpochMilli()))
            put("clinic_zone", JsonPrimitive(clinicZone))
        }
        val sql = """
            INSERT INTO reminders (clinic_id, session_id, kind, due_at, payload)
            VALUES (?, ?, ?, ?, ?::jsonb)
            RETURNING id
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, clinicId)
            ps.setLong(2, sessionId)
            ps.setString(3, kind.wire)
            ps.setTimestamp(4, Timestamp.from(dueAt))
            ps.setString(5, payload.toString())
            ps.executeQuery().use { rs ->
                check(rs.next()) { "INSERT ... RETURNING produced no row" }
                return rs.getLong("id")
            }
        }
    }

    /**
     * Claim up to [limit] due `pending` reminders for delivery, atomically
     * flipping each to `sent` and returning its [ReminderPayload].
     *
     * The claim is one statement: a `FOR UPDATE SKIP LOCKED` sub-select feeds an
     * `UPDATE ... RETURNING`, so a row locked by one poller is skipped by the
     * other and, once this transaction commits, is no longer `pending`. That
     * gives exactly-once dispatch across concurrent scheduler instances. Dispatch
     * failures are reconciled by [markFailed] (`sent` -> `failed`).
     */
    fun claimDueBatch(nowPlusWindow: Instant, limit: Int): List<ClaimedReminder> {
        val sql = """
            UPDATE reminders SET status = 'sent', sent_at = NOW()
            WHERE id IN (
                SELECT id FROM reminders
                WHERE status = 'pending' AND due_at < ?
                ORDER BY due_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            RETURNING id, payload
        """.trimIndent()
        database.getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setTimestamp(1, Timestamp.from(nowPlusWindow))
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ClaimedReminder>()
                    while (rs.next()) {
                        val id = rs.getLong("id")
                        // A single unparseable payload (e.g. a pre-FR-VWRNDR row
                        // still carrying the legacy `catalog_key` shape) must not
                        // abort the whole batch — the atomic claim already flipped
                        // every matched row to `sent`, so a throw here would
                        // silently consume the good rows too. Skip + log; the bad
                        // row stays `sent` (consumed once, never re-polled).
                        val payload = try {
                            rs.toReminderPayload()
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            logger.warn("skipping reminder {} with unparseable payload: {}", id, e.message)
                            continue
                        }
                        out += ClaimedReminder(id, payload)
                    }
                    return out
                }
            }
        }
    }

    fun markFailed(id: Long) {
        update("UPDATE reminders SET status = 'failed' WHERE id = ?", id)
    }

    private fun update(sql: String, id: Long) {
        database.getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, id)
                ps.executeUpdate()
            }
        }
    }

    fun statusOf(id: Long): String? {
        database.getConnection().use { conn ->
            conn.prepareStatement("SELECT status FROM reminders WHERE id = ?").use { ps ->
                ps.setLong(1, id)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getString("status") else null
                }
            }
        }
    }

    /**
     * Insert a reminder whose `platform_user_id`/`language` are resolved from the
     * owning session (the [ReminderSink] adapter path: callers only hold a
     * `sessionId`). Joins `sessions` so the `payload` JSONB is self-contained.
     */
    @Suppress("LongParameterList")
    internal fun insertFromSession(
        sessionId: Long,
        clinicId: Long,
        kind: ReminderKind,
        dueAt: Instant,
        doctorName: String,
        slotAt: Instant,
        clinicZone: String,
    ): Long {
        database.getConnection().use { conn ->
            val (platformUserId, language) = conn.prepareStatement(
                "SELECT platform_user_id, language FROM sessions WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, sessionId)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "session $sessionId not found for reminder insert" }
                    rs.getString("platform_user_id") to rs.getString("language")
                }
            }
            return insert(
                conn, clinicId, sessionId, kind, dueAt, doctorName, slotAt, clinicZone, platformUserId, language,
            )
        }
    }

    private fun ResultSet.toReminderPayload(): ReminderPayload {
        val raw = when (val obj = getObject("payload")) {
            is PGobject -> obj.value ?: "{}"
            is String -> obj
            null -> "{}"
            else -> obj.toString()
        }
        val json = Json.parseToJsonElement(raw).jsonObject
        return ReminderPayload(
            platformUserId = json.getValue("platform_user_id").jsonPrimitive.content,
            language = json.getValue("language").jsonPrimitive.content,
            kind = ReminderKind.fromWire(json.getValue("kind").jsonPrimitive.content),
            doctorName = json.getValue("doctor_name").jsonPrimitive.content,
            slotAt = Instant.ofEpochMilli(json.getValue("slot_at").jsonPrimitive.long),
            clinicZone = json.getValue("clinic_zone").jsonPrimitive.content,
        )
    }

    companion object {
        /** Adapts a [ReminderRepo] into a [ReminderSink] bound to one clinic. */
        fun sink(repo: ReminderRepo, clinicId: Long): ReminderSink =
            object : ReminderSink {
                override fun insert(
                    sessionId: Long,
                    clinicId: Long,
                    kind: ReminderKind,
                    dueAt: Instant,
                    doctorName: String,
                    slotAt: Instant,
                    clinicZone: String,
                ) {
                    repo.insertFromSession(sessionId, clinicId, kind, dueAt, doctorName, slotAt, clinicZone)
                }
            }
    }
}
