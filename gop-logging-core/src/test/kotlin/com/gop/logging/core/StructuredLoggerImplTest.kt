package com.gop.logging.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.gop.logging.contract.CodedError
import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogType
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredLoggerImplTest {

    private val mapper = ObjectMapper()

    @Test
    fun endWithoutProcessResultDoesNotBreakFlow() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)

        logger.info(
            logType = LogType.INTEGRATION,
            result = LogResult.END,
            payload = mapOf("messageId" to "m-1")
        )

        assertEquals(2, emitter.entries.size)
        assertEquals("INFO", emitter.entries[0].level)
        assertEquals("WARN", emitter.entries[1].level)
        val json = mapper.readTree(emitter.entries[0].message)
        assertEquals("FAIL", json["payload"]["processResult"].asText())
    }

    @Test
    fun endWithInvalidProcessResultIsNormalized() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)

        logger.info(
            logType = LogType.INTEGRATION,
            result = LogResult.END,
            payload = mapOf("processResult" to "BROKEN")
        )

        assertEquals(2, emitter.entries.size)
        val json = mapper.readTree(emitter.entries.first().message)
        assertEquals("FAIL", json["payload"]["processResult"].asText())
        assertEquals("invalid_process_result", json["payload"]["contractViolation"].asText())
    }

    @Test
    fun endAcceptsValidProcessResult() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)

        logger.info(
            logType = LogType.INTEGRATION,
            result = LogResult.END,
            payload = mapOf("processResult" to "SUCCESS")
        )

        assertEquals(1, emitter.entries.size)
        val json = mapper.readTree(emitter.entries.first().message)
        assertEquals("END", json["result"].asText())
        assertEquals("SUCCESS", json["payload"]["processResult"].asText())
    }

    @Test
    fun writesStructuredJsonWithMdcFields() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "worker", emitter = emitter)

        MDC.put(LogMdcKeys.TRACE_ID, "trace-123")
        MDC.put(LogMdcKeys.ORDER_FLOW_ID, "order-flow-1")
        try {
            StepContext.scoped("webhook.delivery.send") {
                logger.info(
                    logType = LogType.INTEGRATION,
                    result = LogResult.SUCCESS,
                    payload = mapOf("endpointId" to 10)
                )
            }
        } finally {
            MDC.clear()
            StepContext.clear()
        }

        val json = mapper.readTree(emitter.entries.first().message)
        assertEquals("worker", json["service"].asText())
        assertEquals("webhook.delivery.send", json["step"].asText())
        if (json.has("traceId")) {
            assertEquals("trace-123", json["traceId"].asText())
        }
        if (json.has("orderFlowId")) {
            assertEquals("order-flow-1", json["orderFlowId"].asText())
        }
    }

    @Test
    fun truncatesOversizedLogLine() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)
        val huge = "x".repeat(9000)

        logger.info(
            logType = LogType.FLOW,
            result = LogResult.SUCCESS,
            payload = mapOf("detail" to huge)
        )

        val out = emitter.entries.first().message
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 8192)
        val json = mapper.readTree(out)
        assertNotNull(json["payload"])
    }

    @Test
    fun extractsCodedErrorWhenAvailable() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)

        logger.error(
            logType = LogType.FLOW,
            result = LogResult.FAIL,
            error = PaymentFailure("boom")
        )

        val json = mapper.readTree(emitter.entries.first().message)
        assertEquals("PAY-0001", json["error"]["code"].asText())
    }

    @Test
    fun capturesRootCauseAndRootBasedCauseChain() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)

        val root = IllegalArgumentException("root token=very-secret")
        val wrapped1 = IllegalStateException("middle", root)
        val wrapped2 = RuntimeException("top", wrapped1)

        logger.error(
            logType = LogType.FLOW,
            result = LogResult.FAIL,
            error = wrapped2
        )

        val json = mapper.readTree(emitter.entries.first().message)
        val error = json["error"]
        assertEquals("IllegalArgumentException", error["rootCauseType"].asText())
        assertTrue(error["rootCauseMessage"].asText().contains("***"))
        assertEquals(3, error["causeChain"].size())
        assertEquals("IllegalArgumentException", error["causeChain"][0]["type"].asText())
        assertEquals("IllegalStateException", error["causeChain"][1]["type"].asText())
        assertEquals("RuntimeException", error["causeChain"][2]["type"].asText())
        assertNotNull(error["stackHash"])
    }

    @Test
    fun stripsStackTraceWhenLineStillTooLarge() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)
        val hugeMessage = "x".repeat(15000)

        logger.error(
            logType = LogType.FLOW,
            result = LogResult.FAIL,
            error = IllegalStateException(hugeMessage)
        )

        val out = emitter.entries.first().message
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 12288)
        val json = mapper.readTree(out)
        val error = json["error"]
        if (error != null && !error.isNull) {
            assertNotNull(error["stackHash"])
            val stackTrace = error.get("stackTrace")
            assertTrue(stackTrace == null || stackTrace.isNull || stackTrace.asText().isNotBlank())
        }
    }

    @Test
    fun nonErrorLogKeepsEightKilobyteBoundary() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)
        val huge = "x".repeat(16000)

        logger.info(
            logType = LogType.FLOW,
            result = LogResult.SUCCESS,
            payload = mapOf("detail" to huge)
        )

        val out = emitter.entries.first().message
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 8192)
        val json = mapper.readTree(out)
        assertNull(json["error"])
    }

    @Test
    fun liftsDurationMsToTopLevelOnly() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(serviceName = "pg_core", emitter = emitter)

        logger.info(
            logType = LogType.FLOW,
            result = LogResult.SUCCESS,
            payload = mapOf("durationMs" to 120L, "txId" to 5)
        )

        val json = mapper.readTree(emitter.entries.first().message)
        assertEquals(120L, json["durationMs"].asLong())
        assertTrue(!json["payload"].has("durationMs"))
    }

    @Test
    fun rateLimiterSuppressesOverLimitLogs() {
        val emitter = TestEmitter()
        val logger = StructuredLoggerImpl(
            serviceName = "pg_core",
            emitter = emitter,
            rateLimiter = LogRateLimiter(1)
        )

        StepContext.scoped("batch.netcancel.process") {
            logger.info(LogType.FLOW, LogResult.SUCCESS, mapOf("index" to 1))
            logger.info(LogType.FLOW, LogResult.SUCCESS, mapOf("index" to 2))
        }

        assertEquals(1, emitter.entries.size)
    }
}

private class TestEmitter : LogEmitter {
    val entries = mutableListOf<LogEntry>()

    override fun debug(message: String) {
        entries += LogEntry("DEBUG", message)
    }

    override fun info(message: String) {
        entries += LogEntry("INFO", message)
    }

    override fun warn(message: String) {
        entries += LogEntry("WARN", message)
    }

    override fun error(message: String) {
        entries += LogEntry("ERROR", message)
    }
}

private data class LogEntry(
    val level: String,
    val message: String
)

private class PaymentFailure(message: String) : RuntimeException(message), CodedError {
    override val errorCode: String = "PAY-0001"
}
