package com.gop.logging.spring

import com.gop.logging.contract.StructuredLogger
import com.gop.logging.core.LogEmitter
import com.gop.logging.core.LogSanitizer
import com.gop.logging.core.Slf4jLogEmitter
import com.gop.logging.core.StepResolver
import com.gop.logging.core.StdoutJsonLogEmitter
import com.gop.logging.core.StructuredLoggerImpl
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.core.env.Environment
import org.springframework.core.task.TaskDecorator
import org.springframework.web.filter.OncePerRequestFilter

@AutoConfiguration
@EnableAspectJAutoProxy(proxyTargetClass = true)
class LoggingAutoConfiguration {

    private fun resolveEmitter(environment: Environment): LogEmitter {
        val configuredEmitter = System.getenv(LOG_STRUCTURED_EMITTER)
            ?.takeIf { it.isNotBlank() }
            ?: environment.getProperty(LOG_STRUCTURED_EMITTER)
            ?.takeIf { it.isNotBlank() }
            ?: EMITTER_SLF4J

        return when (configuredEmitter.trim().uppercase()) {
            EMITTER_SLF4J -> Slf4jLogEmitter(LoggerFactory.getLogger(StructuredLoggerImpl::class.java))
            EMITTER_STDOUT_JSON -> StdoutJsonLogEmitter()
            else -> throw IllegalStateException(
                "$LOG_STRUCTURED_EMITTER must be one of [$EMITTER_SLF4J, $EMITTER_STDOUT_JSON]"
            )
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun stepResolver(): StepResolver = StepResolver()

    @Bean
    @ConditionalOnMissingBean
    fun logSanitizer(): LogSanitizer = LogSanitizer()

    @Bean
    @ConditionalOnMissingBean
    fun structuredLogger(environment: Environment, sanitizer: LogSanitizer): StructuredLogger {
        val serviceName = System.getenv("LOG_SERVICE_NAME")
            ?.takeIf { it.isNotBlank() }
            ?: environment.getProperty("LOG_SERVICE_NAME")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("LOG_SERVICE_NAME must be configured")

        require(serviceName in SUPPORTED_SERVICES) {
            "LOG_SERVICE_NAME must be one of $SUPPORTED_SERVICES"
        }

        val emitter = resolveEmitter(environment)

        return StructuredLoggerImpl(
            serviceName = serviceName,
            sanitizer = sanitizer,
            emitter = emitter
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun gopStepContextAspect(stepResolver: StepResolver): StepContextAspect {
        return StepContextAspect(stepResolver)
    }

    @Bean
    @ConditionalOnMissingBean
    fun gopTechnicalLoggingAspect(structuredLogger: StructuredLogger): TechnicalLoggingAspect {
        return TechnicalLoggingAspect(structuredLogger)
    }

    @Bean
    @ConditionalOnMissingBean(TaskDecorator::class)
    fun gopMdcStepTaskDecorator(structuredLogger: StructuredLogger): TaskDecorator {
        return MdcStepTaskDecorator(structuredLogger)
    }

    @Bean
    @ConditionalOnClass(OncePerRequestFilter::class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(TraceContextFilter::class)
    fun gopTraceContextFilter(structuredLogger: StructuredLogger, environment: Environment): TraceContextFilter {
        val configuredPrefixes = environment.getProperty("LOG_TRACE_EXCLUDED_PATH_PREFIXES")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

        return TraceContextFilter(
            structuredLogger = structuredLogger,
            excludedPathPrefixes = if (configuredPrefixes.isEmpty()) {
                TraceContextFilter.DEFAULT_EXCLUDED_PATH_PREFIXES
            } else {
                configuredPrefixes
            }
        )
    }

    companion object {
        private const val LOG_STRUCTURED_EMITTER = "LOG_STRUCTURED_EMITTER"
        private const val EMITTER_SLF4J = "SLF4J"
        private const val EMITTER_STDOUT_JSON = "STDOUT_JSON"
        private val SUPPORTED_SERVICES = setOf("pg_core", "worker", "backoffice")
    }
}
