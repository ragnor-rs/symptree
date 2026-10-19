package com.symptree.core.routes.admin

import com.symptree.core.auth.ApiKeyHasher
import com.symptree.core.auth.TokenClaims
import com.symptree.core.auth.TokenService
import com.symptree.core.crypto.PiiCipher
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.BookingRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.model.Clinic
import com.symptree.core.model.Doctor
import com.symptree.core.model.NewDoctor
import com.symptree.core.source.internal.WeeklyHours
import com.symptree.core.symptomizer.ClinicAction
import com.symptree.core.symptomizer.ConclusionNode
import com.symptree.core.symptomizer.Session
import com.symptree.core.symptomizer.Urgency
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Sends a semantic `booking-cancelled` notification to the patient (the admin
 * cancel path's side effect) — FR-MG7TSR. The production impl POSTs to the
 * messaging module's `/bot-internal/outgoing`; tests inject a recording fake.
 */
interface BookingCancelNotifier {
    suspend fun bookingCancelled(
        clinicId: Long,
        platformUserId: String,
        language: String,
        doctorName: String,
        slotAt: Instant,
    )
}

/** Hand-wired dependency bundle for the clinic-admin REST surface (FR-MG7TSR). */
data class AdminDeps(
    val clinics: ClinicRepo,
    val users: ClinicUserRepo,
    val doctors: DoctorRepo,
    val sessions: SessionRepo,
    val bookings: BookingRepo,
    val algorithms: AlgorithmRepo,
    val tokenService: TokenService,
    val piiCipher: PiiCipher,
    val cancelNotifier: BookingCancelNotifier,
)

@Serializable
private data class AdminErrorResponse(val error: String)

@Serializable
private data class LoginRequest(
    @SerialName("clinic_slug") val clinicSlug: String,
    val email: String,
    val password: String,
)

@Serializable
private data class LoginResponse(val token: String, val role: String)

@Serializable
private data class CreateDoctorRequest(
    @SerialName("full_name") val fullName: String,
    @SerialName("specialist_codes") val specialistCodes: List<String> = emptyList(),
    @SerialName("slot_minutes") val slotMinutes: Int,
    val active: Boolean = true,
)

@Serializable
private data class UpdateDoctorRequest(
    @SerialName("full_name") val fullName: String,
    @SerialName("specialist_codes") val specialistCodes: List<String> = emptyList(),
    @SerialName("slot_minutes") val slotMinutes: Int,
    val active: Boolean = true,
)

@Serializable
private data class DoctorResponse(
    val id: Long,
    @SerialName("full_name") val fullName: String,
    @SerialName("specialist_codes") val specialistCodes: List<String>,
    @SerialName("slot_minutes") val slotMinutes: Int,
    val active: Boolean,
)

private fun Doctor.toResponse() =
    DoctorResponse(id, fullName, specialistCodes, slotMinutes, active)

@Serializable
private data class WeeklyHoursRow(
    @SerialName("day_of_week") val dayOfWeek: Int,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)

@Serializable
private data class ReplaceWeeklyHoursRequest(
    @SerialName("weekly_hours") val weeklyHours: List<WeeklyHoursRow> = emptyList(),
)

@Serializable
private data class CreateTimeOffRequest(
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    val reason: String? = null,
)

@Serializable
private data class TimeOffResponse(
    val id: Long,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    val reason: String?,
)

/**
 * AC-MG7TSR.6 — redacted summary item for the session LIST endpoint.
 * `patient_ref` is a stable, non-reversible reference (NOT the raw
 * platform_user_id); no decrypted free-text is ever present here.
 */
@Serializable
private data class SessionSummaryResponse(
    val id: Long,
    @SerialName("patient_ref") val patientRef: String,
    val language: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class SessionListResponse(
    val sessions: List<SessionSummaryResponse>,
    @SerialName("next_cursor") val nextCursor: Long? = null,
)

/** One decrypted Q→A step in the DETAIL walk (AC-MG7TSR.6). */
@Serializable
private data class SessionTurnResponse(
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("user_text") val userText: String? = null,
    @SerialName("picked_option") val pickedOption: String? = null,
    val ts: String? = null,
)

/** One recommended test in a conclusion: code [name] + optional localized [prep] note. */
@Serializable
private data class AdminTestItemResponse(
    val name: String,
    val prep: String? = null,
)

/**
 * FR-XKH348 — semantic conclusion block on the session DETAIL. The `urgency` /
 * `clinic_action` enums and the `specialist` code are semantic (the SPA renders
 * the badge + localizes the code); `diagnosis` / `action` / `tests` carry
 * algorithm-authored text localized **by core** to the session language. No
 * chrome and no UI labels cross this boundary (FR-VWRNDR separation of concerns).
 */
@Serializable
private data class AdminConclusionResponse(
    val urgency: Urgency,
    val specialist: String? = null,
    val diagnosis: String,
    val action: String,
    val tests: List<AdminTestItemResponse> = emptyList(),
    @SerialName("clinic_action") val clinicAction: ClinicAction,
    @SerialName("follow_up_days") val followUpDays: Int? = null,
)

@Serializable
private data class SessionDetailResponse(
    val id: Long,
    @SerialName("patient_ref") val patientRef: String,
    @SerialName("platform_user_id") val platformUserId: String,
    val language: String,
    val status: String,
    val history: List<SessionTurnResponse>,
    val conclusion: AdminConclusionResponse? = null,
)

/** AC-MG7TSR.8 — data-subject export: the full decrypted session history as JSON. */
@Serializable
private data class SessionExportResponse(
    val id: Long,
    @SerialName("patient_ref") val patientRef: String,
    @SerialName("platform_user_id") val platformUserId: String,
    val language: String,
    val status: String,
    val history: List<SessionTurnResponse>,
)

/** AC-MG7TSR.7 — one booking in the admin list (doctor name + slot + status + feedback). */
@Serializable
private data class BookingResponse(
    val id: Long,
    @SerialName("doctor_name") val doctorName: String,
    @SerialName("slot_at") val slotAt: String,
    @SerialName("slot_end") val slotEnd: String,
    val status: String,
    @SerialName("feedback_score") val feedbackScore: Int? = null,
)

/** AC-MG7TSR.7 — admin booking mutation; only `{action:"cancel"}` is supported. */
@Serializable
private data class BookingActionRequest(
    val action: String,
    val reason: String? = null,
)

/**
 * AC-MG7TSR.7 — success body for a booking mutation (the cancelled booking's new state).
 * [notified] reports whether the patient-facing cancellation push was delivered; it is
 * `false` when the booking was cancelled but the messaging module could not be reached
 * (the cancel still succeeds — FR-XKH348 best-effort notify).
 */
@Serializable
private data class BookingActionResponse(
    val id: Long,
    val status: String,
    val notified: Boolean = true,
)

/**
 * FR-XKH348 — the clinic's available specialist codes, drawn from its ENABLED
 * algorithms' conclusion nodes. Semantic codes only; the SPA localizes each to a
 * display label (AC-XKH348.3 — selection driven by the API, not a hardcoded list).
 */
@Serializable
private data class SpecialistCodesResponse(
    @SerialName("specialist_codes") val specialistCodes: List<String>,
)

/**
 * FR-XKH348 / FR-FXJEVE — the clinic's editable settings for the SPA (semantic).
 * `first_day_of_week` (0=Sun..6=Sat, default Monday) orders the weekly-hours grid
 * per country; `timezone` is the IANA scheduling zone; `min_lead_hours` is the
 * minimum auto-booking lead time. `timezone`/`first_day_of_week`/`min_lead_hours`
 * come from `config.preferences`; the language pair are clinic columns. The SPA
 * owns rendering and is the only field shape `PUT` accepts back.
 */
@Serializable
private data class ClinicSettingsResponse(
    val timezone: String,
    @SerialName("min_lead_hours") val minLeadHours: Int,
    @SerialName("first_day_of_week") val firstDayOfWeek: Int,
    @SerialName("default_language") val defaultLanguage: String,
    @SerialName("allowed_languages") val allowedLanguages: List<String>,
)

/**
 * FR-FXJEVE — inbound full-object settings write. Same field set as
 * [ClinicSettingsResponse]; carries NO `clinic_id` (the target clinic is derived
 * only from the verified token). Validated by [validateClinicSettings] before any
 * write; unknown JSON keys (e.g. a stray `clinic_id`) are ignored by the serializer.
 */
@Serializable
private data class ClinicSettingsUpdateRequest(
    val timezone: String,
    @SerialName("min_lead_hours") val minLeadHours: Int,
    @SerialName("first_day_of_week") val firstDayOfWeek: Int,
    @SerialName("default_language") val defaultLanguage: String,
    @SerialName("allowed_languages") val allowedLanguages: List<String> = emptyList(),
)

// A fixed, valid bcrypt hash used to keep unknown-email and wrong-password paths
// time-indistinguishable: we ALWAYS run a bcrypt verify, even when no user was
// found, so the two failure modes take the same wall-clock time (no enumeration).
// Hash of the constant string "symptree-timing-equalizer" at cost 10.
private const val DUMMY_BCRYPT_HASH =
    "\$2a\$10\$1AaKtHR7.wCRAaJJWei1leg0AeDLjek24CZfrwLu92rbgD0BHqTI2"

private val logger = LoggerFactory.getLogger("com.symptree.core.routes.admin.adminRoutes")

// The role permitted to perform doctor writes (AC-MG7TSR.4).
private const val ROLE_ADMIN = "admin"

// AC-MG7TSR.6 — admin session-list page size. A full page (== this many rows)
// means more may follow, so next_cursor is emitted; a short page is terminal.
private const val SESSION_PAGE_SIZE = 50

// FR-XKH348 — default first day of the week for the schedule grid (0=Sun..6=Sat).
// Monday-first is the default; clinics override via config.preferences.first_day_of_week.
private const val DEFAULT_FIRST_DAY_OF_WEEK = 1

// FR-FXJEVE — settings defaults + bounds.
// Default min auto-booking lead time (matches CoreApp's booking-time default).
private const val DEFAULT_MIN_LEAD_HOURS = 24

// Sanity ceiling for min_lead_hours (7 days), not a product rule (FR-FXJEVE Notes).
private const val MIN_LEAD_HOURS_MAX = 168

// The platform-supported language set; allowed_languages must be a subset of this.
private val SUPPORTED_LANGUAGES = setOf("ru", "kk")

/**
 * Mounts the clinic-admin panel REST surface under `/api/v1/admin` (FR-MG7TSR).
 *
 * Wires `POST /api/v1/admin/login` (AC-MG7TSR.2) plus the bearer-token auth gate
 * (AC-MG7TSR.3) and the role gate (AC-MG7TSR.4) over the doctor routes. Doctor /
 * schedule / session / booking business logic is filled in by their own ACs.
 */
fun Route.adminRoutes(deps: AdminDeps) {
    route("/api/v1/admin") {
        post("/login") {
            handleLogin(deps)
        }

        get("/specialist-codes") {
            val claims = requireAuth(deps) ?: return@get
            handleSpecialistCodes(deps, claims)
        }

        get("/clinic-settings") {
            val claims = requireAuth(deps) ?: return@get
            handleClinicSettings(deps, claims)
        }

        put("/clinic-settings") {
            val claims = requireAuth(deps) ?: return@put
            requireRole(claims, ROLE_ADMIN) ?: return@put
            handlePutClinicSettings(deps, claims)
        }

        route("/doctors") {
            get {
                val claims = requireAuth(deps) ?: return@get
                val includeDeleted = call.request.queryParameters["include_deleted"]?.toBoolean() ?: false
                val doctors = if (includeDeleted) {
                    deps.doctors.listAll(claims.clinicId)
                } else {
                    deps.doctors.listActive(claims.clinicId)
                }
                call.respond(HttpStatusCode.OK, doctors.map { it.toResponse() })
            }
            post {
                val claims = requireAuth(deps) ?: return@post
                requireRole(claims, ROLE_ADMIN) ?: return@post
                val request = try {
                    call.receive<CreateDoctorRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("invalid_body"))
                    return@post
                }
                val created = deps.doctors.insert(
                    NewDoctor(
                        clinicId = claims.clinicId,
                        sourceId = "internal",
                        externalId = "admin-${UUID.randomUUID()}",
                        fullName = request.fullName,
                        specialistCodes = request.specialistCodes,
                        slotMinutes = request.slotMinutes,
                        active = request.active,
                    ),
                )
                call.respond(HttpStatusCode.Created, created.toResponse())
            }

            route("/{id}") {
                patch {
                    val claims = requireDoctor(deps) ?: return@patch
                    requireRole(claims, ROLE_ADMIN) ?: return@patch
                    val request = try {
                        call.receive<UpdateDoctorRequest>()
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("invalid_body"))
                        return@patch
                    }
                    val id = call.parameters["id"]!!.toLong()
                    val updated = deps.doctors.update(
                        clinicId = claims.clinicId,
                        id = id,
                        fullName = request.fullName,
                        specialistCodes = request.specialistCodes,
                        slotMinutes = request.slotMinutes,
                        active = request.active,
                    )
                    if (updated == null) {
                        call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("forbidden"))
                        return@patch
                    }
                    call.respond(HttpStatusCode.OK, updated.toResponse())
                }
                delete {
                    val claims = requireDoctor(deps) ?: return@delete
                    requireRole(claims, ROLE_ADMIN) ?: return@delete
                    val id = call.parameters["id"]!!.toLong()
                    deps.doctors.softDelete(claims.clinicId, id)
                    call.respond(HttpStatusCode.NoContent)
                }
                get("/weekly-hours") {
                    requireDoctor(deps) ?: return@get
                    val doctorId = call.parameters["id"]!!.toLong()
                    val rows = deps.doctors.weeklyHours(doctorId).map {
                        WeeklyHoursRow(it.dayOfWeek, it.start.toString(), it.end.toString())
                    }
                    call.respond(HttpStatusCode.OK, ReplaceWeeklyHoursRequest(rows))
                }
                put("/weekly-hours") {
                    val claims = requireDoctor(deps) ?: return@put
                    requireRole(claims, ROLE_ADMIN) ?: return@put
                    handleReplaceWeeklyHours(deps)
                }
                post("/time-off") {
                    val claims = requireDoctor(deps) ?: return@post
                    requireRole(claims, ROLE_ADMIN) ?: return@post
                    handleCreateTimeOff(deps)
                }
                get("/time-off") {
                    requireDoctor(deps) ?: return@get
                    val doctorId = call.parameters["id"]!!.toLong()
                    val rows = deps.doctors.listTimeOff(doctorId).map {
                        TimeOffResponse(it.id, it.start.toString(), it.end.toString(), it.reason)
                    }
                    call.respond(HttpStatusCode.OK, rows)
                }
                delete("/time-off/{timeOffId}") {
                    val claims = requireDoctor(deps) ?: return@delete
                    requireRole(claims, ROLE_ADMIN) ?: return@delete
                    val doctorId = call.parameters["id"]!!.toLong()
                    val timeOffId = call.parameters["timeOffId"]?.toLongOrNull()
                    if (timeOffId == null) {
                        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("invalid_body"))
                        return@delete
                    }
                    deps.doctors.deleteTimeOff(doctorId, timeOffId)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

        route("/sessions") {
            get {
                val claims = requireAuth(deps) ?: return@get
                handleListSessions(deps, claims)
            }

            route("/{id}") {
                get {
                    val claims = requireSession(deps) ?: return@get
                    handleSessionDetail(deps, claims)
                }
                get("/export") {
                    val claims = requireSession(deps) ?: return@get
                    handleSessionExport(deps, claims)
                }
                delete {
                    val claims = requireSession(deps) ?: return@delete
                    requireRole(claims, ROLE_ADMIN) ?: return@delete
                    handleSessionDelete(deps, claims)
                }
            }
        }

        route("/bookings") {
            get {
                val claims = requireAuth(deps) ?: return@get
                handleListBookings(deps, claims)
            }
            route("/{id}") {
                patch {
                    val claims = requireBooking(deps) ?: return@patch
                    handleCancelBooking(deps, claims)
                }
            }
        }
    }
}

/**
 * AC-MG7TSR.5 — replace the doctor's full weekly-hours set. Parses + validates the
 * body (each row `end_time > start_time`, and no two rows on the SAME weekday
 * overlap) entirely in Kotlin, returning a structured 422 BEFORE any DB write so a
 * rejected replace never partially persists. Split shifts (multiple non-overlapping
 * intervals per weekday) are allowed.
 */
private suspend fun RoutingContext.handleReplaceWeeklyHours(deps: AdminDeps) {
    val request = try {
        call.receive<ReplaceWeeklyHoursRequest>()
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("invalid_body"))
        return
    }
    val doctorId = call.parameters["id"]!!.toLong()

    val rows = try {
        request.weeklyHours.map {
            WeeklyHours(
                dayOfWeek = it.dayOfWeek,
                start = LocalTime.parse(it.startTime),
                end = LocalTime.parse(it.endTime),
            )
        }
    } catch (e: DateTimeParseException) {
        call.respond(HttpStatusCode.UnprocessableEntity, AdminErrorResponse("invalid_time"))
        return
    }

    // Each interval must be non-inverted.
    if (rows.any { !it.end.isAfter(it.start) }) {
        call.respond(HttpStatusCode.UnprocessableEntity, AdminErrorResponse("inverted_range"))
        return
    }
    // No two rows on the same weekday may overlap (half-open [start, end)).
    if (hasSameDayOverlap(rows)) {
        call.respond(HttpStatusCode.UnprocessableEntity, AdminErrorResponse("overlapping_rows"))
        return
    }

    deps.doctors.replaceWeeklyHours(doctorId, rows)
    call.respond(HttpStatusCode.OK, ReplaceWeeklyHoursRequest(request.weeklyHours))
}

