package com.symptree.core

import com.symptree.core.auth.TokenService
import com.symptree.core.booking.BookingService
import com.symptree.core.config.CoreConfig
import com.symptree.core.crypto.PiiCipher
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.BookingRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.db.MigrationRunner
import com.symptree.core.db.PendingBookingRepo
import com.symptree.core.db.PostgresDatabase
import com.symptree.core.db.ReminderRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.llm.OpenAiLlmRouterClient
import com.symptree.core.plugins.configureCallLogging
import com.symptree.core.plugins.configureSerialization
import com.symptree.core.plugins.configureStatusPages
import com.symptree.core.routes.admin.AdminDeps
import com.symptree.core.routes.admin.adminRoutes
import com.symptree.core.routes.botIngressRoutes
import com.symptree.core.routes.clinicRoutes
import com.symptree.core.routes.healthRoutes
import com.symptree.core.scheduler.HttpBookingCancelNotifier
import com.symptree.core.scheduler.HttpReminderDispatcher
import com.symptree.core.scheduler.ReminderScheduler
import com.symptree.core.source.DoctorRef
import com.symptree.core.source.SourceId
import com.symptree.core.source.internal.InternalScheduler
import com.symptree.core.symptomizer.AlgorithmSeedLoader
import com.symptree.core.symptomizer.SessionService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

private val logger = LoggerFactory.getLogger("com.symptree.core.CoreApp")

private const val DEFAULT_MAX_DECLINES = 3
private const val REMINDER_POLL_INTERVAL_MS = 60_000L
private const val DEFAULT_MIN_LEAD_HOURS = 24L

fun main() {
    val config = CoreConfig.fromEnv()
    logger.info("Starting symptree core service on port {}", config.port)

    val database = PostgresDatabase(config)
    MigrationRunner(database).run()
    val algorithmRepo = AlgorithmRepo(database)
    AlgorithmSeedLoader.seed(algorithmRepo)
    val clinicRepo = ClinicRepo(database)
    val sessionRepo = SessionRepo(database)
    val doctorRepo = DoctorRepo(database)
    val bookingRepo = BookingRepo(database)
    val reminderRepo = ReminderRepo(database)
    val pendingBookingRepo = PendingBookingRepo(database)
    val clinicUserRepo = ClinicUserRepo(database)
    val piiCipher = PiiCipher(config.piiKeyBytes())
    val llmRouter = OpenAiLlmRouterClient(apiKey = config.openAiApiKey, model = config.openAiModel)
    val clock = Clock.systemUTC()

    // FR-MG7TSR — clinic-admin panel auth + REST surface. The bearer token is a
    // hand-rolled HS256 signed token (secret + TTL from CoreConfig); cancelling a
    // booking pushes a semantic `booking_cancelled` notification to the patient
    // via the messaging module's /bot-internal/outgoing.
    val adminDeps = AdminDeps(
        clinics = clinicRepo,
        users = clinicUserRepo,
        doctors = doctorRepo,
        sessions = sessionRepo,
        bookings = bookingRepo,
        algorithms = algorithmRepo,
        tokenService = TokenService(
            signingSecret = config.adminTokenSecret,
            ttl = Duration.ofMinutes(config.adminTokenTtlMinutes),
            clock = clock,
        ),
        piiCipher = piiCipher,
        cancelNotifier = HttpBookingCancelNotifier(
            baseUrl = config.botInternalBaseUrl,
            sharedSecret = config.botInternalSharedSecret,
            zoneFor = { clinicId -> clinicZone(clinicRepo, clinicId) },
        ),
    )

    // AC-W03362.5 — per-request, clinic-scoped BookingService (the internal source
    // is the scheduler of record; first-available over the clinic's own doctors).
    val bookingServiceFactory: (Long) -> BookingService = { clinicId ->
        val zone = clinicZone(clinicRepo, clinicId)
        val scheduler = InternalScheduler(doctorRepo, bookingRepo, zone, clock)
        BookingService(
            scheduling = scheduler,
            booking = scheduler,
            doctors = doctorRefs(doctorRepo, clinicId),
            reminders = ReminderRepo.sink(reminderRepo, clinicId),
            clock = clock,
            maxDeclines = DEFAULT_MAX_DECLINES,
            minLead = Duration.ofHours(clinicMinLeadHours(clinicRepo, clinicId)),
            zone = zone,
        )
    }

    val sessionService = SessionService(
        clinicRepo = clinicRepo,
        algorithmRepo = algorithmRepo,
        sessionRepo = sessionRepo,
        llm = llmRouter,
        piiCipher = piiCipher,
        bookingServiceFactory = bookingServiceFactory,
        pendingBookingRepo = pendingBookingRepo,
    )
    // AC-W03362.8 — the reminder scheduler runs on its own IO dispatcher, polling
    // every 60s and POSTing due reminders to the messaging module. Survives
    // restarts (past-due `pending` rows are picked up on the first poll).
    val reminderScheduler = ReminderScheduler(
        reminderRepo = reminderRepo,
        dispatcher = HttpReminderDispatcher(config.botInternalBaseUrl, config.botInternalSharedSecret),
        clock = clock,
    )
    CoroutineScope(Dispatchers.IO).launch {
        logger.info("Reminder scheduler started (poll every {}ms)", REMINDER_POLL_INTERVAL_MS)
        while (isActive) {
            runCatching { reminderScheduler.pollOnce() }
                .onFailure { logger.error("reminder poll failed: {}", it.message, it) }
            delay(REMINDER_POLL_INTERVAL_MS)
        }
    }

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        configureSerialization()
        configureCallLogging()
        configureStatusPages()

        routing {
            healthRoutes()
            clinicRoutes(clinicRepo, clinicUserRepo, config.platformAdminApiKey)
            botIngressRoutes(clinicRepo, sessionService, config.botIngressSharedSecret)
            adminRoutes(adminDeps)
        }
    }.start(wait = true)
}

/** The clinic's IANA scheduling zone from `config.preferences.timezone` (default `UTC`). */
private fun clinicZone(clinicRepo: ClinicRepo, clinicId: Long): String =
    runCatching {
        clinicRepo.findById(clinicId)?.config?.get("preferences")?.jsonObject
            ?.get("timezone")?.jsonPrimitive?.content
    }.getOrNull() ?: "UTC"

/**
 * The clinic's minimum auto-booking lead time in hours from
 * `config.preferences.min_lead_hours` (AC-W03362.13, default 24 = tomorrow-or-later).
 */
private fun clinicMinLeadHours(clinicRepo: ClinicRepo, clinicId: Long): Long =
    runCatching {
        clinicRepo.findById(clinicId)?.config?.get("preferences")?.jsonObject
            ?.get("min_lead_hours")?.jsonPrimitive?.content?.toLong()
    }.getOrNull() ?: DEFAULT_MIN_LEAD_HOURS

/** Active doctors of [clinicId] as source-agnostic [DoctorRef]s for the booking service. */
private fun doctorRefs(doctorRepo: DoctorRepo, clinicId: Long): List<DoctorRef> =
    doctorRepo.listActive(clinicId).map {
        DoctorRef(SourceId(it.sourceId), it.externalId, it.identityId, it.fullName, it.specialistCodes)
    }
