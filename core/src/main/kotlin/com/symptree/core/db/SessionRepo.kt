package com.symptree.core.db

import com.symptree.core.symptomizer.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.postgresql.util.PGobject
import java.sql.ResultSet

/**
 * Raw-JDBC repository for the `sessions` table.
 *
 * AC-PWVTT4.1 surface — the minimum the integration test exercises to verify
 * migration + UNIQUE constraint + history JSONB round-trip:
 *  - [insertActive]: inserts a new session row in the `'active'` state.
 *  - [findById]: lookup by primary key, returns a [Session] or null.
 *  - [replaceHistory]: overwrites the `history` JSONB array on a row.
 *
 * State-mutation methods that drive the walker step (set current_algorithm_id,
 * append-history-and-advance-node in one UPDATE, transition status to
 * `concluded` / `abandoned`) belong to AC-PWVTT4.2 / AC-PWVTT4.8 surface and
 * will land alongside `SessionService`.
 */
/**
 * AC-MG7TSR.6 — redacted summary row for the admin session-list endpoint.
 * Carries no decrypted free-text (no `history`); the DETAIL endpoint is the
 * only place PII is decrypted.
 */
data class SessionSummaryRow(
    val id: Long,
    val clinicId: Long,
    val platform: String,
    val platformUserId: String,
    val language: String,
    val status: String,
    val createdAt: java.time.Instant,
)

class SessionRepo(private val database: PostgresDatabase) {

    private companion object {
        const val DEFAULT_PAGE_LIMIT = 50
        const val MAX_PAGE_LIMIT = 200
    }

