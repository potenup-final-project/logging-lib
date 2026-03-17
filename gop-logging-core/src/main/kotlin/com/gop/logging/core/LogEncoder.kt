package com.gop.logging.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.gop.logging.contract.LogEnvelope

interface LogEncoder {
    fun encode(envelope: LogEnvelope): String
}

class JsonLogEncoder(
    private val mapper: ObjectMapper,
    private val sizePolicy: LogSizePolicy,
    private val errorExtractor: ErrorExtractor
) : LogEncoder {
    override fun encode(envelope: LogEnvelope): String {
        val maxBytes = sizePolicy.maxBytes(envelope.error != null)

        val original = mapper.writeValueAsString(envelope)
        if (original.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return original
        }

        val withError = envelope.error
        val stackTrace = withError?.stackTrace
        if (withError != null && stackTrace != null) {
            val lines = stackTrace.lineSequence().count()
            val checkpoints = listOf(12, 8, 4, 2, 1).filter { it < lines }
            for (limit in checkpoints) {
                val trimmed = envelope.copy(error = errorExtractor.trimStack(withError, limit))
                val trimmedJson = mapper.writeValueAsString(trimmed)
                if (trimmedJson.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                    return trimmedJson
                }
            }

            val noStack = envelope.copy(error = errorExtractor.removeStack(withError))
            val noStackJson = mapper.writeValueAsString(noStack)
            if (noStackJson.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                return noStackJson
            }
        }

        val fallback = envelope.copy(
            payloadTruncated = true,
            payload = mapOf(
                "reason" to "line_size_exceeded",
                "maxBytes" to maxBytes
            )
        )
        val fallbackJson = mapper.writeValueAsString(fallback)
        if (fallbackJson.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return fallbackJson
        }

        return "{\"timestamp\":\"${envelope.timestamp}\",\"level\":\"${envelope.level}\",\"service\":\"${envelope.service}\",\"logType\":\"${envelope.logType.name}\",\"step\":\"${envelope.step}\",\"result\":\"${envelope.result.name}\",\"payload\":{\"reason\":\"line_size_exceeded\",\"fallback\":true}}"
    }
}
