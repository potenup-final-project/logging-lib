package com.gop.logging.spring

import com.gop.logging.contract.ArgsLog
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogType
import com.gop.logging.contract.ProcessResult
import com.gop.logging.contract.ReturnLog
import com.gop.logging.contract.StructuredLogger
import com.gop.logging.core.LogSanitizer
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotationUtils
import java.lang.reflect.Method

@Aspect
class MethodIoLoggingAspect(
    private val structuredLogger: StructuredLogger,
    private val sanitizer: LogSanitizer
) {

    @Around(
        "execution(* *(..)) && (" +
            "@annotation(com.gop.logging.contract.ArgsLog) || " +
            "@annotation(com.gop.logging.contract.ReturnLog) || " +
            "@within(com.gop.logging.contract.ArgsLog) || " +
            "@within(com.gop.logging.contract.ReturnLog)" +
            ")"
    )
    fun around(joinPoint: ProceedingJoinPoint): Any? {
        val targetClass = joinPoint.target?.let { AopUtils.getTargetClass(it) }
            ?: (joinPoint.signature as? MethodSignature)?.method?.declaringClass
            ?: joinPoint.signature.declaringType
        val method = resolveMethod(joinPoint, targetClass)

        val argsEnabled = hasAnnotation(method, targetClass, ArgsLog::class.java)
        val returnEnabled = hasAnnotation(method, targetClass, ReturnLog::class.java)

        if (argsEnabled) {
            structuredLogger.debug(
                logType = LogType.ARGS,
                result = LogResult.START,
                payload = buildArgsPayload(joinPoint, targetClass, method)
            )
        }

        var returned: Any? = null
        var thrown: Throwable? = null

        try {
            returned = joinPoint.proceed()
            return returned
        } catch (ex: Throwable) {
            thrown = ex
            throw ex
        } finally {
            if (returnEnabled) {
                if (thrown == null) {
                    structuredLogger.debug(
                        logType = LogType.RETURN,
                        result = LogResult.END,
                        payload = buildReturnPayload(targetClass, method, returned)
                    )
                } else {
                    structuredLogger.debug(
                        logType = LogType.RETURN,
                        result = LogResult.FAIL,
                        payload = buildExceptionPayload(targetClass, method, thrown),
                        error = thrown
                    )
                }
            }
        }
    }

    private fun resolveMethod(joinPoint: ProceedingJoinPoint, targetClass: Class<*>): Method {
        val signatureMethod = (joinPoint.signature as MethodSignature).method
        return AopUtils.getMostSpecificMethod(signatureMethod, targetClass)
    }

    private fun hasAnnotation(method: Method, targetClass: Class<*>, annotationType: Class<out Annotation>): Boolean {
        return AnnotationUtils.findAnnotation(method, annotationType) != null ||
            AnnotationUtils.findAnnotation(targetClass, annotationType) != null
    }

    private fun buildArgsPayload(
        joinPoint: ProceedingJoinPoint,
        targetClass: Class<*>,
        method: Method
    ): Map<String, Any?> {
        val signature = joinPoint.signature as MethodSignature
        val names = signature.parameterNames
        val values = joinPoint.args

        val arguments = linkedMapOf<String, Any?>()
        values.forEachIndexed { index, value ->
            if (shouldSkipValue(value)) {
                return@forEachIndexed
            }
            val key = names.getOrNull(index) ?: "arg$index"
            arguments[key] = sanitizeValue(key, value, 0)
        }

        val rawPayload = mapOf(
            "declaringClass" to targetClass.simpleName,
            "method" to method.name,
            "processResult" to ProcessResult.APPROACH.name,
            "arguments" to arguments
        )

        return sanitizer.sanitize(rawPayload).payload
    }

    private fun buildReturnPayload(targetClass: Class<*>, method: Method, result: Any?): Map<String, Any?> {
        val rawPayload = mapOf(
            "declaringClass" to targetClass.simpleName,
            "method" to method.name,
            "processResult" to ProcessResult.EXIT.name,
            "returnValue" to sanitizeValue("returnValue", result, 0)
        )

        return sanitizer.sanitize(rawPayload).payload
    }

    private fun buildExceptionPayload(targetClass: Class<*>, method: Method, throwable: Throwable): Map<String, Any?> {
        val rawPayload = mapOf(
            "declaringClass" to targetClass.simpleName,
            "method" to method.name,
            "processResult" to ProcessResult.FAIL.name,
            "exceptionType" to throwable::class.java.simpleName,
            "exceptionMessage" to sanitizeExceptionMessage(throwable.message.orEmpty())
        )

        return sanitizer.sanitize(rawPayload).payload
    }

    private fun shouldSkipValue(value: Any?): Boolean {
        val className = value?.javaClass?.name ?: return false
        return SKIPPED_TYPE_PREFIXES.any { className.startsWith(it) }
    }

    private fun sanitizeValue(key: String?, value: Any?, depth: Int): Any? {
        if (value == null) {
            return null
        }
        if (depth >= MAX_DEPTH) {
            return summarize(value)
        }

        return when (value) {
            is Number, is Boolean -> value
            is Enum<*> -> value.name
            is CharSequence -> sanitizeString(value.toString())
            is Map<*, *> -> sanitizeMap(value, depth + 1)
            is Array<*> -> sanitizeIterable(value.toList(), depth + 1)
            is Iterable<*> -> sanitizeIterable(value.toList(), depth + 1)
            else -> {
                if (key != null && isSensitiveKey(key)) {
                    "[MASKED]"
                } else {
                    summarize(value)
                }
            }
        }
    }

    private fun sanitizeMap(value: Map<*, *>, depth: Int): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        value.entries.take(MAX_MAP_ENTRIES).forEach { (rawKey, rawValue) ->
            val key = rawKey?.toString() ?: return@forEach
            result[key] = if (isSensitiveKey(key)) {
                "[MASKED]"
            } else {
                sanitizeValue(key, rawValue, depth)
            }
        }

        if (value.size > MAX_MAP_ENTRIES) {
            result["truncated"] = true
            result["originalSize"] = value.size
        }

        return result
    }

    private fun sanitizeIterable(values: List<*>, depth: Int): Any {
        return if (values.size > MAX_COLLECTION_ENTRIES) {
            mapOf(
                "count" to values.size,
                "sample" to values.take(MAX_COLLECTION_SAMPLE).map { sanitizeValue(null, it, depth) }
            )
        } else {
            values.map { sanitizeValue(null, it, depth) }
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().replace("_", "").replace("-", "")
        return SENSITIVE_KEYS.any { normalized.contains(it) }
    }

    private fun summarize(value: Any): Map<String, String> {
        return mapOf(
            "type" to value::class.java.simpleName,
            "value" to "[OBJECT]"
        )
    }

    private fun sanitizeString(value: String): String {
        return if (value.length > MAX_TEXT_LENGTH) {
            value.take(MAX_TEXT_LENGTH) + "[TRUNCATED]"
        } else {
            value
        }
    }

    private fun sanitizeExceptionMessage(value: String): String {
        val masked = SENSITIVE_ASSIGNMENT_PATTERN.replace(value) { match ->
            match.groupValues[1] + "=***"
        }
        return sanitizeString(masked)
    }

    companion object {
        private const val MAX_DEPTH = 2
        private const val MAX_TEXT_LENGTH = 300
        private const val MAX_MAP_ENTRIES = 10
        private const val MAX_COLLECTION_ENTRIES = 5
        private const val MAX_COLLECTION_SAMPLE = 3

        private val SKIPPED_TYPE_PREFIXES = listOf(
            "jakarta.servlet.",
            "javax.servlet.",
            "org.springframework.validation.BindingResult",
            "org.springframework.web.multipart.",
            "kotlin.coroutines.Continuation"
        )

        private val SENSITIVE_KEYS = setOf(
            "password",
            "secret",
            "token",
            "authorization",
            "apikey",
            "accesskey",
            "webhooksecret",
            "clientsecret",
            "signature"
        )

        private val SENSITIVE_ASSIGNMENT_PATTERN = Regex("(?i)(password|secret|token|authorization|api[_-]?key|access[_-]?key|signature)=([^\\s,;]+)")
    }
}