/** True if any two weekly-hours rows on the SAME `dayOfWeek` overlap as half-open `[start, end)`. */
private fun hasSameDayOverlap(rows: List<WeeklyHours>): Boolean {
    return rows.groupBy { it.dayOfWeek }.values.any { day ->
        val sorted = day.sortedBy { it.start }
        sorted.zipWithNext().any { (a, b) -> b.start.isBefore(a.end) }
    }
}

/**
 * AC-MG7TSR.5 — add a one-off time-off window. Validates `end_at > start_at` in
 * Kotlin, returning a structured 422 before the DB write.
 */
private suspend fun RoutingContext.handleCreateTimeOff(deps: AdminDeps) {
    val request = try {
        call.receive<CreateTimeOffRequest>()
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("invalid_body"))
        return
    }
    val doctorId = call.parameters["id"]!!.toLong()

    val start: Instant
    val end: Instant
    try {
        start = Instant.parse(request.startAt)
        end = Instant.parse(request.endAt)
    } catch (e: DateTimeParseException) {
        call.respond(HttpStatusCode.UnprocessableEntity, AdminErrorResponse("invalid_time"))
        return
    }

    if (!end.isAfter(start)) {
        call.respond(HttpStatusCode.UnprocessableEntity, AdminErrorResponse("inverted_range"))
        return
    }

    val id = deps.doctors.addTimeOff(doctorId, start, end, request.reason)
    call.respond(HttpStatusCode.Created, TimeOffResponse(id, request.startAt, request.endAt, request.reason))
}

