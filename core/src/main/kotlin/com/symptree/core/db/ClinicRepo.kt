package com.symptree.core.db

import com.symptree.core.model.Clinic
import com.symptree.core.model.CreateClinicRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.postgresql.util.PGobject
import java.sql.ResultSet

/**
 * Raw-JDBC repository for the `clinics` and `clinic_algorithms` tables.
 *
 * Per technical-spec.md § 4.4, every tenant-scoped repo function takes
 * `clinicId: Long` as its first parameter. Functions marked
 * `// platform-admin only` (insert, list-all, slug lookup, algorithm-key
 * lookup) are the documented exceptions.
 */
class ClinicRepo(private val database: PostgresDatabase) {

    // platform-admin only
    fun insertClinic(request: CreateClinicRequest): Clinic {
        database.getConnection().use { conn ->
            val sql = """
                INSERT INTO clinics (slug, name, default_language, allowed_languages, config)
                VALUES (?, ?, ?, ?, ?::jsonb)
                RETURNING id, slug, name, default_language, allowed_languages, config, is_active
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, request.slug)
                ps.setString(2, request.name)
                ps.setString(3, request.defaultLanguage)
                ps.setArray(4, conn.createArrayOf("text", request.allowedLanguages.toTypedArray()))
                ps.setString(5, request.config.toString())
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT ... RETURNING produced no row" }
                    return rs.toClinic()
                }
            }
        }
    }

    // platform-admin only
    fun findBySlug(slug: String): Clinic? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, slug, name, default_language, allowed_languages, config, is_active " +
                    "FROM clinics WHERE slug = ?",
            ).use { ps ->
                ps.setString(1, slug)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toClinic() else null
                }
            }
        }
    }

    fun findById(clinicId: Long): Clinic? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, slug, name, default_language, allowed_languages, config, is_active " +
                    "FROM clinics WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toClinic() else null
                }
            }
        }
    }

    fun updateConfig(clinicId: Long, newConfig: JsonObject) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE clinics SET config = ?::jsonb, updated_at = NOW() WHERE id = ?",
            ).use { ps ->
                ps.setString(1, newConfig.toString())
                ps.setLong(2, clinicId)
                ps.executeUpdate()
            }
        }
    }

    /**
     * FR-FXJEVE — atomic settings write. Sets the two language columns AND the
     * merged `config` jsonb in a SINGLE statement, so a settings save is never
     * observable half-applied. The caller (admin route) has already validated the
     * inputs and merged the preference keys into [newConfig].
     */
    fun updateSettings(
        clinicId: Long,
        defaultLanguage: String,
        allowedLanguages: List<String>,
        newConfig: JsonObject,
    ) {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE clinics SET default_language = ?, allowed_languages = ?, " +
                    "config = ?::jsonb, updated_at = NOW() WHERE id = ?",
            ).use { ps ->
                ps.setString(1, defaultLanguage)
                ps.setArray(2, conn.createArrayOf("text", allowedLanguages.toTypedArray()))
                ps.setString(3, newConfig.toString())
                ps.setLong(4, clinicId)
                ps.executeUpdate()
            }
        }
    }

    // platform-admin only
    fun findAlgorithmIdByKey(algorithmKey: String): Long? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id FROM algorithms WHERE algorithm_key = ?",
            ).use { ps ->
                ps.setString(1, algorithmKey)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getLong(1) else null
                }
            }
        }
    }

    /**
     * AC-PWVTT4.8 — list the enabled algorithm ids for [clinicId] in the order
     * they were enabled. Used by `SessionService.applyStep` pre-pick to choose
     * an algorithm when the session has no `current_algorithm_id` yet.
     *
     * M2 simplification: returns ids only; algorithm selection picks the first
     * entry. A future iteration may rank by category / age-group or hand the
     * choice off to the LLM.
     */
    fun findEnabledAlgorithmIds(clinicId: Long): List<Long> {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT algorithm_id FROM clinic_algorithms " +
                    "WHERE clinic_id = ? AND enabled = TRUE " +
                    "ORDER BY algorithm_id ASC",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<Long>()
                    while (rs.next()) out += rs.getLong(1)
                    return out
                }
            }
        }
    }

    fun setAlgorithmEnabled(clinicId: Long, algorithmId: Long, enabled: Boolean) {
        database.getConnection().use { conn ->
            val sql = """
                INSERT INTO clinic_algorithms (clinic_id, algorithm_id, enabled)
                VALUES (?, ?, ?)
                ON CONFLICT (clinic_id, algorithm_id)
                  DO UPDATE SET enabled = EXCLUDED.enabled
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, clinicId)
                ps.setLong(2, algorithmId)
                ps.setBoolean(3, enabled)
                ps.executeUpdate()
            }
        }
    }

    private fun ResultSet.toClinic(): Clinic {
        val rawLangs = getArray("allowed_languages").array as Array<*>
        val langs = rawLangs.filterIsInstance<String>()
        val cfgRaw = when (val obj = getObject("config")) {
            is PGobject -> obj.value ?: "{}"
            is String -> obj
            null -> "{}"
            else -> obj.toString()
        }
        val cfgJson = Json.parseToJsonElement(cfgRaw) as JsonObject
        return Clinic(
            id = getLong("id"),
            slug = getString("slug"),
            name = getString("name"),
            defaultLanguage = getString("default_language"),
            allowedLanguages = langs,
            config = cfgJson,
            isActive = getBoolean("is_active"),
        )
    }
}
