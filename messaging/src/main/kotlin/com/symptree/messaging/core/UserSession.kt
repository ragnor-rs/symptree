package com.symptree.messaging.core

import com.symptree.messaging.port.PlatformUser
import java.time.Instant

data class UserSession(
    val user: PlatformUser,
    var state: State = State.IDLE,
    var lastActivity: Instant = Instant.now(),
)
