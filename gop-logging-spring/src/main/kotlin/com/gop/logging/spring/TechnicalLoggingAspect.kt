package com.gop.logging.spring

import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogType
import com.gop.logging.contract.StructuredLogger
import com.gop.logging.contract.TechnicalMonitored
import com.gop.logging.core.StepContext
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect

@Aspect
class TechnicalLoggingAspect(
    private val structuredLogger: StructuredLogger
) {

    @Around("@annotation(monitored)")
    fun around(joinPoint: ProceedingJoinPoint, monitored: TechnicalMonitored): Any? {
        val start = System.currentTimeMillis()
        var failed = false
        return try {
            joinPoint.proceed()
        } catch (ex: Throwable) {
            failed = true
            throw ex
        } finally {
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > monitored.thresholdMs) {
                StepContext.scoped(monitored.step) {
                    structuredLogger.warn(
                        logType = LogType.TECHNICAL,
                        result = if (failed) LogResult.FAIL else LogResult.SUCCESS,
                        payload = mapOf(
                            "durationMs" to elapsed,
                            "thresholdMs" to monitored.thresholdMs,
                            "method" to joinPoint.signature.toShortString()
                        )
                    )
                }
            }
        }
    }
}
