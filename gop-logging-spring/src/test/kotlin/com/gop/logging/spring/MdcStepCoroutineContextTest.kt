package com.gop.logging.spring

import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.core.StepContext
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
        val result = withContext(context) {
            MDC.get(LogMdcKeys.TRACE_ID) to StepContext.get()
        }

        assertEquals("settlement.retry.process", result.second)
        if (result.first != null) {
            assertEquals("trace-coroutine", result.first)
        }

        MDC.clear()
        StepContext.clear()
    }
}
