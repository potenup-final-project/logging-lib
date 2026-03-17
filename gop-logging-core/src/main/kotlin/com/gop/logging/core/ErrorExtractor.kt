package com.gop.logging.core

import com.gop.logging.contract.CodedError
import com.gop.logging.contract.LogCause
import com.gop.logging.contract.LogError
import java.security.MessageDigest

class ErrorExtractor(
    private val maskingPolicy: MaskingPolicy = DefaultMaskingPolicy(),
    private val maxCauseDepth: Int = 3,
    private val maxStackLines: Int = 20,
    private val maxMessageLength: Int = 500
) {
    fun extract(throwable: Throwable): LogError {
        val topChain = throwable.topCauseChain()
        val root = topChain.lastOrNull() ?: throwable
        val rootBasedChain = topChain.asReversed().take(maxCauseDepth).map { cause ->
            LogCause(
                type = cause::class.java.simpleName,
                message = cause.message
                    ?.let(maskingPolicy::maskText)
                    ?.let { maskingPolicy.truncate(it, maxMessageLength) }
            )
        }

        val stackLines = throwable.stackTraceToString().lineSequence().toList()
        val stackExcerptLines = stackLines.take(maxStackLines)
        val stackTruncated = stackLines.size > stackExcerptLines.size
        val stackExcerpt = if (stackExcerptLines.isEmpty()) {
            null
        } else {
            maskingPolicy.maskText(stackExcerptLines.joinToString("\n"))
        }

        val topMessage = throwable.message
            ?.let(maskingPolicy::maskText)
            ?.let { maskingPolicy.truncate(it, maxMessageLength) }

        val rootMessage = root.message
            ?.let(maskingPolicy::maskText)
            ?.let { maskingPolicy.truncate(it, maxMessageLength) }

        return LogError(
            type = throwable::class.java.simpleName,
            code = (throwable as? CodedError)?.errorCode,
            message = topMessage,
            rootCauseType = root::class.java.simpleName,
            rootCauseMessage = rootMessage,
            causeChain = rootBasedChain,
            stackTrace = stackExcerpt,
            stackHash = stackHash(rootBasedChain, stackExcerptLines),
            stackTruncated = stackTruncated.takeIf { it }
        )
    }

    fun trimStack(error: LogError, maxLines: Int): LogError {
        val current = error.stackTrace ?: return error
        val lines = current.lineSequence().toList()
        if (lines.size <= maxLines) {
            return error
        }
        return error.copy(
            stackTrace = lines.take(maxLines).joinToString("\n"),
            stackTruncated = true
        )
    }

    fun removeStack(error: LogError): LogError {
        if (error.stackTrace == null) {
            return error
        }
        return error.copy(stackTrace = null, stackTruncated = true)
    }

    private fun Throwable.topCauseChain(): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        val seen = mutableSetOf<Throwable>()
        var cursor: Throwable? = this
        while (cursor != null && seen.add(cursor)) {
            chain += cursor
            cursor = cursor.cause
        }
        return chain
    }

    private fun stackHash(causeChain: List<LogCause>, stackLines: List<String>): String {
        val chainKey = causeChain.joinToString("|") { "${it.type}:${it.message.orEmpty()}" }
        val stackKey = stackLines.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest((chainKey + "\n" + stackKey).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }
}
