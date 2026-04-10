plugins {
    kotlin("jvm")
    `java-library`
    id("com.vanniktech.maven.publish")
}

import java.io.File
import com.vanniktech.maven.publish.DeploymentValidation

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
}

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

mavenPublishing {
    publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.PUBLISHED)
    signAllPublications()

    coordinates(
        project.group.toString(),
        "sqlcipher-multiplatform-jdbc-core",
        project.version.toString()
    )

    pom {
        name.set(providers.gradleProperty("POM_NAME").orElse(rootProject.name))
        description.set(
            providers.gradleProperty("POM_DESCRIPTION")
                .orElse("SQLCipherMultiplatform Kotlin/JVM + KMP libraries")
        )
        url.set(
            providers.gradleProperty("POM_URL")
                .orElse("https://github.com/s0d3s/SQLCipherMultiplatform")
        )

        licenses {
            license {
                name.set(providers.gradleProperty("POM_LICENSE_NAME").orElse("MIT"))
                url.set(providers.gradleProperty("POM_LICENSE_URL").orElse("https://opensource.org/licenses/MIT"))
            }
        }

        developers {
            developer {
                id.set(providers.gradleProperty("POM_DEVELOPER_ID").orElse("s0d3s"))
                name.set(providers.gradleProperty("POM_DEVELOPER_NAME").orElse("Sergio"))
                email.set(providers.gradleProperty("POM_DEVELOPER_EMAIL").orElse(""))
            }
        }

        scm {
            val scmUrl = providers.gradleProperty("POM_SCM_URL")
                .orElse("https://github.com/s0d3s/SQLCipherMultiplatform")
            url.set(scmUrl)
            connection.set(scmUrl.map { "scm:git:$it.git" })
            developerConnection.set("scm:git:ssh://git@github.com:s0d3s/SQLCipherMultiplatform.git")
        }
    }
}

data class IntegrationGuardrailTarget(
    val taskName: String,
    val nativeArtifactModulePath: String,
    val payloadDirProperty: String,
    val defaultNativePathProvider: () -> String,
)

val integrationGuardrailTargets = listOf(
    IntegrationGuardrailTarget(
        taskName = "testIntegrationWindowsX64",
        nativeArtifactModulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-windows-x64",
        payloadDirProperty = "native.windowsPayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-artifacts/sqlcipher-multiplatform-jdbc-windows-x64/build/resources/main/META-INF/sqlcipher/native/windows-x64").absolutePath
        }
    ),
    IntegrationGuardrailTarget(
        taskName = "testIntegrationLinuxX64",
        nativeArtifactModulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-linux-x64",
        payloadDirProperty = "native.linuxX64PayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-artifacts/sqlcipher-multiplatform-jdbc-linux-x64/build/resources/main/META-INF/sqlcipher/native/linux-x64").absolutePath
        }
    ),
    IntegrationGuardrailTarget(
        taskName = "testIntegrationLinuxArm64",
        nativeArtifactModulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-linux-arm64",
        payloadDirProperty = "native.linuxArm64PayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-artifacts/sqlcipher-multiplatform-jdbc-linux-arm64/build/resources/main/META-INF/sqlcipher/native/linux-arm64").absolutePath
        }
    ),
    IntegrationGuardrailTarget(
        taskName = "testIntegrationMacosX64",
        nativeArtifactModulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-macos-x64",
        payloadDirProperty = "native.macosX64PayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-artifacts/sqlcipher-multiplatform-jdbc-macos-x64/build/resources/main/META-INF/sqlcipher/native/macos-x64").absolutePath
        }
    ),
    IntegrationGuardrailTarget(
        taskName = "testIntegrationMacosArm64",
        nativeArtifactModulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-macos-arm64",
        payloadDirProperty = "native.macosArm64PayloadDir",
        defaultNativePathProvider = {
            rootProject.file("native-artifacts/sqlcipher-multiplatform-jdbc-macos-arm64/build/resources/main/META-INF/sqlcipher/native/macos-arm64").absolutePath
        }
    )
)

integrationGuardrailTargets.forEach { target ->
    tasks.register<Test>(target.taskName) {
        group = "verification"
        description = "Runs shared JDBC core integration tests for ${target.taskName.removePrefix("testIntegration")}."

        useJUnitPlatform()
        dependsOn(tasks.named("testClasses"))
        dependsOn("${target.nativeArtifactModulePath}:prepareNativeResources")
        dependsOn("${target.nativeArtifactModulePath}:processResources")
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