/** Forbid the request unless [claims] carries [role]; responds 403 and returns null otherwise. */
private suspend fun RoutingContext.requireRole(claims: TokenClaims, role: String): Unit? {
    if (claims.role != role) {
        call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("forbidden"))
        return null
    }
    return Unit
}

/**
 * AC-MG7TSR.3 — token-derived tenancy guard. Authenticates the bearer, then
 * checks that the `{id}` path param names a resource owned by the TOKEN's clinic.
 * A foreign or absent id is answered 403 (never 404/200) so the two are
 * indistinguishable and existence never leaks. Returns the verified claims on
 * success, or null after having responded the appropriate status.
 *
 * `clinicId` is read ONLY from [TokenClaims]; the path `{id}` selects the row but
 * never the tenant — a request can only ever act on its own clinic.
 */
private suspend fun RoutingContext.requireTenant(
    deps: AdminDeps,
    ownedByClinic: (clinicId: Long, id: Long) -> Boolean,
): TokenClaims? {
    val claims = requireAuth(deps) ?: return null
    val id = call.parameters["id"]?.toLongOrNull()
    if (id == null || !ownedByClinic(claims.clinicId, id)) {
        call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("forbidden"))
        return null
    }
    return claims
}

private suspend fun RoutingContext.requireDoctor(deps: AdminDeps): TokenClaims? =
    requireTenant(deps) { clinicId, id -> deps.doctors.findById(clinicId, id) != null }

