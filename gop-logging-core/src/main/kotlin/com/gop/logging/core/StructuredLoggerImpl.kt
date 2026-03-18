package com.gop.logging.core

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.gop.logging.contract.LogEnvelope
import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogStep
import com.gop.logging.contract.LogType
import com.gop.logging.contract.ProcessResult
import com.gop.logging.contract.StructuredLogger
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import  kotlin.text.toLongOrNull as kToLongOrNull

class StructuredLoggerImpl(
    private val serviceName: String,
    private val sanitizer: LogSanitizer = LogSanitizer(),
    private val rateLimiter: LogRateLimiter = LogRateLimiter.fromEnvironment(),
    private val minimumLevel: String = System.getenv("LOG_MIN_LEVEL") ?: "DEBUG",
    private val emitter: LogEmitter = Slf4jLogEmitter(LoggerFactory.getLogger(StructuredLoggerImpl::class.java)),
    private val errorExtractor: ErrorExtractor = ErrorExtractor(),
    private val sizePolicy: LogSizePolicy = LogSizePolicy(),
    private val encoder: LogEncoder = JsonLogEncoder(defaultObjectMapper(), sizePolicy, errorExtractor)
) : StructuredLogger {

    private val mapper: ObjectMapper = defaultObjectMapper()

    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
    }

    override fun debug(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        write("DEBUG", logType, result, payload, error)
    }

    override fun info(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        write("INFO", logType, result, payload, error)
    }

    override fun warn(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        write("WARN", logType, result, payload, error)
    }

    override fun error(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        write("ERROR", logType, result, payload, error)
    }

    private fun write(
        level: String,
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?>,
        throwable: Throwable?
    ) {
        if (!isEnabled(level)) {
            return
        }

        val step = StepContext.get() ?: LogStep.UNKNOWN
        val rateResult = rateLimiter.acquire(step, level)
        if (!rateResult.allowed) {
            return
        }

        val safePayload = try {
            sanitizer.sanitize(payload)
        } catch (_: Exception) {
            SanitizedPayload(payload = mapOf("payload" to "[MASKING_ERROR]"), payloadTruncated = false)
        }

        // normalizePayload only adds fixed internal markers for contract handling.
        val normalized = normalizePayload(result, safePayload.payload)
        val payloadWithoutDuration = normalized.payload.filterKeys { it != "durationMs" }

        val envelope = LogEnvelope(
            timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            level = level,
            service = serviceName,
            logType = logType,
            step = step,
            result = result,
            traceId = MDC.get(LogMdcKeys.TRACE_ID),
            orderFlowId = MDC.get(LogMdcKeys.ORDER_FLOW_ID),
            eventId = MDC.get(LogMdcKeys.EVENT_ID),
            messageId = MDC.get(LogMdcKeys.MESSAGE_ID),
            deliveryId = MDC.get(LogMdcKeys.DELIVERY_ID)?.toLongOrNull(),
            durationMs = normalized.payload["durationMs"].toLongOrNull(),
            payloadTruncated = safePayload.payloadTruncated.takeIf { it },
            suppressed = rateResult.suppressed.takeIf { it > 0 },
            payload = payloadWithoutDuration,
            error = throwable?.let { errorExtractor.extract(it) }
        )

        try {
            emit(level, ensureLineSize(envelope))
            normalized.warning?.let { emit("WARN", it) }
        } catch (ex: Exception) {
            emit("ERROR", fallbackJson(step, ex))
        }
    }

    private fun emit(level: String, json: String) {
        when (level) {
            "DEBUG" -> emitter.debug(json)
            "INFO" -> emitter.info(json)
            "WARN" -> emitter.warn(json)
            else -> emitter.error(json)
        }
    }

    private fun ensureLineSize(envelope: LogEnvelope): String {
        return encoder.encode(envelope)
    }

    private fun fallbackJson(step: String, ex: Exception): String {
        return try {
            mapper.writeValueAsString(
                mapOf(
                    "timestamp" to OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "level" to "ERROR",
                    "service" to serviceName,
                    "logType" to LogType.TECHNICAL.name,
                    "step" to LogStep.LOGGING_SERIALIZE,
                    "result" to LogResult.FAIL.name,
                    "traceId" to MDC.get(LogMdcKeys.TRACE_ID),
                    "payload" to mapOf(
                        "reason" to "serialization_failure",
                        "originStep" to step
                    ),
                    "error" to mapOf(
                        "type" to ex::class.java.simpleName,
                        "message" to ex.message?.take(500)
                    )
                )
            )
        } catch (_: Exception) {
            "{\"timestamp\":\"${OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}\",\"level\":\"ERROR\",\"service\":\"$serviceName\",\"logType\":\"TECHNICAL\",\"step\":\"${LogStep.LOGGING_SERIALIZE}\",\"result\":\"FAIL\",\"payload\":{\"reason\":\"serialization_failure\",\"fallback\":true}}"
        }
    }

    private fun normalizePayload(result: LogResult, payload: Map<String, Any?>): NormalizedPayload {
        if (result != LogResult.END) {
            return NormalizedPayload(payload = payload)
        }

        val mutable = payload.toMutableMap()
        val processResult = mutable["processResult"]?.toString()

        if (processResult == null) {
            mutable["processResult"] = ProcessResult.FAIL.name
            mutable["contractViolation"] = "missing_process_result"
            return NormalizedPayload(
                payload = mutable,
                warning = contractViolationWarning("missing_process_result")
            )
        }

        return try {
            ProcessResult.valueOf(processResult)
            NormalizedPayload(payload = mutable)
        } catch (_: IllegalArgumentException) {
            mutable["processResult"] = ProcessResult.FAIL.name
            mutable["contractViolation"] = "invalid_process_result"
            NormalizedPayload(
                payload = mutable,
                warning = contractViolationWarning("invalid_process_result")
            )
        }
    }

    private fun contractViolationWarning(reason: String): String {
        return runCatching {
            mapper.writeValueAsString(
                mapOf(
                    "timestamp" to OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "level" to "WARN",
                    "service" to serviceName,
                    "logType" to LogType.TECHNICAL.name,
                    "step" to LogStep.LOGGING_CONTRACT_VIOLATION,
                    "result" to LogResult.SKIP.name,
                    "traceId" to MDC.get(LogMdcKeys.TRACE_ID),
                    "payload" to mapOf("reason" to reason)
                )
            )
        }.getOrDefault(
            "{\"timestamp\":\"${OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}\",\"level\":\"WARN\",\"service\":\"$serviceName\",\"logType\":\"TECHNICAL\",\"step\":\"${LogStep.LOGGING_CONTRACT_VIOLATION}\",\"result\":\"SKIP\",\"payload\":{\"reason\":\"$reason\"}}"
        )
    }

    private fun Any?.toLongOrNull(): Long? {
        return when (this) {
            null -> null
            is Long -> this
            is Int -> this.toLong()
            is Short -> this.toLong()
            is Byte -> this.toLong()
            is String -> this.trim().kToLongOrNull()
            else -> this.toString().trim().kToLongOrNull()
        }
    }

    companion object {
        private fun defaultObjectMapper(): ObjectMapper {
            return ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        }
    }

    private fun isEnabled(level: String): Boolean {
        val configured = LogLevel.parse(minimumLevel)
        val requested = LogLevel.parse(level)
        return requested.priority >= configured.priority
    }
}

