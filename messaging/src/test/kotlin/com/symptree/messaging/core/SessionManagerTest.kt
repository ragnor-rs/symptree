package com.symptree.messaging.core

import com.symptree.messaging.port.Platform
import com.symptree.messaging.port.PlatformUser
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SessionManagerTest {
    private val tgUser = PlatformUser(Platform.TELEGRAM, "1")

    @Test
    fun `get returns same session for same user`() {
        val mgr = SessionManager(maxSessions = 10, sessionTimeout = Duration.ofHours(1))
        val s1 = mgr.get(tgUser)
        val s2 = mgr.get(tgUser)
        assertSame(s1, s2)
        assertEquals(1, mgr.size())
    }

    @Test
    fun `reset removes the session`() {
        val mgr = SessionManager(maxSessions = 10, sessionTimeout = Duration.ofHours(1))
        mgr.get(tgUser)
        mgr.reset(tgUser)
        assertEquals(0, mgr.size())
    }

    @Test
    fun `different platforms produce different sessions`() {
        val mgr = SessionManager(maxSessions = 10, sessionTimeout = Duration.ofHours(1))
        val tg = mgr.get(PlatformUser(Platform.TELEGRAM, "shared"))
        val wa = mgr.get(PlatformUser(Platform.WHATSAPP, "shared"))
        assertNotSame(tg, wa)
        assertEquals(2, mgr.size())
    }
}
