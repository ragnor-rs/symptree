package com.symptree.core.routes

import com.symptree.core.auth.ApiKeyHasher
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.model.Clinic
import com.symptree.core.model.CreateClinicRequest
import com.symptree.core.model.CreateClinicResponse
import com.symptree.core.model.NewClinicUser
import com.symptree.core.model.SeededAdmin
import com.symptree.core.model.UpdateConfigRequest
import com.symptree.core.validation.ClinicConfigValidator
import com.symptree.core.validation.ValidationResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.SQLException
import java.util.Base64
import java.nio.charset.StandardCharsets

@Serializable
private data class ErrorResponse(
    val error: String,
    val detail: String? = null,
    val errors: List<String>? = null,
)

private val logger = LoggerFactory.getLogger("com.symptree.core.routes.clinicRoutes")

private const val API_KEY_HEADER = "X-API-Key"
private const val API_KEY_BYTES = 32
private const val API_KEY_HASH_FIELD = "api_key_hash"

// PostgreSQL SQLState 23505 — unique_violation.
// https://www.postgresql.org/docs/current/errcodes-appendix.html
private const val PG_UNIQUE_VIOLATION = "23505"

// SecureRandom is thread-safe (nextBytes is internally synchronized); reuse a
// single instance instead of constructing one per key-generation request.
private val secureRandom = SecureRandom()

/**
 * Mounts clinic-admin REST routes under `/api/v1/clinics`.
 *
 * AC-M3WYNS.2: `POST /api/v1/clinics` — gated on the platform-admin API key
 * passed via the `X-API-Key` header. Validates the body, generates a
 * clinic-admin API key server-side, stores its bcrypt hash in `config.api_key_hash`,
 * and returns the plaintext key exactly once in the response.
 */
fun Route.clinicRoutes(repo: ClinicRepo, clinicUserRepo: ClinicUserRepo, platformAdminKey: String) {
    route("/api/v1/clinics") {
        post {
            val providedKey = call.request.headers[API_KEY_HEADER]
            if (!isPlatformAdmin(providedKey, platformAdminKey)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
                return@post
            }

            val request = try {
                call.receive<CreateClinicRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_body", detail = e.message ?: "could not parse body"),
                )
                return@post
            }

            // AC-PRVSN2.5: ClinicConfigValidator also shape-checks admin_email,
            // so an invalid address is rejected here — before any insert — and
            // never leaves a half-provisioned clinic behind.
            when (val result = ClinicConfigValidator.validate(request)) {
                is ValidationResult.Invalid -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("validation_failed", errors = result.errors),
                    )
                    return@post
                }
                ValidationResult.Ok -> Unit
            }

            val adminEmail = request.adminEmail
            val plaintextKey = generateClinicAdminApiKey()
            val keyHash = ApiKeyHasher.hash(plaintextKey)
            val mergedConfig = mergeApiKeyHash(request.config, keyHash)
            val toInsert = request.copy(config = mergedConfig)

            val clinic = try {
                repo.insertClinic(toInsert)
            } catch (e: SQLException) {
                // PostgreSQL unique-violation -> 409 Conflict, not 500. Slug
                // collision is the only UNIQUE constraint on `clinics` in M2.
                if (e.sqlState == PG_UNIQUE_VIOLATION) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        ErrorResponse("slug_taken", detail = "slug '${request.slug}' is already in use"),
                    )
                    return@post
                }
                throw e
            }

            val seededAdmin = adminEmail?.let { seedAdminLogin(clinicUserRepo, clinic.id, it) }

            call.respond(
                HttpStatusCode.Created,
                CreateClinicResponse(
                    clinic = clinic.withoutApiKeyHash(),
                    clinicAdminApiKey = plaintextKey,
                    admin = seededAdmin,
                ),
            )
        }

        // AC-M3WYNS.3: GET /api/v1/clinics/{slug}
        // - Missing key  -> 401
        // - Platform-admin key + unknown slug -> 404
        // - Platform-admin key + known slug   -> 200
        // - Clinic-admin key matching slug    -> 200
        // - Clinic-admin key for other slug
        //   OR clinic-admin key for unknown slug -> 403 (do not leak existence)
        get("/{slug}") {
            val clinic = authorizeForSlug(repo, platformAdminKey) ?: return@get
            call.respond(HttpStatusCode.OK, clinic.withoutApiKeyHash())
        }

        // AC-M3WYNS.4: PUT /api/v1/clinics/{slug}/config
        // - Missing key                       -> 401
        // - Clinic-admin key not matching slug -> 403 (never 404, never 200)
        // - Platform-admin key + unknown slug  -> 404
        // - Authorized                         -> deep-merge configPatch into
        //   existing config, persist, return 200 with the merged (api_key_hash-
        //   stripped) config, and emit one INFO log line with clinic_id but no
        //   api_key_hash.
        put("/{slug}/config") {
            val clinic = authorizeForSlug(repo, platformAdminKey) ?: return@put

            val request = try {
                call.receive<UpdateConfigRequest>()
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_body", detail = e.message ?: "could not parse body"),
                )
                return@put
            }

            // Strip api_key_hash from the incoming patch — clinic admins must
            // never rotate the auth hash through this endpoint. Use structural
            // inequality so the detection survives a future refactor of
            // stripApiKeyHash that always returns a copy.
            val sanitizedPatch = stripApiKeyHash(request.configPatch)
            if (sanitizedPatch != request.configPatch) {
                logger.info("stripped {} from incoming patch for clinic_id={}",
                    API_KEY_HASH_FIELD, clinic.id)
            }

            val merged = deepMergeJsonObjects(clinic.config, sanitizedPatch)
            repo.updateConfig(clinic.id, merged)

            // Per AC-M3WYNS.4: INFO-log a redacted diff. We log per-key
            // before -> after pairs for every top-level key in the patch, with
            // api_key_hash always elided (it is also never in `sanitizedPatch`
            // and never in `clinic.config`'s loggable view).
            val redactedDiff = buildRedactedDiff(clinic.config, merged, sanitizedPatch.keys)
            logger.info("clinic config updated clinic_id={} diff={}",
                clinic.id, redactedDiff)

            val responseConfig = stripApiKeyHash(merged)
            call.respond(HttpStatusCode.OK, responseConfig)
        }

        // AC-M3WYNS.5: POST /api/v1/clinics/{slug}/algorithms/{algorithm_key}/enable
        // and POST /api/v1/clinics/{slug}/algorithms/{algorithm_key}/disable.
        //
        // Auth contract (same as AC-3/AC-4):
        //  - Missing key                          -> 401
        //  - Clinic-admin key not matching slug   -> 403 (also for unknown slug)
        //  - Platform-admin key + unknown slug    -> 404
        // After auth, the algorithm_key must exist in the `algorithms` table;
        // otherwise 404. Toggling is idempotent via an upsert in ClinicRepo.
        post("/{slug}/algorithms/{algorithm_key}/enable") {
            handleAlgorithmToggle(repo, platformAdminKey, enabled = true)
        }
        post("/{slug}/algorithms/{algorithm_key}/disable") {
            handleAlgorithmToggle(repo, platformAdminKey, enabled = false)
        }
    }
}