private enum class LogLevel(val priority: Int) {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40);

    companion object {
        fun parse(value: String): LogLevel {
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: DEBUG
        }
    }
}

data class RateAcquireResult(
    val allowed: Boolean,
    val suppressed: Int
)

class LogRateLimiter(private val limitPerSecond: Int) {
    private val windows = ConcurrentHashMap<String, AtomicReference<RateWindow>>()

    fun acquire(step: String, level: String): RateAcquireResult {
        val key = "$step|$level"
        if (!windows.containsKey(key) && windows.size >= MAX_WINDOW_ENTRIES) {
            return RateAcquireResult(allowed = true, suppressed = 0)
        }
        val nowSecond = System.currentTimeMillis() / 1000
        val stateRef = windows.computeIfAbsent(key) { AtomicReference(RateWindow(epochSecond = nowSecond, count = 0, suppressed = 0)) }

        while (true) {
            val current = stateRef.get()

            if (current.epochSecond != nowSecond) {
                val reset = RateWindow(epochSecond = nowSecond, count = 1, suppressed = 0)
                if (stateRef.compareAndSet(current, reset)) {
                    return RateAcquireResult(allowed = true, suppressed = current.suppressed)
                }
                continue
            }

            if (current.count < limitPerSecond) {
                val next = current.copy(count = current.count + 1)
                if (stateRef.compareAndSet(current, next)) {
                    return RateAcquireResult(allowed = true, suppressed = 0)
                }
                continue
            }

            val suppressed = current.copy(suppressed = current.suppressed + 1)
            if (stateRef.compareAndSet(current, suppressed)) {
                return RateAcquireResult(allowed = false, suppressed = 0)
            }
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 100
        private const val MAX_WINDOW_ENTRIES = 1000

        fun fromEnvironment(): LogRateLimiter {
            val configured = System.getenv("LOG_RATE_LIMIT_PER_SECOND")?.toIntOrNull()
            val limit = configured?.takeIf { it > 0 } ?: DEFAULT_LIMIT
            return LogRateLimiter(limit)
        }
    }
}

data class RateWindow(
    val epochSecond: Long,
    val count: Int,
    val suppressed: Int
)

data class NormalizedPayload(
    val payload: Map<String, Any?>,
    val warning: String? = null
)

interface LogEmitter {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}

class Slf4jLogEmitter(private val logger: Logger) : LogEmitter {
    override fun debug(message: String) {
        logger.debug(message)
    }

    override fun info(message: String) {
        logger.info(message)
    }

    override fun warn(message: String) {
        logger.warn(message)
    }

    override fun error(message: String) {
        logger.error(message)
    }
}

class StdoutJsonLogEmitter : LogEmitter {
    override fun debug(message: String) {
        System.out.println(message)
    }

    override fun info(message: String) {
        System.out.println(message)
    }

    override fun warn(message: String) {
        System.out.println(message)
    }

    override fun error(message: String) {
        System.err.println(message)
    }
}
