package com.gop.logging.spring

import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StructuredLogger
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertFailsWith

class TraceContextFilterTest {

    @Test
    fun writesHttpStartAndEndLogsAndSetsTraceHeader() {
        val logger = FilterCapturingStructuredLogger()
        val filter = TraceContextFilter(logger)

        val request = MockHttpServletRequest("GET", "/payments/confirm")
        request.addHeader("X-Trace-Id", "trace-incoming-1")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertEquals("trace-incoming-1", response.getHeader("X-Trace-Id"))
        assertEquals(2, logger.logs.size)
        assertEquals(LogType.HTTP, logger.logs[0].logType)
        assertEquals(LogResult.START, logger.logs[0].result)
        assertEquals(LogResult.END, logger.logs[1].result)
        assertNotNull(logger.logs[1].payload["processResult"])
    }

    @Test
    fun writesFailEndLogWhenChainThrows() {
        val logger = FilterCapturingStructuredLogger()
        val filter = TraceContextFilter(logger)
        val request = MockHttpServletRequest("GET", "/payments/confirm")
        val response = MockHttpServletResponse()
        val failingChain = FilterChain { _: ServletRequest, _: ServletResponse ->
            throw IllegalStateException("fail")
        }

        assertFailsWith<IllegalStateException> {
            filter.doFilter(request, response, failingChain)
        }

        val endLog = logger.logs.last()
        assertEquals(LogResult.END, endLog.result)
        assertEquals(500, endLog.payload["httpStatus"])
        assertEquals("FAIL", endLog.payload["processResult"])
        assertTrue(logger.logs.size >= 2)
    }
}

private class FilterCapturingStructuredLogger : StructuredLogger {
    val logs = mutableListOf<FilterLogCall>()

    override fun debug(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += FilterLogCall("DEBUG", logType, result, payload, error)
    }

    override fun info(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += FilterLogCall("INFO", logType, result, payload, error)
    }

    override fun warn(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += FilterLogCall("WARN", logType, result, payload, error)
    }

    override fun error(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += FilterLogCall("ERROR", logType, result, payload, error)
    }
}

private data class FilterLogCall(
    val level: String,
    val logType: LogType,
    val result: LogResult,
    val payload: Map<String, Any?>,
    val error: Throwable?
)
