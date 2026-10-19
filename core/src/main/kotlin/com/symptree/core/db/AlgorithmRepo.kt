package com.symptree.core.db

import com.symptree.core.symptomizer.Algorithm
import com.symptree.core.symptomizer.AlgorithmJson
import com.symptree.core.symptomizer.AlgorithmMetadata
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory

/**
 * Raw-JDBC repository for the `algorithms` table.
 *
 * Per FR-KT4697 surface:
 *  - [insert]: writes a single (algorithm_key, version) row carrying metadata + tree JSONB.
 *  - [findByKeyAndVersion]: looks up a row's id by (key, version); returns null if missing.
 *
 * `ingestedBy` is currently advisory — there is no column for it on the table.
 * It is INFO-logged so audit trails survive in the application log.
 */
class AlgorithmRepo(private val database: PostgresDatabase) {

    private val log = LoggerFactory.getLogger(AlgorithmRepo::class.java)

    // platform-admin only — writes to the global algorithms catalog (per-clinic enable lives in ClinicRepo)
    fun insert(algorithm: Algorithm, ingestedBy: String): Long {
        val metadataJson = AlgorithmJson.instance.encodeToString(
            AlgorithmMetadata.serializer(),
            algorithm.metadata,
        )
        val treeJson = AlgorithmJson.instance.encodeToString(Algorithm.serializer(), algorithm)
        database.getConnection().use { conn ->
            val sql = """
                INSERT INTO algorithms (algorithm_key, version, metadata, tree, is_active)
                VALUES (?, ?, ?::jsonb, ?::jsonb, TRUE)
                RETURNING id
            """.trimIndent()
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, algorithm.algorithmId)
                ps.setString(2, algorithm.version)
                ps.setString(3, metadataJson)
                ps.setString(4, treeJson)
                ps.executeQuery().use { rs ->
                    check(rs.next()) { "INSERT ... RETURNING produced no row" }
                    val id = rs.getLong(1)
                    log.info(
                        "algorithm inserted id={} key={} version={} ingested_by={}",
                        id,
                        algorithm.algorithmId,
                        algorithm.version,
                        ingestedBy,
                    )
                    return id
                }
            }
        }
    }

    // platform-admin only — global catalog lookup, no clinic_id
    fun findByKeyAndVersion(algorithmKey: String, version: String): Long? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id FROM algorithms WHERE algorithm_key = ? AND version = ?",
            ).use { ps ->
                ps.setString(1, algorithmKey)
                ps.setString(2, version)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) rs.getLong(1) else null
                }
            }
        }
    }

    /**
     * AC-PWVTT4.8 — load the full [Algorithm] tree by primary key.
     *
     * Used by `SessionService.applyStep` mid-walk: the session row stores
     * `current_algorithm_id` only, and the walker needs the deserialized tree
     * to advance.
     */
    fun findById(algorithmId: Long): Algorithm? {
        database.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT tree FROM algorithms WHERE id = ?",
            ).use { ps ->
                ps.setLong(1, algorithmId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val raw = when (val obj = rs.getObject("tree")) {
                        is PGobject -> obj.value ?: error("algorithm $algorithmId has null tree JSONB")
                        is String -> obj
                        else -> obj.toString()
                    }
                    return AlgorithmJson.instance.decodeFromString(Algorithm.serializer(), raw)
                }
            }
        }
    }
}
