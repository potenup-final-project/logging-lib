package com.gop.logging.core

interface MaskingPolicy {
    fun maskText(value: String): String
    fun truncate(value: String, maxLength: Int): String
}

class DefaultMaskingPolicy : MaskingPolicy {
    private val sensitiveAssignmentPattern = Regex("(?i)(password|secret|token|authorization|api[_-]?key|access[_-]?key|signature)=([^\\s,;]+)")
    private val cardPattern = Regex("\\b(?:\\d[ -]*?){13,19}\\b")

    override fun maskText(value: String): String {
        val assignmentMasked = sensitiveAssignmentPattern.replace(value) { match ->
            match.groupValues[1] + "=***"
        }
        return cardPattern.replace(assignmentMasked) { _ -> "****" }
    }

    override fun truncate(value: String, maxLength: Int): String {
        if (maxLength <= 0 || value.length <= maxLength) {
            return value
        }
        return value.take(maxLength) + "[TRUNCATED]"
    }
}