@Serializable
private data class AlgorithmToggleResponse(
    @SerialName("algorithm_key") val algorithmKey: String,
    val enabled: Boolean,
)

private suspend fun RoutingContext.handleAlgorithmToggle(
    repo: ClinicRepo,
    platformAdminKey: String,
    enabled: Boolean,
) {
    val clinic = authorizeForSlug(repo, platformAdminKey) ?: return
    val algorithmKey = call.parameters["algorithm_key"].orEmpty()

    val algorithmId = repo.findAlgorithmIdByKey(algorithmKey)
    if (algorithmId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("algorithm_not_found"))
        return
    }

    repo.setAlgorithmEnabled(clinic.id, algorithmId, enabled)
    call.respond(
        HttpStatusCode.OK,
        AlgorithmToggleResponse(algorithmKey = algorithmKey, enabled = enabled),
    )
}

/**
 * Resolves the `{slug}` path parameter and enforces the M3WYNS auth ladder:
 *  - Missing/blank `X-API-Key`                       -> 401, returns null.
 *  - Platform-admin key + unknown slug               -> 404, returns null.
 *  - Platform-admin key + known slug                 -> returns the clinic.
 *  - Clinic-admin key matching the slug              -> returns the clinic.
 *  - Anything else (wrong clinic key, unknown slug)  -> 403, returns null.
 *
 * The 403-not-404 behavior for clinic-admin callers is load-bearing — it
 * prevents existence leaks across tenants (see AC-M3WYNS.3 / AC-M3WYNS.7).
 *
 * On any non-null return the caller is guaranteed to be authorized for the
 * returned [Clinic]; on null the response has already been written.
 */
private suspend fun RoutingContext.authorizeForSlug(
    repo: ClinicRepo,
    platformAdminKey: String,
): Clinic? {
    val providedKey = call.request.headers[API_KEY_HEADER]
    if (providedKey.isNullOrBlank()) {
        call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
        return null
    }
    val slug = call.parameters["slug"].orEmpty()
    val platformAdmin = isPlatformAdmin(providedKey, platformAdminKey)
    val clinic = repo.findBySlug(slug)

    if (platformAdmin) {
        if (clinic == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("not_found"))
            return null
        }
        return clinic
    }

    // Clinic-admin path: never leak existence. Anything that doesn't
    // authorize the caller for *this* slug returns 403.
    if (clinic == null || !verifyClinicAdminKey(providedKey, clinic)) {
        call.respond(HttpStatusCode.Forbidden, ErrorResponse("forbidden"))
        return null
    }
    return clinic
}

private fun verifyClinicAdminKey(providedKey: String, clinic: Clinic): Boolean {
    val storedHash = clinic.config[API_KEY_HASH_FIELD]?.jsonPrimitive?.contentOrNull
        ?: return false
    return ApiKeyHasher.verify(providedKey, storedHash)
}

