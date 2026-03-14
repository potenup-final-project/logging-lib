package com.gop.logging.contract

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LogSuffix(val suffix: String)
