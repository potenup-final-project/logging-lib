package com.gop.logging.spring

import com.gop.logging.contract.StructuredLogger
import com.gop.logging.core.LogSanitizer
import com.gop.logging.core.StepResolver
import com.gop.logging.core.StructuredLoggerImpl
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

        return StructuredLoggerImpl(serviceName, sanitizer)
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
                setOf("/actuator", "/swagger-ui", "/v3/api-docs")
            } else {
                configuredPrefixes
            }
        )
    }

    companion object {
        private val SUPPORTED_SERVICES = setOf("pg_core", "worker", "backoffice")
    }
}
