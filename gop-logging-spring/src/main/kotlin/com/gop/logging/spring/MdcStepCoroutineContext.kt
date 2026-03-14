package com.gop.logging.spring

import com.gop.logging.core.StepContext
import kotlinx.coroutines.ThreadContextElement
import org.slf4j.MDC
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class MdcStepCoroutineContext(
    private val mdcSnapshot: Map<String, String>?,
    private val stepSnapshot: String?
) : ThreadContextElement<MdcStepCoroutineContext.State>,
    AbstractCoroutineContextElement(Key) {

    data class State(
        val mdc: Map<String, String>?,
        val step: String?
    )

    override fun updateThreadContext(context: CoroutineContext): State {
        val previous = State(MDC.getCopyOfContextMap(), StepContext.get())
        if (mdcSnapshot == null) {
            MDC.clear()
        } else {
            MDC.setContextMap(mdcSnapshot)
        }

        if (stepSnapshot == null) {
            StepContext.clear()
        } else {
            StepContext.set(stepSnapshot)
        }

        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: State) {
        if (oldState.mdc == null) {
            MDC.clear()
        } else {
            MDC.setContextMap(oldState.mdc)
        }

        if (oldState.step == null) {
            StepContext.clear()
        } else {
            StepContext.set(oldState.step)
        }
    }

    companion object Key : CoroutineContext.Key<MdcStepCoroutineContext> {
        fun captureCurrent(): MdcStepCoroutineContext {
            val mdc = MDC.getCopyOfContextMap()
            val step = StepContext.get()
            return MdcStepCoroutineContext(mdc, step)
        }
    }
}
