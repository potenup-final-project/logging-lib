package com.gop.logging.contract

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class TechnicalMonitored(
    val thresholdMs: Long = 500,
    val step: String = "technical.monitor"
)
