package com.gop.logging.spring

import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogStep
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StructuredLogger
import com.gop.logging.core.StepContext
import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

class MdcStepTaskDecorator(
    private val structuredLogger: StructuredLogger,
    private val executorName: String = "applicationTaskExecutor"
) : TaskDecorator {

    override fun decorate(runnable: Runnable): Runnable {
        val contextMap = MDC.getCopyOfContextMap()
        val stepSnapshot = StepContext.get()
        val mdcStepSnapshot = contextMap?.get(LogMdcKeys.STEP)
        val shouldWarnMissingStep = stepSnapshot == null && hasDistributedTraceContext(contextMap)

        return Runnable {
            val previousContext = MDC.getCopyOfContextMap()
            val previousStep = StepContext.get()
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap)
                } else {
                    MDC.clear()
                }

                if (stepSnapshot != null) {
                    StepContext.set(stepSnapshot)
                } else if (!mdcStepSnapshot.isNullOrBlank()) {
                    StepContext.set(mdcStepSnapshot)
                } else {
                    if (shouldWarnMissingStep) {
                        StepContext.scoped(LogStep.LOGGING_CONTEXT_MISSING) {
                            structuredLogger.warn(
                                logType = LogType.TECHNICAL,
                                result = LogResult.SKIP,
                                payload = mapOf(
                                    "reason" to "step context missing on async propagation",
                                    "executorName" to executorName,
                                    "threadName" to Thread.currentThread().name
                                )
                            )
                        }
                    } else {
                        StepContext.clear()
                    }
                }

                runnable.run()
            } catch (ex: Exception) {
                StepContext.scoped(LogStep.LOGGING_CONTEXT_MISSING) {
                    structuredLogger.warn(
                        logType = LogType.TECHNICAL,
                        result = LogResult.SKIP,
                        payload = mapOf(
                            "reason" to "async context propagation failure",
                            "executorName" to executorName,
                            "threadName" to Thread.currentThread().name
                        ),
                        error = ex
                    )
                }
                throw ex
            } catch (err: Error) {
                throw err
            } finally {
                if (previousStep == null) {
                    StepContext.clear()
                } else {
                    StepContext.set(previousStep)
                }

                if (previousContext == null) {
                    MDC.clear()
                } else {
                    MDC.setContextMap(previousContext)
                }
            }
        }
    }

    private fun hasDistributedTraceContext(contextMap: Map<String, String>?): Boolean {
        if (contextMap.isNullOrEmpty()) {
            return false
        }

        return listOf(
            LogMdcKeys.TRACE_ID,
            LogMdcKeys.ORDER_FLOW_ID,
            LogMdcKeys.EVENT_ID,
            LogMdcKeys.MESSAGE_ID,
            LogMdcKeys.DELIVERY_ID
        ).any { key -> !contextMap[key].isNullOrBlank() }
    }
}