private fun isPlatformAdmin(providedKey: String?, platformAdminKey: String): Boolean {
    if (providedKey.isNullOrBlank() || platformAdminKey.isBlank()) return false
    // Constant-time comparison closes a timing side-channel that would
    // otherwise leak the platform-admin key prefix to a network attacker.
    val a = providedKey.toByteArray(StandardCharsets.UTF_8)
    val b = platformAdminKey.toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.isEqual(a, b)
}

// A fresh high-entropy secret: API_KEY_BYTES of SecureRandom drawn from the
// shared instance, URL-safe base64, unpadded (32 bytes -> 43 chars). Each call
// is independent, so two calls yield two distinct values.
private fun generateUrlSafeSecret(): String {
    val bytes = ByteArray(API_KEY_BYTES)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun generateClinicAdminApiKey(): String = generateUrlSafeSecret()

// AC-PRVSN2.1/.2/.3: seed exactly one active admin login with a freshly
// generated, high-entropy temp password (a value distinct from the clinic-admin
// API key). The plaintext is returned once and never logged or persisted.
private fun seedAdminLogin(clinicUserRepo: ClinicUserRepo, clinicId: Long, adminEmail: String): SeededAdmin {
    val tempPassword = generateTempPassword()
    clinicUserRepo.insert(
        NewClinicUser(
            clinicId = clinicId,
            email = adminEmail,
            passwordHash = ApiKeyHasher.hash(tempPassword),
            displayName = adminEmail,
            role = "admin",
            active = true,
        ),
    )
    return SeededAdmin(email = adminEmail, tempPassword = tempPassword)
}

// AC-PRVSN2.3: the seeded admin's one-time temp password — a SEPARATE secret,
// distinct from the clinic-admin API key (independent draws from the same
// generator), with identical entropy/shape.
private fun generateTempPassword(): String = generateUrlSafeSecret()

private fun mergeApiKeyHash(config: JsonObject, hash: String): JsonObject = buildJsonObject {
    for ((k, v) in config) {
        if (k == API_KEY_HASH_FIELD) continue
        put(k, v)
    }
    put(API_KEY_HASH_FIELD, JsonPrimitive(hash))
}

private fun Clinic.withoutApiKeyHash(): Clinic {
    if (API_KEY_HASH_FIELD !in config) return this
    return copy(config = stripApiKeyHash(config))
}

private fun stripApiKeyHash(config: JsonObject): JsonObject {
    if (API_KEY_HASH_FIELD !in config) return config
    return buildJsonObject {
        for ((k, v) in config) {
            if (k == API_KEY_HASH_FIELD) continue
            put(k, v)
        }
    }
}

/**
 * Recursively merges [patch] into [base], preferring [patch].
 *
 * Semantics:
 *  - If both `base[k]` and `patch[k]` are JSON objects, merge recursively.
 *  - Otherwise (primitive, array, or type mismatch), `patch[k]` replaces `base[k]`.
 *  - Keys present only in `base` are preserved.
 */
private fun deepMergeJsonObjects(base: JsonObject, patch: JsonObject): JsonObject = buildJsonObject {
    val keys = LinkedHashSet<String>()
    keys.addAll(base.keys)
    keys.addAll(patch.keys)
    for (key in keys) {
        val merged = mergeValues(base[key], patch[key])
        if (merged != null) put(key, merged)
    }
}

private fun mergeValues(baseValue: JsonElement?, patchValue: JsonElement?): JsonElement? {
    if (patchValue == null) return baseValue
    if (baseValue == null) return patchValue
    if (baseValue is JsonObject && patchValue is JsonObject) {
        return deepMergeJsonObjects(baseValue, patchValue)
    }
    return patchValue
}

/**
 * Per-key `before -> after` rendering of [base] vs [merged] for every key in
 * [changedKeys], suitable for an INFO log. `api_key_hash` is **always** elided
 * from both sides regardless of whether the patch touched it — the contract is
 * that the hash never appears in operational logs.
 *
 * Nested JsonObject values are rendered shallowly (one level); deeper nesting
 * is rendered as compact JSON via `toString()`. This is intentional — the log
 * is operational, not forensic; forensic audits read the DB.
 */
private fun buildRedactedDiff(
    base: JsonObject,
    merged: JsonObject,
    changedKeys: Set<String>,
): String {
    val entries = changedKeys
        .filter { it != API_KEY_HASH_FIELD }
        .sorted()
        .map { key ->
            val before = redactForLog(base[key])
            val after = redactForLog(merged[key])
            "$key: $before -> $after"
        }
    return entries.joinToString(prefix = "{", postfix = "}", separator = ", ")
}

private fun redactForLog(value: JsonElement?): String {
    if (value == null) return "<absent>"
    if (value !is JsonObject) return value.toString()
    return buildJsonObject {
        for ((k, v) in value) {
            if (k == API_KEY_HASH_FIELD) {
                put(k, JsonPrimitive("<redacted>"))
            } else {
                put(k, v)
            }
        }
    }.toString()
}