private suspend fun RoutingContext.requireSession(deps: AdminDeps): TokenClaims? =
    requireTenant(deps) { clinicId, id -> deps.sessions.existsForClinic(clinicId, id) }

private suspend fun RoutingContext.requireBooking(deps: AdminDeps): TokenClaims? =
    requireTenant(deps) { clinicId, id -> deps.bookings.existsForClinic(clinicId, id) }

/**
 * AC-MG7TSR.2 — clinic-scoped login. On success issues a signed bearer; on any
 * failure (unknown clinic / unknown email / wrong password / inactive user)
 * responds an identical 401 body. To defeat timing-based user enumeration we
 * ALWAYS run exactly one bcrypt verify — against the real hash when a user is
 * found, against [DUMMY_BCRYPT_HASH] otherwise.
 */
private suspend fun RoutingContext.handleLogin(deps: AdminDeps) {
    val request = try {
        call.receive<LoginRequest>()
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("invalid_body"))
        return
    }

    val clinic = deps.clinics.findBySlug(request.clinicSlug)
    val user = clinic?.let { deps.users.findByClinicAndEmail(it.id, request.email) }

    val hashToCheck = user?.passwordHash ?: DUMMY_BCRYPT_HASH
    val passwordOk = ApiKeyHasher.verify(request.password, hashToCheck)

    if (clinic == null || user == null || !user.active || !passwordOk) {
        call.respond(HttpStatusCode.Unauthorized, AdminErrorResponse("unauthorized"))
        return
    }

    val token = deps.tokenService.issue(
        clinicId = user.clinicId,
        clinicUserId = user.id,
        role = user.role,
    )
    call.respond(HttpStatusCode.OK, LoginResponse(token = token, role = user.role))
}

