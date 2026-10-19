package com.symptree.messaging.port

data class IncomingMessage(
    val user: PlatformUser,
    val text: String?,
    val photoBytes: ByteArray? = null,
    val callbackData: String? = null,
)
