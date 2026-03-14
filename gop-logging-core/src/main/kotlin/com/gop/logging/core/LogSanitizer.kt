package com.gop.logging.core

class LogSanitizer {
    private val sensitiveKeys = setOf(
        "password",
        "secret",
        "token",
        "authorization",
        "apikey",
        "api_key",
        "accesskey",
        "access_key",
        "webhooksecret",
        "clientsecret",
        "signature"
    )

    private val cardPattern = Regex("\\b(?:\\d[ -]*?){13,19}\\b")
    private val emailPattern = Regex("^([^@]+)@([^@]+)$")
    private val maxPayloadFields = 10
    private val maxListEntries = 5

    fun sanitize(payload: Map<String, Any?>): SanitizedPayload {
        val result = linkedMapOf<String, Any?>()
        var truncated = false
        payload.entries.take(maxPayloadFields).forEach { (key, value) ->
            val sanitized = sanitizeEntry(key, value)
            if (sanitized != null) {
                result[key] = sanitized
            }
        }

        if (payload.size > maxPayloadFields) {
            truncated = true
        }

        return SanitizedPayload(
            payload = result,
            payloadTruncated = truncated
        )
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is Map<*, *> -> sanitizeMap(value)
            is Array<*> -> sanitizeList(value.toList())
            is Set<*> -> sanitizeList(value.toList())
            is Iterable<*> -> sanitizeList(value.toList())
            is Number, is Boolean -> value
            is Enum<*> -> value.name
            is CharSequence -> sanitizeString(value.toString())
            else -> sanitizeString(value.toString())
        }
    }

    private fun sanitizeMap(value: Map<*, *>): Map<String, Any?> {
        return value.entries
            .mapNotNull { (rawKey, rawValue) ->
                val key = rawKey?.toString() ?: return@mapNotNull null
                val sanitized = sanitizeEntry(key, rawValue) ?: return@mapNotNull null
                key to sanitized
            }
            .toMap(linkedMapOf())
    }

    private fun sanitizeEntry(key: String, value: Any?): Any? {
        return if (isSensitiveKey(key)) {
            mask(value)
        } else {
            sanitizeValue(value)
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        val compact = normalized.replace("_", "").replace("-", "")
        return sensitiveKeys.any { candidate ->
            normalized.contains(candidate) || compact.contains(candidate.replace("_", ""))
        }
    }

    private fun sanitizeList(values: List<*>): Any {
        if (values.size > maxListEntries) {
            return mapOf("count" to values.size)
        }
        return values.mapNotNull { sanitizeValue(it) }
    }

    private fun sanitizeString(value: String): String {
        val maskedEmail = maskEmail(value)
        val maskedCard = maskCard(maskedEmail)
        return truncate(maskedCard)
    }

    private fun maskEmail(value: String): String {
        val match = emailPattern.matchEntire(value) ?: return value
        val local = match.groupValues[1]
        val domain = match.groupValues[2]
        if (local.length <= 2) {
            return "**@$domain"
        }
        return local.take(2) + "***@" + domain
    }

    private fun maskCard(value: String): String {
        return cardPattern.replace(value) { _ -> "****" }
    }

    private fun mask(value: Any?): String {
        val source = value?.toString().orEmpty()
        if (source.isBlank()) {
            return "[MASKED]"
        }
        if (source.length < 8) {
            return "***"
        }
        return source.take(2) + "***" + source.takeLast(2)
    }

    private fun truncate(value: String): String {
        return if (value.length <= 300) value else value.take(300) + "[TRUNCATED]"
    }
}

data class SanitizedPayload(
    val payload: Map<String, Any?>,
    val payloadTruncated: Boolean
)