    fun insertActive(
        clinicId: Long,
        platform: String,
        platformUserId: String,
        language: String,
    ): Long {
        database.getConnection().use { conn ->
            val sql = """
                INSERT INTO sessions (clinic_id, platform, platform_user_id, language)
                VALUES (?, ?, ?, ?)
                RETURNING id
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, clinicId)
                ps.setString(2, platform)
                ps.setString(3, platformUserId)
                ps.setString(4, language)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT ... RETURNING produced no row" }
                    return rs.getLong(1)
                }
            }
        }
    }

    /**
     * AC-MG7TSR.3 — tenancy guard for the admin REST surface: true iff a session
     * row with [sessionId] exists AND belongs to [clinicId]. A foreign or absent
     * id is indistinguishable (both false), so the caller answers 403 either way
     * and never leaks existence as a 404.
     */
    fun existsForClinic(clinicId: Long, sessionId: Long): Boolean {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM sessions WHERE id = ? AND clinic_id = ?",
            ).use { ps ->
                ps.setLong(1, sessionId)
                ps.setLong(2, clinicId)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    /**
     * AC-MG7TSR.6 — clinic-scoped detail read: the session row WITH its history
     * JSONB, but only if it belongs to [clinicId]. A foreign or absent id returns
     * null so the route answers 403 without leaking existence.
     */
    fun findByIdForClinic(clinicId: Long, sessionId: Long): Session? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, clinic_id, platform, platform_user_id, language, " +
                    "current_algorithm_id, current_node_id, history, status " +
                    "FROM sessions WHERE id = ? AND clinic_id = ?",
            ).use { ps ->
                ps.setLong(1, sessionId)
                ps.setLong(2, clinicId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toSession() else null
                }
            }
        }
    }

    /**
     * AC-MG7TSR.6 — cursor-paginated, clinic-scoped session summaries for the
     * admin list endpoint. Filters by optional [status] and a `created_at`
     * window (`[from, to)`); pages forward by `id` (cursor = last id seen).
     * Deliberately does NOT return the `history` JSONB — the list is a redacted
     * summary, never decrypted free-text.
     */
    fun listForClinic(
        clinicId: Long,
        status: String? = null,
        from: java.time.Instant? = null,
        to: java.time.Instant? = null,
        cursor: Long? = null,
        limit: Int = DEFAULT_PAGE_LIMIT,
    ): List<SessionSummaryRow> {
        val sql = buildString {
            append(
                "SELECT id, clinic_id, platform, platform_user_id, language, status, created_at " +
                    "FROM sessions WHERE clinic_id = ?",
            )
            if (status != null) append(" AND status = ?")
            if (from != null) append(" AND created_at >= ?")
            if (to != null) append(" AND created_at < ?")
            if (cursor != null) append(" AND id > ?")
            append(" ORDER BY id ASC LIMIT ?")
        }
        database.getConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setLong(i++, clinicId)
                if (status != null) ps.setString(i++, status)
                if (from != null) ps.setObject(i++, java.sql.Timestamp.from(from))
                if (to != null) ps.setObject(i++, java.sql.Timestamp.from(to))
                if (cursor != null) ps.setLong(i++, cursor)
                ps.setInt(i, limit.coerceIn(1, MAX_PAGE_LIMIT))
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<SessionSummaryRow>()
                    while (rs.next()) {
                        out += SessionSummaryRow(
                            id = rs.getLong("id"),
                            clinicId = rs.getLong("clinic_id"),
                            platform = rs.getString("platform"),
                            platformUserId = rs.getString("platform_user_id"),
                            language = rs.getString("language"),
                            status = rs.getString("status"),
                            createdAt = rs.getTimestamp("created_at").toInstant(),
                        )
                    }
                    return out
                }
            }
        }
    }

    fun findById(sessionId: Long): Session? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, clinic_id, platform, platform_user_id, language, " +
                    "current_algorithm_id, current_node_id, history, status " +
                    "FROM sessions WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, sessionId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toSession() else null
                }
            }
        }
    }

    /**
     * AC-W3WDWH.10 — returns the most-recent session row for
     * `(clinic, platform, platform_user_id)` regardless of `status`, or `null`
     * if the user has never started a session in this clinic. Used by the bot
     * ingress route to detect a freshly-concluded conversation and route
     * subsequent free-text back to the welcome menu instead of opening a brand
     * new symptomizer journey.
     */
    fun findLatestSession(clinicId: Long, platform: String, platformUserId: String): Session? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, clinic_id, platform, platform_user_id, language, " +
                    "current_algorithm_id, current_node_id, history, status " +
                    "FROM sessions " +
                    "WHERE clinic_id = ? AND platform = ? AND platform_user_id = ? " +
                    "ORDER BY updated_at DESC LIMIT 1",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setString(2, platform)
                ps.setString(3, platformUserId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toSession() else null
                }
            }
        }
    }

    /**
     * Returns the active session for the `(clinic, platform, platform_user_id)`
     * tuple, or `null` if none exists. The `sessions` UNIQUE constraint
     * guarantees at most one `'active'` row per tuple, so callers may rely on
     * this method to drive the start-or-resume idempotency contract from
     * AC-PWVTT4.2.
     */
    fun findActiveSession(clinicId: Long, platform: String, platformUserId: String): Session? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, clinic_id, platform, platform_user_id, language, " +
                    "current_algorithm_id, current_node_id, history, status " +
                    "FROM sessions " +
                    "WHERE clinic_id = ? AND platform = ? AND platform_user_id = ? AND status = 'active'",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setString(2, platform)
                ps.setString(3, platformUserId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toSession() else null
                }
            }
        }
    }

    // test-only — production code goes through `updateStateAndAppendTurn`
    // which appends one audit turn at a time. Replacing the whole history
    // array would bypass the append-only invariant the spec requires.
    internal fun replaceHistory(sessionId: Long, newHistory: JsonArray) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE sessions SET history = ?::jsonb, updated_at = NOW() WHERE id = ?",
            ).use { ps ->
                ps.setString(1, newHistory.toString())
                ps.setLong(2, sessionId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * AC-PWVTT4.3 — atomically update `sessions.language` and append a single
     * audit turn (`turnJson`) to the `history` JSONB array in one UPDATE.
     *
     * Uses Postgres `jsonb || jsonb` concatenation so the append + language
     * swap land together; if the row's `history` had drifted between the
     * service's read and this write, we still produce a valid array because
     * concatenation is associative on JSONB arrays.
     */
    fun updateLanguageAndAppendTurn(sessionId: Long, newLanguage: String, turnJson: String) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE sessions " +
                    "SET language = ?, " +
                    "    history = history || ?::jsonb, " +
                    "    updated_at = NOW() " +
                    "WHERE id = ?",
            ).use { ps ->
                ps.setString(1, newLanguage)
                // jsonb || jsonb of a single object appends one element when the right side is a JSON array.
                ps.setString(2, "[$turnJson]")
                ps.setLong(3, sessionId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * AC-PWVTT4.8 — atomically advance session state and append one history
     * turn in a single UPDATE.
     *
     * Any of [currentAlgorithmId], [currentNodeId], [status] may be `null`
     * meaning "leave the column unchanged". The [turnJson] string is appended
     * to the `history` JSONB array. The single UPDATE guarantees that history
     * append and state change either both land or neither does — no orphaned
     * writes per the AC-PWVTT4.8 atomicity contract.
     */
    /**
     * Flip an active session to `status = 'cancelled'`. Used by the bot ingress
     * route on `/start` and on re-pick of the `symptom_consultation` goal so a
     * user can abandon a stuck mid-walk session and start fresh without DB
     * surgery. No-ops on rows that are already non-active (partial unique
     * index on `(platform, platform_user_id, clinic_id) WHERE status='active'`
     * permits any number of cancelled rows to coexist with the new active one).
     */
    fun cancelActive(sessionId: Long): Int {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE sessions SET status = 'cancelled', updated_at = NOW() " +
                    "WHERE id = ? AND status = 'active'",
            ).use { ps ->
                ps.setLong(1, sessionId)
                return ps.executeUpdate()
            }
        }
    }

    /**
     * AC-MG7TSR.8 — data-subject erasure (requirements §2.4): a SOFT, clinic-scoped
     * anonymization, NOT a hard row delete. The aggregate survives (node path,
     * conclusion, status, timestamps) but every PII field is scrubbed:
     *  - `platform_user_id` is overwritten with a non-reversible, per-row-unique
     *    placeholder (`anonymized-<id>`), and
     *  - each `history` turn has its `user_text_encrypted` (and any free-text) key
     *    stripped, so no decryptable PII remains.
     *
     * The placeholder embeds the row id so two anonymized sessions in the same
     * `(platform, clinic)` never collide on the `sessions_active_unique` partial
     * index (which still binds any row left in `status = 'active'`).
     *
     * Returns the number of rows affected (1 on success, 0 if the id is foreign /
     * absent — the route already enforced tenancy via the WHERE clinic_id guard).
     */
    fun anonymize(clinicId: Long, sessionId: Long): Int {
        database.getConnection().use { conn ->
            val sql = """
                UPDATE sessions SET
                    platform_user_id = 'anonymized-' || id,
                    history = COALESCE(
                        (
                            SELECT jsonb_agg(turn - 'user_text_encrypted')
                            FROM jsonb_array_elements(history) AS turn
                        ),
                        '[]'::jsonb
                    ),
                    updated_at = NOW()
                WHERE id = ? AND clinic_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, sessionId)
                ps.setLong(2, clinicId)
                return ps.executeUpdate()
            }
        }
    }

    fun updateStateAndAppendTurn(
        sessionId: Long,
        currentAlgorithmId: Long?,
        currentNodeId: String?,
        status: String?,
        turnJson: String,
    ) {
        database.getConnection().use { conn ->
            val sql = """
                UPDATE sessions SET
                    current_algorithm_id = COALESCE(?, current_algorithm_id),
                    current_node_id      = COALESCE(?, current_node_id),
                    status               = COALESCE(?, status),
                    history              = history || ?::jsonb,
                    updated_at           = NOW()
                WHERE id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                if (currentAlgorithmId != null) ps.setLong(1, currentAlgorithmId)
                else ps.setNull(1, java.sql.Types.BIGINT)
                if (currentNodeId != null) ps.setString(2, currentNodeId)
                else ps.setNull(2, java.sql.Types.VARCHAR)
                if (status != null) ps.setString(3, status)
                else ps.setNull(3, java.sql.Types.VARCHAR)
                ps.setString(4, "[$turnJson]")
                ps.setLong(5, sessionId)
                ps.executeUpdate()
            }
        }
    }

    private fun ResultSet.toSession(): Session {
        val historyRaw = when (val obj = getObject("history")) {
            is PGobject -> obj.value ?: "[]"
            is String -> obj
            null -> "[]"
            else -> obj.toString()
        }
        val historyJson = Json.parseToJsonElement(historyRaw) as JsonArray
        val currentAlgorithmId = getLong("current_algorithm_id").let { if (wasNull()) null else it }
        val currentNodeId = getString("current_node_id")
        return Session(
            id = getLong("id"),
            clinicId = getLong("clinic_id"),
            platform = getString("platform"),
            platformUserId = getString("platform_user_id"),
            language = getString("language"),
            currentAlgorithmId = currentAlgorithmId,
            currentNodeId = currentNodeId,
            history = historyJson,
            status = getString("status"),
        )
    }
}