/**
 * AC-MG7TSR.6 — clinic-scoped, cursor-paginated session LIST. Emits a redacted
 * summary only (patient ref, language, status, timestamp) — never the decrypted
 * free-text history. `clinic_id` comes ONLY from the verified token claims.
 */
private suspend fun RoutingContext.handleListSessions(deps: AdminDeps, claims: TokenClaims) {
    val q = call.request.queryParameters
    val status = q["status"]?.takeIf { it.isNotBlank() }
    val window = parseInstantWindow(q["from"], q["to"]) ?: return
    val (from, to) = window
    val cursor = q["cursor"]?.toLongOrNull()

    val rows = deps.sessions.listForClinic(claims.clinicId, status, from, to, cursor, SESSION_PAGE_SIZE)
    val items = rows.map {
        SessionSummaryResponse(
            id = it.id,
            patientRef = patientRef(it.platformUserId),
            language = it.language,
            status = it.status,
            createdAt = it.createdAt.toString(),
        )
    }
    // Terminal page (fewer than a full page) → no next_cursor, so a paging client
    // can tell it has reached the end instead of issuing one more empty request.
    val nextCursor = if (rows.size == SESSION_PAGE_SIZE) rows.last().id else null
    call.respond(
        HttpStatusCode.OK,
        SessionListResponse(sessions = items, nextCursor = nextCursor),
    )
}

/**
 * AC-MG7TSR.6 — clinic-scoped session DETAIL. The tenancy guard already proved
 * the row belongs to the token's clinic; here we re-read it scoped to that clinic
 * and decrypt each history turn's `user_text_encrypted` server-side into a
 * plaintext `user_text` field for the owning clinic only.
 */
private suspend fun RoutingContext.handleSessionDetail(deps: AdminDeps, claims: TokenClaims) {
    val session = loadDecryptableSession(deps, claims) ?: return
    call.respond(
        HttpStatusCode.OK,
        SessionDetailResponse(
            id = session.id,
            patientRef = patientRef(session.platformUserId),
            platformUserId = session.platformUserId,
            language = session.language,
            status = session.status,
            history = decryptTurns(deps, session.history),
            conclusion = resolveConclusion(deps, session),
        ),
    )
}

/**
 * FR-XKH348 — reconstruct the terminal conclusion for a CONCLUDED session as a
 * semantic block. Returns null unless the session is `concluded` AND its current
 * node resolves to a [ConclusionNode] in its algorithm. Algorithm-authored text
 * is localized to the session language; enums + the specialist code pass through
 * as semantic values.
 */
