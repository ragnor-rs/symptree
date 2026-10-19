package com.symptree.core.routes.admin

import com.symptree.core.auth.TokenService
import com.symptree.core.crypto.PiiCipher
import com.symptree.core.db.AlgorithmRepo
import com.symptree.core.db.BookingRepo
import com.symptree.core.db.ClinicRepo
import com.symptree.core.db.ClinicUserRepo
import com.symptree.core.db.DoctorRepo
import com.symptree.core.db.SessionRepo
import com.symptree.core.integration.PostgresTestSupport
import com.symptree.core.plugins.configureSerialization
import com.symptree.core.plugins.configureStatusPages
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import java.time.Instant

/** A recording fake for the cancel-side semantic push to `/bot-internal/outgoing`. */
class RecordingCancelNotifier : BookingCancelNotifier {
    data class Sent(
        val clinicId: Long,
        val platformUserId: String,
        val language: String,
        val doctorName: String,
        val slotAt: Instant,
    )

    val sent = mutableListOf<Sent>()

    override suspend fun bookingCancelled(
        clinicId: Long,
        platformUserId: String,
        language: String,
        doctorName: String,
        slotAt: Instant,
    ) {
        sent += Sent(clinicId, platformUserId, language, doctorName, slotAt)
    }
}

/**
 * Shared Ktor wiring for every admin-route test: builds the full
 * [AdminDeps] graph against the Testcontainers DB and mounts [adminRoutes].
 */
object AdminTestApp {

    val piiCipher = PiiCipher(ByteArray(32) { it.toByte() })

    fun deps(
        tokenService: TokenService = AdminFixtures.tokenService(),
        notifier: BookingCancelNotifier = RecordingCancelNotifier(),
    ): AdminDeps {
        val db = PostgresTestSupport.database()
        return AdminDeps(
            clinics = ClinicRepo(db),
            users = ClinicUserRepo(db),
            doctors = DoctorRepo(db),
            sessions = SessionRepo(db),
            bookings = BookingRepo(db),
            algorithms = AlgorithmRepo(db),
            tokenService = tokenService,
            piiCipher = piiCipher,
            cancelNotifier = notifier,
        )
    }

    fun ApplicationTestBuilder.installAdmin(deps: AdminDeps) {
        application {
            configureSerialization()
            configureStatusPages()
            routing { adminRoutes(deps) }
        }
    }

    fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
}
