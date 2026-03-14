package com.gop.logging.contract

data class LogEnvelope(
    val timestamp: String,
    val level: String,
    val service: String,
    val logType: LogType,
    val step: String,
    val result: LogResult,
    val traceId: String?,
    val orderFlowId: String? = null,
    val eventId: String? = null,
    val messageId: String? = null,
    val deliveryId: Long? = null,
    val durationMs: Long? = null,
    val payloadTruncated: Boolean? = null,
    val suppressed: Int? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val error: LogError? = null
)

data class LogError(
    val type: String,
    val code: String? = null,
    val message: String? = null
)
