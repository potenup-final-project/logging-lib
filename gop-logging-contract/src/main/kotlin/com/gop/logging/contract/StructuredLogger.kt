package com.gop.logging.contract

interface StructuredLogger {
    fun debug(message: String, vararg args: Any?) {
        val (rendered, throwable) = renderTemplate(message, args)
        debug(
            logType = LogType.TECHNICAL,
            result = LogResult.SUCCESS,
            payload = mapOf("legacyMessage" to rendered),
            error = throwable
        )
    }

    fun debug(
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    )

    fun info(message: String, vararg args: Any?) {
        val (rendered, throwable) = renderTemplate(message, args)
        info(
            logType = LogType.TECHNICAL,
            result = LogResult.SUCCESS,
            payload = mapOf("legacyMessage" to rendered),
            error = throwable
        )
    }

    fun info(
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    )

    fun warn(message: String, vararg args: Any?) {
        val (rendered, throwable) = renderTemplate(message, args)
        warn(
            logType = LogType.TECHNICAL,
            result = LogResult.RETRY,
            payload = mapOf("legacyMessage" to rendered),
            error = throwable
        )
    }

    fun warn(
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    )

    fun error(message: String, vararg args: Any?) {
        val (rendered, throwable) = renderTemplate(message, args)
        error(
            logType = LogType.TECHNICAL,
            result = LogResult.FAIL,
            payload = mapOf("legacyMessage" to rendered),
            error = throwable
        )
    }

    fun error(
        logType: LogType,
        result: LogResult,
        payload: Map<String, Any?> = emptyMap(),
        error: Throwable? = null
    )

    private fun renderTemplate(message: String, args: Array<out Any?>): Pair<String, Throwable?> {
        val throwable = args.lastOrNull() as? Throwable
        val values = if (throwable == null) args.toList() else args.dropLast(1)
        if (values.isEmpty()) {
            return message to throwable
        }
        val out = StringBuilder(message.length + values.size * 8)
        var cursor = 0
        var index = 0
        while (true) {
            val token = message.indexOf("{}", cursor)
            if (token < 0) {
                out.append(message, cursor, message.length)
                break
            }
            out.append(message, cursor, token)
            if (index < values.size) {
                out.append(values[index++].toPrintable())
            } else {
                out.append("{}")
            }
            cursor = token + 2
        }
        if (index < values.size) {
            out.append(" [")
            values.subList(index, values.size).joinTo(out, separator = ",") { it.toPrintable() }
            out.append(']')
        }
        return out.toString() to throwable
    }

    private fun Any?.toPrintable(): String {
        return when (this) {
            null -> "null"
            is BooleanArray -> contentToString()
            is ByteArray -> contentToString()
            is CharArray -> contentToString()
            is DoubleArray -> contentToString()
            is FloatArray -> contentToString()
            is IntArray -> contentToString()
            is LongArray -> contentToString()
            is ShortArray -> contentToString()
            is Array<*> -> contentDeepToString()
            else -> toString()
        }
    }
}
