plugins {
    kotlin("jvm")
    `java-library`
    id("maven-publish")
    id("signing")
}

import java.io.File

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeBuildType = providers.gradleProperty("native.buildType")
    .orElse(System.getenv("NATIVE_BUILD_TYPE") ?: if (isWindowsHost) "Release" else "RelWithDebInfo")

tasks.test {
    useJUnitPlatform()

    val integrationEnabled = System.getProperty("sqlcipher.integration.enabled")
    if (!integrationEnabled.isNullOrBlank()) {
        systemProperty("sqlcipher.integration.enabled", integrationEnabled)
    }

    val nativePath = System.getProperty("sqlcipher.native.path")
    if (!nativePath.isNullOrBlank()) {
        systemProperty("sqlcipher.native.path", nativePath)
    }

    val nativeLibBasename = System.getProperty("sqlcipher.native.lib.basename")
        ?: providers.gradleProperty("native.lib.basename").orNull
    if (!nativeLibBasename.isNullOrBlank()) {
        systemProperty("sqlcipher.native.lib.basename", nativeLibBasename)
    }
}

data class IntegrationGuardrailTarget(
    val taskName: String,
    val payloadDirProperty: String,
    val defaultNativePathProvider: () -> String,
)

val integrationGuardrailTargets = listOf(
    IntegrationGuardrailTarget(
        taskName = "testIntegrationWindowsX64",
        payloadDirProperty = "native.windowsPayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-bridge/build/cmake/out/${nativeBuildType.get()}").absolutePath
        }
    ),
    IntegrationGuardrailTarget(
        taskName = "testIntegrationLinuxX64",
        payloadDirProperty = "native.linuxX64PayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-bridge/build/cmake/out").absolutePath
        }
    ),
    IntegrationGuardrailTarget(
        taskName = "testIntegrationLinuxArm64",
        payloadDirProperty = "native.linuxArm64PayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-bridge/build/cmake/out").absolutePath
        }
    ),
    IntegrationGuardrailTarget(
        taskName = "testIntegrationMacosX64",
        payloadDirProperty = "native.macosX64PayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-bridge/build/cmake/out").absolutePath
        }
    ),
    IntegrationGuardrailTarget(
        taskName = "testIntegrationMacosArm64",
        payloadDirProperty = "native.macosArm64PayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-bridge/build/cmake/out").absolutePath
        }
    )
)

integrationGuardrailTargets.forEach { target ->
    tasks.register<Test>(target.taskName) {
        group = "verification"
        description = "Runs shared JDBC core integration tests for ${target.taskName.removePrefix("testIntegration")}."

        useJUnitPlatform()
        dependsOn(tasks.named("testClasses"))
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath

        filter {
            includeTestsMatching("*SqlCipherIntegrationTest")
        }

        doFirst {
            fun normalizePath(rawPath: String): String {
                val candidate = File(rawPath)
                return if (candidate.isAbsolute) candidate.absolutePath else rootProject.file(rawPath).absolutePath
            }

            val nativePath = System.getProperty("sqlcipher.native.path")
                ?.let(::normalizePath)
                ?: providers.gradleProperty(target.payloadDirProperty).orNull?.let(::normalizePath)
                ?: normalizePath(target.defaultNativePathProvider())

            val nativeLibBasename = System.getProperty("sqlcipher.native.lib.basename")
                ?: providers.gradleProperty("native.lib.basename").orNull
                ?: "sqlcipher_jni"

            logger.lifecycle("[INFO] ${target.taskName}: sqlcipher.native.path=$nativePath")
            systemProperty("sqlcipher.integration.enabled", "true")
            systemProperty("sqlcipher.native.path", nativePath)
            systemProperty("sqlcipher.native.lib.basename", nativeLibBasename)
        }
    }
}
