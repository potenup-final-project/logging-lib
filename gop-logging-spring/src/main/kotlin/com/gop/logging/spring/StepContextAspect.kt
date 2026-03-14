package com.gop.logging.spring

import com.gop.logging.contract.LogSuffix
import com.gop.logging.contract.LogMdcKeys
import com.gop.logging.core.StepContext
import com.gop.logging.core.StepResolver
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.slf4j.MDC
import org.springframework.aop.support.AopUtils

@Aspect
class StepContextAspect(
    private val stepResolver: StepResolver
) {
    @Around("@annotation(logSuffix)")
    fun around(joinPoint: ProceedingJoinPoint, logSuffix: LogSuffix): Any? {
        val targetClass = joinPoint.target?.let { AopUtils.getTargetClass(it) } ?: joinPoint.signature.declaringType
        val step = stepResolver.resolve(targetClass, logSuffix.suffix)
        val previousMdcStep = MDC.get(LogMdcKeys.STEP)
        return StepContext.scoped(step) {
            MDC.put(LogMdcKeys.STEP, step)
            try {
                joinPoint.proceed()
            } finally {
                if (previousMdcStep == null) {
                    MDC.remove(LogMdcKeys.STEP)
                } else {
                    MDC.put(LogMdcKeys.STEP, previousMdcStep)
                }
            }
        }
    }
}
