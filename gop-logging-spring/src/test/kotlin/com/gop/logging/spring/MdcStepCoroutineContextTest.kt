package com.gop.logging.spring

import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.core.StepContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.slf4j.MDC

class MdcStepCoroutineContextTest {

    @Test
    fun propagatesMdcAndStepIntoCoroutineBoundary() = runBlocking {
        MDC.clear()
        StepContext.clear()
        MDC.put(LogMdcKeys.TRACE_ID, "trace-coroutine")
        StepContext.set("settlement.retry.process")

        val context = MdcStepCoroutineContext.captureCurrent()
        MDC.put(LogMdcKeys.TRACE_ID, "trace-parent")
        StepContext.set("parent.step")

        val result = withContext(Dispatchers.Default + context) {
            MDC.get(LogMdcKeys.TRACE_ID) to StepContext.get()
        }

        assertEquals("settlement.retry.process", result.second)
        if (result.first != null) {
            assertEquals("trace-coroutine", result.first)
        }
        assertEquals("parent.step", StepContext.get())
        if (MDC.get(LogMdcKeys.TRACE_ID) != null) {
            assertEquals("trace-parent", MDC.get(LogMdcKeys.TRACE_ID))
        }

        MDC.clear()
        StepContext.clear()
    }

    @Test
    fun restoresOriginalThreadContextAfterCoroutineBlock() = runBlocking {
        MDC.clear()
        StepContext.clear()
        MDC.put(LogMdcKeys.TRACE_ID, "trace-before-capture")
        StepContext.set("before.capture.step")

        val context = MdcStepCoroutineContext.captureCurrent()

        MDC.put(LogMdcKeys.TRACE_ID, "trace-parent")
        StepContext.set("parent.step")

        withContext(Dispatchers.Default + context) {
            assertEquals("before.capture.step", StepContext.get())
            if (MDC.get(LogMdcKeys.TRACE_ID) != null) {
                assertEquals("trace-before-capture", MDC.get(LogMdcKeys.TRACE_ID))
            }
        }

        assertEquals("parent.step", StepContext.get())
        if (MDC.get(LogMdcKeys.TRACE_ID) != null) {
            assertEquals("trace-parent", MDC.get(LogMdcKeys.TRACE_ID))
        }

        MDC.clear()
        StepContext.clear()
    }
}
