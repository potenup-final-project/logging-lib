package com.gop.logging.spring

import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StructuredLogger
import com.gop.logging.core.StepContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import kotlin.test.assertFailsWith

class MdcStepTaskDecoratorTest {

    @Test
    fun propagatesMdcAndStepContext() {
        val logger = DecoratorCapturingStructuredLogger()
        val decorator = MdcStepTaskDecorator(logger)
        MDC.put(LogMdcKeys.TRACE_ID, "trace-test")
        StepContext.set("settlement.retry.process")

        val captured = mutableMapOf<String, String?>()
        val runnable = decorator.decorate {
            captured["traceId"] = MDC.get(LogMdcKeys.TRACE_ID)
            captured["step"] = StepContext.get()
        }

        runnable.run()

        assertEquals("settlement.retry.process", captured["step"])
        if (captured["traceId"] != null) {
            assertEquals("trace-test", captured["traceId"])
        }
        assertTrue(logger.logs.isEmpty())
        MDC.clear()
        StepContext.clear()
    }

    @Test
    fun doesNotWarnWhenStepContextMissingWithoutDistributedContext() {
        val logger = DecoratorCapturingStructuredLogger()
        val decorator = MdcStepTaskDecorator(logger)
        MDC.clear()
        StepContext.clear()

        decorator.decorate { }.run()

        assertTrue(logger.logs.isEmpty())
    }

    @Test
    fun logsWarningAndRethrowsWhenRunnableFails() {
        val logger = DecoratorCapturingStructuredLogger()
        val decorator = MdcStepTaskDecorator(logger)
        MDC.clear()
        StepContext.clear()

        assertFailsWith<IllegalStateException> {
            decorator.decorate {
                throw IllegalStateException("boom")
            }.run()
        }

        assertTrue(logger.logs.size >= 1)
        val last = logger.logs.last()
        assertEquals(LogType.TECHNICAL, last.logType)
        assertEquals(LogResult.SKIP, last.result)
    }
}

private class DecoratorCapturingStructuredLogger : StructuredLogger {
    val logs = mutableListOf<DecoratorLogCall>()

    override fun debug(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += DecoratorLogCall("DEBUG", logType, result, payload, error)
    }

    override fun info(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += DecoratorLogCall("INFO", logType, result, payload, error)
    }

    override fun warn(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += DecoratorLogCall("WARN", logType, result, payload, error)
    }

    override fun error(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += DecoratorLogCall("ERROR", logType, result, payload, error)
    }
}

private data class DecoratorLogCall(
    val level: String,
    val logType: LogType,
    val result: LogResult,
    val payload: Map<String, Any?>,
    val error: Throwable?
)
