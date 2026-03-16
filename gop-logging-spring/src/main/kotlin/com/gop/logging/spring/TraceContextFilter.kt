package com.gop.logging.spring

import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogStep
import com.gop.logging.contract.LogType
import com.gop.logging.contract.ProcessResult
import com.gop.logging.contract.StructuredLogger
import com.gop.logging.core.StepContext
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

class TraceContextFilter(
    private val structuredLogger: StructuredLogger,
    private val excludedPathPrefixes: Set<String> = DEFAULT_EXCLUDED_PATH_PREFIXES
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI ?: return false
        return excludedPathPrefixes.any { path.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val previousMdc = MDC.getCopyOfContextMap()
        val previousStep = StepContext.get()
        val startedAt = System.currentTimeMillis()
        val traceId = request.getHeader(HEADER_TRACE_ID)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val orderFlowId = request.getHeader(HEADER_ORDER_FLOW_ID)?.takeIf { it.isNotBlank() }

        MDC.put(LogMdcKeys.TRACE_ID, traceId)
        orderFlowId?.let { MDC.put(LogMdcKeys.ORDER_FLOW_ID, it) }
        response.setHeader(HEADER_TRACE_ID, traceId)

        StepContext.scoped(LogStep.HTTP_REQUEST_START) {
            MDC.put(LogMdcKeys.STEP, LogStep.HTTP_REQUEST_START)
            structuredLogger.info(
                logType = LogType.HTTP,
                result = LogResult.START,
                payload = mapOf(
                    "requestUri" to request.requestURI,
                    "httpMethod" to request.method
                )
            )
        }

        var thrown: Throwable? = null
        try {
            filterChain.doFilter(request, response)
        } catch (ex: Throwable) {
            thrown = ex
            throw ex
        } finally {
            val elapsed = System.currentTimeMillis() - startedAt
            val effectiveStatus = if (thrown != null && !response.isCommitted) 500 else response.status
            val processResult = if (thrown != null || effectiveStatus >= 400) {
                ProcessResult.FAIL.name
            } else {
                ProcessResult.SUCCESS.name
            }

            StepContext.scoped(LogStep.HTTP_REQUEST_END) {
                MDC.put(LogMdcKeys.STEP, LogStep.HTTP_REQUEST_END)
                val endPayload = mapOf(
                    "requestUri" to request.requestURI,
                    "httpMethod" to request.method,
                    "httpStatus" to effectiveStatus,
                    "durationMs" to elapsed,
                    "processResult" to processResult
                )

                when {
                    thrown != null || effectiveStatus >= 500 || effectiveStatus == 401 || effectiveStatus == 403 -> {
                        structuredLogger.error(
                            logType = LogType.HTTP,
                            result = LogResult.END,
                            payload = endPayload,
                            error = thrown
                        )
                    }

                    effectiveStatus >= 400 -> {
                        structuredLogger.warn(
                            logType = LogType.HTTP,
                            result = LogResult.END,
                            payload = endPayload
                        )
                    }

                    else -> {
                        structuredLogger.info(
                            logType = LogType.HTTP,
                            result = LogResult.END,
                            payload = endPayload
                        )
                    }
                }
            }
            if (previousMdc == null) {
                MDC.clear()
            } else {
                MDC.setContextMap(previousMdc)
            }
            if (previousStep == null) {
                StepContext.clear()
            } else {
                StepContext.set(previousStep)
            }
        }
    }

    companion object {
        private const val HEADER_TRACE_ID = "X-Trace-Id"
        private const val HEADER_ORDER_FLOW_ID = "X-Order-Flow-Id"
        private val DEFAULT_EXCLUDED_PATH_PREFIXES = setOf(
            "/actuator",
            "/swagger-ui",
            "/v3/api-docs"
        )
    }
}
