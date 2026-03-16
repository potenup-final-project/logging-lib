package com.gop.logging.spring

import com.gop.logging.contract.ArgsLog
import com.gop.logging.contract.LogResult
import com.gop.logging.contract.LogType
import com.gop.logging.contract.ProcessResult
import com.gop.logging.contract.ReturnLog
import com.gop.logging.contract.StructuredLogger
import com.gop.logging.core.LogSanitizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory

class MethodIoLoggingAspectTest {

    @Test
    fun logsArgsAndReturnAtDebugLevelForMethodAnnotations() {
        val logger = MethodIoCapturingStructuredLogger()
        val proxy = createProxy(MethodAnnotatedServiceImpl(), logger) as MethodAnnotatedService

        proxy.process(mapOf("orderId" to "order-1", "password" to "secret-value-1234"))

        assertEquals(2, logger.logs.size)

        val argsLog = logger.logs[0]
        assertEquals("DEBUG", argsLog.level)
        assertEquals(LogType.ARGS, argsLog.logType)
        assertEquals(LogResult.START, argsLog.result)
        assertEquals(ProcessResult.APPROACH.name, argsLog.payload["processResult"])

        val arguments = argsLog.payload["arguments"] as Map<*, *>
        val input = (arguments["input"] ?: arguments["arg0"]) as Map<*, *>
        assertNotNull(input)
        assertEquals("order-1", input["orderId"])
        assertTrue((input["password"] as String).contains("***"))

        val returnLog = logger.logs[1]
        assertEquals("DEBUG", returnLog.level)
        assertEquals(LogType.RETURN, returnLog.logType)
        assertEquals(LogResult.END, returnLog.result)
        assertEquals(ProcessResult.EXIT.name, returnLog.payload["processResult"])
    }

    @Test
    fun logsReturnFailWhenAnnotatedMethodThrows() {
        val logger = MethodIoCapturingStructuredLogger()
        val proxy = createProxy(ThrowingReturnServiceImpl(), logger) as ThrowingReturnService

        try {
            proxy.explode("secret-token-123")
        } catch (_: IllegalStateException) {
            // expected
        }

        assertEquals(1, logger.logs.size)
        val failLog = logger.logs.first()
        assertEquals("DEBUG", failLog.level)
        assertEquals(LogType.RETURN, failLog.logType)
        assertEquals(LogResult.FAIL, failLog.result)
        assertEquals(ProcessResult.FAIL.name, failLog.payload["processResult"])
        assertEquals("IllegalStateException", failLog.payload["exceptionType"])
        assertTrue((failLog.payload["exceptionMessage"] as String).contains("***"))
    }

    @Test
    fun appliesClassLevelAnnotationsWithoutDuplicateLogs() {
        val logger = MethodIoCapturingStructuredLogger()
        val proxy = createProxy(ClassAnnotatedServiceImpl(), logger) as ClassAnnotatedService

        proxy.run("merchant-1")

        assertEquals(2, logger.logs.size)
        assertEquals(LogType.ARGS, logger.logs[0].logType)
        assertEquals(LogType.RETURN, logger.logs[1].logType)
    }

    @Test
    fun logsOnlyConfiguredDirectionWhenSingleAnnotationUsed() {
        val logger = MethodIoCapturingStructuredLogger()
        val proxy = createProxy(ReturnOnlyServiceImpl(), logger) as ReturnOnlyService

        proxy.calculate(5)

        assertEquals(1, logger.logs.size)
        assertEquals(LogType.RETURN, logger.logs.first().logType)
        assertEquals(ProcessResult.EXIT.name, logger.logs.first().payload["processResult"])
    }

    private fun createProxy(target: Any, logger: StructuredLogger): Any {
        val factory = AspectJProxyFactory(target)
        factory.addAspect(MethodIoLoggingAspect(logger, LogSanitizer()))
        return factory.getProxy()
    }
}

private interface MethodAnnotatedService {
    fun process(input: Map<String, Any?>): Map<String, Any?>
}

private class MethodAnnotatedServiceImpl : MethodAnnotatedService {
    @ArgsLog
    @ReturnLog
    override fun process(input: Map<String, Any?>): Map<String, Any?> {
        return mapOf("status" to "ok", "amount" to 1000)
    }
}

@ArgsLog
@ReturnLog
private class ClassAnnotatedServiceImpl : ClassAnnotatedService {
    override fun run(merchantId: String): String {
        return merchantId
    }
}

private interface ClassAnnotatedService {
    fun run(merchantId: String): String
}

private interface ReturnOnlyService {
    fun calculate(number: Int): Int
}

private class ReturnOnlyServiceImpl : ReturnOnlyService {
    @ReturnLog
    override fun calculate(number: Int): Int {
        return number * 2
    }
}

private interface ThrowingReturnService {
    fun explode(token: String): String
}

private class ThrowingReturnServiceImpl : ThrowingReturnService {
    @ReturnLog
    override fun explode(token: String): String {
        throw IllegalStateException("failed token=$token")
    }
}

private class MethodIoCapturingStructuredLogger : StructuredLogger {
    val logs = mutableListOf<MethodIoLogCall>()

    override fun debug(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += MethodIoLogCall("DEBUG", logType, result, payload, error)
    }

    override fun info(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += MethodIoLogCall("INFO", logType, result, payload, error)
    }

    override fun warn(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += MethodIoLogCall("WARN", logType, result, payload, error)
    }

    override fun error(logType: LogType, result: LogResult, payload: Map<String, Any?>, error: Throwable?) {
        logs += MethodIoLogCall("ERROR", logType, result, payload, error)
    }
}

private data class MethodIoLogCall(
    val level: String,
    val logType: LogType,
    val result: LogResult,
    val payload: Map<String, Any?>,
    val error: Throwable?
)
