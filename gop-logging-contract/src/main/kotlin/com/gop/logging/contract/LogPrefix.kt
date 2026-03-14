package com.gop.logging.contract

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class LogPrefix(val value: String)