private fun resolveConclusion(deps: AdminDeps, session: Session): AdminConclusionResponse? {
    if (session.status != "concluded") return null
    val algorithmId = session.currentAlgorithmId ?: return null
    val nodeId = session.currentNodeId ?: return null
    val algorithm = deps.algorithms.findById(algorithmId) ?: return null
    val node = algorithm.nodes[nodeId] as? ConclusionNode ?: return null
    val lang = session.language
    val tests = (node.tests + node.additionalTests).map { name ->
        val prep = node.preparation[name]?.let { localize(it, lang) }?.takeIf { it.isNotBlank() }
        AdminTestItemResponse(name = name, prep = prep)
    }
    return AdminConclusionResponse(
        urgency = node.urgency,
        specialist = node.specialist,
        diagnosis = localize(node.diagnosis, lang),
        action = localize(node.action, lang),
        tests = tests,
        clinicAction = node.clinicAction,
        followUpDays = node.followUpDays,
    )
}

/**
 * FR-XKH348 — the clinic's specialist codes, gathered from every ENABLED
 * algorithm's conclusion nodes, deduped and sorted. Semantic codes only; the SPA
 * localizes them. `clinic_id` comes ONLY from the verified token claims.
 */
private suspend fun RoutingContext.handleSpecialistCodes(deps: AdminDeps, claims: TokenClaims) {
    val codes = deps.clinics.findEnabledAlgorithmIds(claims.clinicId)
        .mapNotNull { deps.algorithms.findById(it) }
        .flatMap { algorithm -> algorithm.nodes.values.filterIsInstance<ConclusionNode>() }
        .mapNotNull { it.specialist }
        .distinct()
        .sorted()
    call.respond(HttpStatusCode.OK, SpecialistCodesResponse(codes))
}

/**
 * FR-XKH348 / FR-FXJEVE — the clinic's settings (timezone + first-day-of-week +
 * min-lead-hours + languages) for the SPA. `first_day_of_week` (0=Sun..6=Sat)
 * defaults to Monday; `timezone` defaults to UTC; `min_lead_hours` defaults to 24.
 * The preference keys come from `config.preferences`; `clinic_id` comes only from
 * the token.
 */
private suspend fun RoutingContext.handleClinicSettings(deps: AdminDeps, claims: TokenClaims) {
    val clinic = deps.clinics.findById(claims.clinicId)
    if (clinic == null) {
        call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("forbidden"))
        return
    }
    call.respond(HttpStatusCode.OK, clinic.toSettingsResponse())
}

/** Reads the semantic settings view from a clinic's columns + `config.preferences`. */
private fun Clinic.toSettingsResponse(): ClinicSettingsResponse {
    val prefs = config["preferences"]?.jsonObject
    val timezone = prefs?.get("timezone")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "UTC"
    val minLead = prefs?.get("min_lead_hours")?.jsonPrimitive?.intOrNull ?: DEFAULT_MIN_LEAD_HOURS
    val firstDay = prefs?.get("first_day_of_week")?.jsonPrimitive?.intOrNull?.takeIf { it in 0..6 }
        ?: DEFAULT_FIRST_DAY_OF_WEEK
    return ClinicSettingsResponse(
        timezone = timezone,
        minLeadHours = minLead,
        firstDayOfWeek = firstDay,
        defaultLanguage = defaultLanguage,
        allowedLanguages = allowedLanguages,
    )
}

/**
 * FR-FXJEVE — admin-only full-object settings write. Validates every field, then
 * persists atomically (one `UPDATE`) by merging the three preference keys into
 * `config.preferences` (preserving all other config) and setting the two language
 * columns. Responds the same shape `GET` returns so the SPA re-renders from it.
 * `clinic_id` comes ONLY from the token; any body-supplied `clinic_id` is ignored.
 */
private suspend fun RoutingContext.handlePutClinicSettings(deps: AdminDeps, claims: TokenClaims) {
    val request = try {
        call.receive<ClinicSettingsUpdateRequest>()
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("invalid_body"))
        return
    }

    val validationError = validateClinicSettings(request)
    if (validationError != null) {
        call.respond(HttpStatusCode.UnprocessableEntity, AdminErrorResponse(validationError))
        return
    }

    val clinic = deps.clinics.findById(claims.clinicId)
    if (clinic == null) {
        call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("forbidden"))
        return
    }

    val newConfig = mergeSettingsIntoConfig(clinic.config, request)
    deps.clinics.updateSettings(
        clinicId = claims.clinicId,
        defaultLanguage = request.defaultLanguage,
        allowedLanguages = request.allowedLanguages,
        newConfig = newConfig,
    )

    // Re-read so the response is byte-identical to a subsequent GET (AC-FXJEVE.2).
    val updated = deps.clinics.findById(claims.clinicId) ?: clinic
    call.respond(HttpStatusCode.OK, updated.toSettingsResponse())
}

/**
 * FR-FXJEVE field validation (AC-FXJEVE.4/.5). Returns a semantic error code on the
 * first violation, or null when every field is valid. Safe-edit language guardrails:
 * non-empty set, default ∈ set, and every code within [SUPPORTED_LANGUAGES].
 */
