package com.symptree.core

import com.symptree.core.plugins.configureSerialization
import com.symptree.core.routes.healthRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRoutesTest {
    @Test
    fun `health endpoint returns ok`() = testApplication {
        application {
            configureSerialization()
            routing { healthRoutes() }
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }
}
