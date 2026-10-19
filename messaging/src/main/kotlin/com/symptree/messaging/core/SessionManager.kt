package com.symptree.messaging.core

import com.symptree.messaging.port.Platform
import com.symptree.messaging.port.PlatformUser
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe session store keyed by (platform, userId). LRU eviction once maxSessions is hit.
 */
class SessionManager(
    private val maxSessions: Int,
    private val sessionTimeout: Duration,
) {
    private val sessions = ConcurrentHashMap<String, UserSession>()

    fun get(user: PlatformUser): UserSession {
        evictExpired()
        return sessions.compute(key(user.platform, user.id)) { _, existing ->
            existing?.also { it.lastActivity = Instant.now() }
                ?: UserSession(user)
        }!!.also { evictIfOverCapacity() }
    }

    fun reset(user: PlatformUser) {
        sessions.remove(key(user.platform, user.id))
    }

    fun size(): Int = sessions.size

    private fun key(platform: Platform, id: String): String = "${platform.name}:$id"

    private fun evictExpired() {
        val cutoff = Instant.now().minus(sessionTimeout)
        sessions.entries.removeIf { it.value.lastActivity.isBefore(cutoff) }
    }

    private fun evictIfOverCapacity() {
        if (sessions.size <= maxSessions) return
        val toRemove = sessions.size - maxSessions
        sessions.entries
            .sortedBy { it.value.lastActivity }
            .take(toRemove)
            .forEach { sessions.remove(it.key) }
    }
}