private fun validateClinicSettings(req: ClinicSettingsUpdateRequest): String? {
    if (req.timezone !in ZoneId.getAvailableZoneIds()) return "invalid_timezone"
    if (req.minLeadHours !in 0..MIN_LEAD_HOURS_MAX) return "invalid_min_lead_hours"
    if (req.firstDayOfWeek !in 0..6) return "invalid_first_day_of_week"
    if (req.allowedLanguages.isEmpty()) return "invalid_languages"
    if (req.defaultLanguage !in req.allowedLanguages) return "invalid_languages"
    if ((req.allowedLanguages + req.defaultLanguage).any { it !in SUPPORTED_LANGUAGES }) return "invalid_languages"
    return null
}

/**
 * Deep-merges the three settings preference keys into `config.preferences` while
 * preserving every other `preferences` key AND every other top-level `config` key
 * (e.g. `api_key_hash`). This is what keeps the single-statement write non-lossy.
 */
private fun mergeSettingsIntoConfig(base: JsonObject, req: ClinicSettingsUpdateRequest): JsonObject {
    val basePrefs = base["preferences"]?.jsonObject ?: JsonObject(emptyMap())
    val newPrefs = buildJsonObject {
        for ((k, v) in basePrefs) put(k, v)
        put("timezone", req.timezone)
        put("min_lead_hours", req.minLeadHours)
        put("first_day_of_week", req.firstDayOfWeek)
    }
    return buildJsonObject {
        for ((k, v) in base) put(k, v)
        put("preferences", newPrefs)
    }
}

/** Algorithm-authored multilingual text → the session language, with `en`/first-value fallback. */
private fun localize(map: Map<String, String>, lang: String): String =
    map[lang] ?: map["en"] ?: map.values.firstOrNull() ?: ""

/**
 * Re-reads the `{id}` session scoped to the token's clinic for the DETAIL
 * (AC-MG7TSR.6) and EXPORT (AC-MG7TSR.8) routes. The tenancy gate already proved
 * ownership; this is defence-in-depth — a foreign/absent row responds 403 and
 * returns null (never leaking existence) so the caller can `?: return`.
 */
private suspend fun RoutingContext.loadDecryptableSession(
    deps: AdminDeps,
    claims: TokenClaims,
): Session? {
    val id = call.parameters["id"]!!.toLong()
    val session = deps.sessions.findByIdForClinic(claims.clinicId, id)
    if (session == null) {
        call.respond(HttpStatusCode.Forbidden, AdminErrorResponse("forbidden"))
        return null
    }
    return session
}

/**
 * Decrypts each history turn's `user_text_encrypted` server-side into a plaintext
 * `user_text` field. Shared by the DETAIL (AC-MG7TSR.6) and EXPORT (AC-MG7TSR.8)
 * routes — the owning clinic is the only caller able to reach this code.
 */
private fun decryptTurns(deps: AdminDeps, history: List<kotlinx.serialization.json.JsonElement>): List<SessionTurnResponse> =
    history.map { element ->
        val obj = element as? JsonObject ?: JsonObject(emptyMap())
        val encrypted = obj["user_text_encrypted"]?.jsonPrimitive?.contentOrNull
        val userText = encrypted?.let { deps.piiCipher.decrypt(it) }
        SessionTurnResponse(
            nodeId = obj["node_id"]?.jsonPrimitive?.contentOrNull,
            userText = userText,
            pickedOption = obj["picked_option"]?.jsonPrimitive?.contentOrNull,
            ts = obj["ts"]?.jsonPrimitive?.contentOrNull,
        )
    }

/**
 * AC-MG7TSR.8 — data-subject right-to-export (requirements NFR-2). Returns the
 * full DECRYPTED session history as JSON for the owning clinic. The tenancy guard
 * already proved the row belongs to the token's clinic; we re-read it scoped to
 * that clinic and decrypt each turn server-side.
 */
private suspend fun RoutingContext.handleSessionExport(deps: AdminDeps, claims: TokenClaims) {
    val session = loadDecryptableSession(deps, claims) ?: return
    call.respond(
        HttpStatusCode.OK,
        SessionExportResponse(
            id = session.id,
            patientRef = patientRef(session.platformUserId),
            platformUserId = session.platformUserId,
            language = session.language,
            status = session.status,
            history = decryptTurns(deps, session.history),
        ),
    )
}

/**
 * AC-MG7TSR.8 — data-subject right-to-erasure (requirements §2.4). NOT a hard
 * delete: the aggregate row survives but all PII is scrubbed (platform_user_id
 * anonymized, free-text history stripped). Admin role required (gated by caller).
 */
private suspend fun RoutingContext.handleSessionDelete(deps: AdminDeps, claims: TokenClaims) {
    val id = call.parameters["id"]!!.toLong()
    deps.sessions.anonymize(claims.clinicId, id)
    call.respond(HttpStatusCode.NoContent)
}

/**
 * AC-MG7TSR.7 — clinic-scoped booking LIST. Filterable by `status` and a
 * `slot_at` window (`from`/`to`, ISO-8601 instants). `clinic_id` comes ONLY from
 * the verified token claims; each row carries the doctor display name, slot
 * window, status and (post-visit) feedback score.
 */
