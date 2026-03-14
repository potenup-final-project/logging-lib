import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    base
}

group = "com.gop.logging"
version = providers.gradleProperty("loggingLibVersion").orElse("1.0.0-SNAPSHOT").get()

abstract class ValidateLoggingConventionsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun validate() {
        val stepRegex = Regex("^[a-z]+(\\.[a-z0-9]+)+$")
        val suffixRegex = Regex("^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$")
        val logPrefixLiteralRegex = Regex("@LogPrefix\\(\\s*\"")
        val structuredLoggerStepRegex = Regex("structuredLogger\\.(debug|info|warn|error)\\([^)]*step\\s*=")
        val stepPrefixValueRegex = Regex("const\\s+val\\s+\\w+\\s*=\\s*\"([^\"]+)\"")
        val logSuffixLiteralRegex = Regex("@LogSuffix\\(\\s*\"([^\"]+)\"\\s*\\)")
        val technicalMonitoredStepRegex = Regex("@TechnicalMonitored\\([^)]*step\\s*=\\s*\"([^\"]+)\"")

        val violations = mutableListOf<String>()

        sourceFiles.files.forEach { file ->
            val text = file.readText()
            val lines = text.lines()

            lines.forEachIndexed { index, line ->
                if (logPrefixLiteralRegex.containsMatchIn(line)) {
                    violations += "${file.path}:${index + 1} - @LogPrefix must use StepPrefix constant, not string literal"
                }

                if (structuredLoggerStepRegex.containsMatchIn(line)) {
                    violations += "${file.path}:${index + 1} - structuredLogger must not pass step directly"
                }
            }

            if (file.name == "StepPrefix.kt") {
                stepPrefixValueRegex.findAll(text).forEach { match ->
                    val value = match.groupValues[1]
                    if (!stepRegex.matches(value)) {
                        violations += "${file.path} - StepPrefix value '$value' violates regex ${stepRegex.pattern}"
                    }
                }
            }

            logSuffixLiteralRegex.findAll(text).forEach { match ->
                val suffix = match.groupValues[1]
                if (!suffixRegex.matches(suffix)) {
                    violations += "${file.path} - @LogSuffix value '$suffix' violates regex ${suffixRegex.pattern}"
                }
            }

            technicalMonitoredStepRegex.findAll(text).forEach { match ->
                val step = match.groupValues[1]
                if (!stepRegex.matches(step)) {
                    violations += "${file.path} - @TechnicalMonitored step '$step' violates regex ${stepRegex.pattern}"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Logging convention validation failed:")
                    violations.forEach { appendLine(" - $it") }
                }
            )
        }
    }
}

val validateLoggingConventions = tasks.register("validateLoggingConventions", ValidateLoggingConventionsTask::class.java) {
    group = "verification"
    description = "Validates logging annotation and step conventions"

    subprojects.forEach { subproject ->
        sourceFiles.from(
            subproject.fileTree(subproject.projectDir) {
                include("src/main/**/*.kt")
                include("src/test/**/*.kt")
            }
        )
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    pluginManager.apply("maven-publish")

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        extensions.configure(JavaPluginExtension::class.java) {
            withSourcesJar()
            withJavadocJar()
        }
    }

    val publishing = extensions.getByType(PublishingExtension::class.java)

    pluginManager.withPlugin("java") {
        publishing.publications {
            create("mavenJava", MavenPublication::class.java) {
                from(components.getByName("java"))
                artifactId = project.name
            }
        }
    }

    val releaseUrl = providers.gradleProperty("codeArtifactReleaseUrl").orNull
    val snapshotUrl = providers.gradleProperty("codeArtifactSnapshotUrl").orNull
    val repositoryUrl = if (version.toString().endsWith("-SNAPSHOT")) snapshotUrl else releaseUrl

    if (!repositoryUrl.isNullOrBlank()) {
        publishing.repositories {
            maven {
                name = "codeArtifact"
                url = uri(repositoryUrl)
                credentials {
                    username = providers.gradleProperty("codeArtifactUser").orElse("aws").get()
                    password = providers.gradleProperty("codeArtifactToken").orNull
                }
            }
        }
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(validateLoggingConventions)
    }
}
