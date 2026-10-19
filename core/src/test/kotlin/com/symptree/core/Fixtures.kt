package com.symptree.core

import com.symptree.core.auth.ApiKeyHasher
import com.symptree.core.db.ClinicRepo
import com.symptree.core.model.Clinic
import com.symptree.core.model.CreateClinicRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicInteger

object Fixtures {

    private val seq = AtomicInteger(0)

    data class ClinicFixture(
        val clinic: Clinic,
        val apiKey: String,
    )

    /**
     * Insert a clinic via [ClinicRepo.insertClinic] and return the persisted [Clinic] together
     * with the plaintext clinic-admin API key. The hash of [apiKey] is stored in `clinic.config`
     * under `api_key_hash` (matching the production wire format).
     */
    fun fixtureClinic(
        repo: ClinicRepo,
        slug: String = "clinic-${seq.incrementAndGet()}",
        name: String = "Fixture Clinic $slug",
        defaultLanguage: String = "ru",
        allowedLanguages: List<String> = listOf("ru", "kk"),
        config: JsonObject = JsonObject(emptyMap()),
        apiKey: String = "ck_test_${slug}_${seq.get()}",
    ): ClinicFixture {
        val hash = ApiKeyHasher.hash(apiKey)
        val merged = buildJsonObject {
            for ((k, v) in config) put(k, v)
            put("api_key_hash", JsonPrimitive(hash))
        }
        val request = CreateClinicRequest(
            slug = slug,
            name = name,
            defaultLanguage = defaultLanguage,
            allowedLanguages = allowedLanguages,
            config = merged,
        )
        val clinic = repo.insertClinic(request)
        return ClinicFixture(clinic, apiKey)
    }
}
