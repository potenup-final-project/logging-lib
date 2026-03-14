package com.gop.logging.contract

interface StructuredLogger {
    fun debug(
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    )

    fun info(
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    )

    fun warn(
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    )

    fun error(
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    )
}