private suspend fun RoutingContext.handleListBookings(deps: AdminDeps, claims: TokenClaims) {
    val q = call.request.queryParameters
    val status = q["status"]?.takeIf { it.isNotBlank() }
    val (from, to) = parseInstantWindow(q["from"], q["to"]) ?: return
    val rows = deps.bookings.listForClinic(claims.clinicId, status, from, to).map {
        BookingResponse(
            id = it.id,
            doctorName = it.doctorName,
            slotAt = it.slotAt.toString(),
            slotEnd = it.slotEnd.toString(),
            status = it.status,
            feedbackScore = it.feedbackScore,
        )
    }
    call.respond(HttpStatusCode.OK, rows)
}

/**
 * AC-MG7TSR.7 — admin cancel. The tenancy guard already proved the booking
 * belongs to the token's clinic. Flipping a `'confirmed'` row to `'cancelled'`
 * frees the slot (the internal `EXCLUDE` guard only binds confirmed rows). A
 * successful cancel pushes a semantic `booking-cancelled` notification to the
 * patient (resolved via the booking's session). Cancelling an already-cancelled
 * booking matches no confirmed row -> 409.
 */
private suspend fun RoutingContext.handleCancelBooking(deps: AdminDeps, claims: TokenClaims) {
    val request = try {
        call.receive<BookingActionRequest>()
    } catch (e: Exception) {
        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("invalid_body"))
        return
    }
    if (request.action != "cancel") {
        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse("unsupported_action"))
        return
    }
    val id = call.parameters["id"]!!.toLong()
    val cancelled = deps.bookings.cancelById(claims.clinicId, id)
    if (cancelled == null) {
        call.respond(HttpStatusCode.Conflict, AdminErrorResponse("not_cancellable"))
        return
    }
    // Best-effort patient notification. The cancel has already committed (the slot
    // is freed), so a messaging-module failure must NOT undo it or fail the request —
    // we log a warning and report notified=false so the panel can surface it.
    val session = deps.sessions.findById(cancelled.sessionId)
    val notified = if (session == null) {
        false
    } else {
        try {
            deps.cancelNotifier.bookingCancelled(
                clinicId = claims.clinicId,
                platformUserId = session.platformUserId,
                language = session.language,
                doctorName = cancelled.doctorName,
                slotAt = cancelled.slotAt,
            )
            true
        } catch (e: Exception) {
            logger.warn(
                "booking {} cancelled but patient notification failed (clinic_id={}): {}",
                id, claims.clinicId, e.message,
            )
            false
        }
    }
    call.respond(HttpStatusCode.OK, BookingActionResponse(id = id, status = "cancelled", notified = notified))
}

/**
 * Parses the shared `from`/`to` ISO-8601 instant window query params. Blank/absent
 * bounds map to null (unbounded). On a malformed bound responds 400 with the
 * matching `invalid_from` / `invalid_to` error and returns null so the caller can
 * `?: return`; otherwise returns the `(from, to)` pair. Shared by the session
 * (AC-MG7TSR.6) and booking (AC-MG7TSR.7) list endpoints.
 */
private suspend fun RoutingContext.parseInstantWindow(
    rawFrom: String?,
    rawTo: String?,
): Pair<Instant?, Instant?>? {
    val from = parseInstantBound(rawFrom, "invalid_from") ?: return null
    val to = parseInstantBound(rawTo, "invalid_to") ?: return null
    return from.value to to.value
}

/** Boxes the parsed (possibly-null) bound so a null *value* is distinguishable from a parse failure. */
private data class InstantBound(val value: Instant?)

private suspend fun RoutingContext.parseInstantBound(raw: String?, errorKey: String): InstantBound? {
    val trimmed = raw?.takeIf { it.isNotBlank() } ?: return InstantBound(null)
    return try {
        InstantBound(Instant.parse(trimmed))
    } catch (e: DateTimeParseException) {
        call.respond(HttpStatusCode.BadRequest, AdminErrorResponse(errorKey))
        null
    }
}

/** Stable, non-reversible patient reference derived from the platform user id. */
private fun patientRef(platformUserId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(platformUserId.toByteArray(Charsets.UTF_8))
    return "p_" + digest.joinToString("") { "%02x".format(it) }.take(12)
}

/**
 * AC-MG7TSR.3 — bearer-token auth gate. Returns verified [TokenClaims] for a
 * valid `Authorization: Bearer <token>` header, or responds 401 and returns null
 * for a missing / malformed / tampered / expired token.
 */
private suspend fun RoutingContext.requireAuth(deps: AdminDeps): TokenClaims? {
    val header = call.request.headers["Authorization"]
    val token = header?.removePrefix("Bearer ")?.trim()
    val claims = token?.takeIf { it.isNotBlank() }?.let { deps.tokenService.verify(it) }
    if (claims == null) {
        call.respond(HttpStatusCode.Unauthorized, AdminErrorResponse("unauthorized"))
        return null
    }
    return claims
}
