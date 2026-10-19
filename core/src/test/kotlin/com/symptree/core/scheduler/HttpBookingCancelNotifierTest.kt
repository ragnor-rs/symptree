package com.symptree.core.scheduler

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FR-MG7TSR (AC-MG7TSR.7) — the production [HttpBookingCancelNotifier] POSTs a
 * semantic `booking_cancelled` payload to the messaging module's
 * `/bot-internal/outgoing` with the shared secret in `X-Internal-Secret`, and
 * resolves the clinic zone via the injected `zoneFor` lambda.
 */
class HttpBookingCancelNotifierTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts a semantic booking_cancelled payload with the shared secret header`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val slot = Instant.parse("2026-06-20T09:30:00Z")
        val notifier = HttpBookingCancelNotifier(
            baseUrl = server.url("").toString().trimEnd('/'),
            sharedSecret = "internal-secret",
            zoneFor = { clinicId -> if (clinicId == 7L) "Asia/Almaty" else "UTC" },
        )

        runBlocking {
            notifier.bookingCancelled(
                clinicId = 7L,
                platformUserId = "tg-42",
                language = "kk",
                doctorName = "Dr. House",
                slotAt = slot,
            )
        }

        val recorded = server.takeRequest()
        assertEquals("/bot-internal/outgoing", recorded.path)
        assertEquals("internal-secret", recorded.getHeader("X-Internal-Secret"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"kind\":\"booking_cancelled\""), "wrong kind: $body")
        assertTrue(body.contains("\"platform_user_id\":\"tg-42\""), "missing platform_user_id: $body")
        assertTrue(body.contains("\"language\":\"kk\""), "missing language: $body")
        assertTrue(body.contains("\"doctor_name\":\"Dr. House\""), "missing doctor_name: $body")
        assertTrue(body.contains("\"slot_at\":${slot.toEpochMilli()}"), "slot_at must be epoch millis: $body")
        assertTrue(body.contains("\"clinic_zone\":\"Asia/Almaty\""), "zoneFor not applied: $body")
    }

    @Test
    fun `a non-200 response throws so the caller can surface the failure`() {
        server.enqueue(MockResponse().setResponseCode(401))
        val notifier = HttpBookingCancelNotifier(
            baseUrl = server.url("").toString().trimEnd('/'),
            sharedSecret = "wrong",
            zoneFor = { "UTC" },
        )
        assertFailsWith<IllegalStateException> {
            runBlocking {
                notifier.bookingCancelled(1L, "tg-1", "ru", "Dr. X", Instant.parse("2026-06-20T09:30:00Z"))
            }
        }
    }
}
