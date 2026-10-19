package com.symptree.core.db

import com.symptree.core.model.ClinicUser
import com.symptree.core.model.NewClinicUser
import java.sql.ResultSet

/**
 * Raw-JDBC repository for `clinic_users` (FR-MG7TSR) — the clinic-scoped human
 * auth boundary for the admin panel.
 *
 * `password_hash` carries a bcrypt hash supplied by the caller; plaintext never
 * crosses this boundary (see [NewClinicUser]). Email is unique per clinic, so
 * [findByClinicAndEmail] isolates by `clinic_id`.
 */
class ClinicUserRepo(private val database: PostgresDatabase) {

    private val columns =
        "id, clinic_id, email, password_hash, display_name, role, active"

    fun insert(user: NewClinicUser): ClinicUser {
        database.getConnection().use { conn ->
            val sql = """
                INSERT INTO clinic_users (clinic_id, email, password_hash, display_name, role, active)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING $columns
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setLong(1, user.clinicId)
                ps.setString(2, user.email)
                ps.setString(3, user.passwordHash)
                ps.setString(4, user.displayName)
                ps.setString(5, user.role)
                ps.setBoolean(6, user.active)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT ... RETURNING produced no row" }
                    return rs.toClinicUser()
                }
            }
        }
    }

    fun findByClinicAndEmail(clinicId: Long, email: String): ClinicUser? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT $columns FROM clinic_users WHERE clinic_id = ? AND email = ?",
            ).use { ps ->
                ps.setLong(1, clinicId)
                ps.setString(2, email)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.toClinicUser() else null
                }
            }
        }
    }

    private fun ResultSet.toClinicUser(): ClinicUser = ClinicUser(
        id = getLong("id"),
        clinicId = getLong("clinic_id"),
        email = getString("email"),
        passwordHash = getString("password_hash"),
        displayName = getString("display_name"),
        role = getString("role"),
        active = getBoolean("active"),
    )
}
