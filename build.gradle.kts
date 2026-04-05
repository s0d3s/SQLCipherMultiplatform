import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.plugins.signing.SigningExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion

fun normalizeSigningKeyId(raw: String?): String? {
    val trimmed = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
    if (trimmed.isBlank()) return null
    val normalized = trimmed.uppercase()
    if (!normalized.matches(Regex("[0-9A-F]+"))) return raw
    return when {
        normalized.length == 8 -> "0x$normalized"
        normalized.length == 16 -> "0x${normalized.takeLast(8)}"
        normalized.length >= 40 -> "0x${normalized.takeLast(8)}"
        else -> raw
    }
}

plugins {
    kotlin("multiplatform") version "1.9.24" apply false
    kotlin("jvm") version "1.9.24" apply false
    id("com.android.library") version "8.2.2" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    base
}

allprojects {
    group = "io.github.s0d3s"
    version = (findProperty("releaseVersion") as String?) ?: "0.1.0-SNAPSHOT"
}

subprojects {
    pluginManager.withPlugin("java-library") {
        extensions.configure<JavaPluginExtension>("java") {
            toolchain.languageVersion.set(JavaLanguageVersion.of(17))
        }

        pluginManager.withPlugin("maven-publish") {
            extensions.configure<PublishingExtension>("publishing") {
                if (publications.findByName("mavenJava") == null && components.findByName("java") != null) {
                    publications.create("mavenJava", MavenPublication::class.java) {
                        from(components.getByName("java"))
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension>("publishing") {
            publications.withType(MavenPublication::class.java).configureEach {
                pom {
                    name.set(provider { project.findProperty("POM_NAME")?.toString() ?: rootProject.name })
                    description.set(
                        provider {
                            project.findProperty("POM_DESCRIPTION")?.toString()
                                ?: "SQLCipherMultiplatform Kotlin/JVM + KMP libraries"
                        }
                    )
                    url.set(provider { project.findProperty("POM_URL")?.toString() ?: "https://github.com/s0d3s/SQLCipherMultiplatform" })

                    licenses {
                        license {
                            name.set(provider { project.findProperty("POM_LICENSE_NAME")?.toString() ?: "MIT" })
                            url.set(provider { project.findProperty("POM_LICENSE_URL")?.toString() ?: "https://opensource.org/licenses/MIT" })
                        }
                    }

                    developers {
                        developer {
                            id.set(provider { project.findProperty("POM_DEVELOPER_ID")?.toString() ?: "s0d3s" })
                            name.set(provider { project.findProperty("POM_DEVELOPER_NAME")?.toString() ?: "s0d3s" })
                            email.set(provider { project.findProperty("POM_DEVELOPER_EMAIL")?.toString() ?: "" })
                        }
                    }

                    scm {
                        val scmUrl = provider {
                            project.findProperty("POM_SCM_URL")?.toString()
                                ?: "https://github.com/s0d3s/SQLCipherMultiplatform"
                        }
                        url.set(scmUrl)
                        connection.set(provider { "scm:git:${scmUrl.get()}.git" })
                        developerConnection.set(provider { "scm:git:ssh://git@github.com:s0d3s/SQLCipherMultiplatform.git" })
                    }
                }
            }

            repositories {
                maven {
                    name = "sonatype"
                    val versionText = project.version.toString()
                    // Maven Central releases are deployed via Sonatype staging deploy API endpoint
                    // when using Gradle maven-publish/signing flow.
                    val releaseUrl = "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
                    val snapshotUrl = "https://central.sonatype.com/repository/maven-snapshots/"
                    url = uri(if (versionText.endsWith("SNAPSHOT")) snapshotUrl else releaseUrl)

                    credentials {
                        username = (project.findProperty("mavenCentralUsername") as String?)
                            ?: System.getenv("MAVEN_CENTRAL_USERNAME")
                            ?: (project.findProperty("ossrhUsername") as String?)
                            ?: System.getenv("OSSRH_USERNAME")
                        password = (project.findProperty("mavenCentralPassword") as String?)
                            ?: System.getenv("MAVEN_CENTRAL_PASSWORD")
                            ?: (project.findProperty("ossrhPassword") as String?)
                            ?: System.getenv("OSSRH_PASSWORD")
                    }
                }
            }
        }
    }

    pluginManager.withPlugin("signing") {
        val signingKey = (findProperty("signingInMemoryKey") as String?) ?: System.getenv("SIGNING_KEY")
        val signingPassword = (findProperty("signingInMemoryKeyPassword") as String?) ?: System.getenv("SIGNING_PASSWORD")
        val signingKeyIdRaw = (findProperty("signingInMemoryKeyId") as String?)
            ?: System.getenv("SIGNING_KEY_ID")
            ?: (findProperty("signing.keyId") as String?)
        val signingKeyId = normalizeSigningKeyId(signingKeyIdRaw)

        extensions.configure<SigningExtension>("signing") {
            val isReleaseVersion = !project.version.toString().endsWith("SNAPSHOT")
            setRequired {
                isReleaseVersion && gradle.startParameter.taskNames.any { it.contains("publish", ignoreCase = true) }
            }

            if (!signingKey.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
                useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
            }

            val publishing = extensions.findByType(PublishingExtension::class.java)
            if (publishing != null) {
                sign(publishing.publications)
            }
        }
    }
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val sqlcipherRef = providers.gradleProperty("sqlcipher.ref").orNull

fun detectHostNativeTargetId(): String? {
    val osName = System.getProperty("os.name").lowercase()
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> return null
    }

    return when {
        osName.startsWith("windows") && arch == "x64" -> "windows-x64"
        osName.startsWith("linux") && arch == "x64" -> "linux-x64"
        osName.startsWith("linux") && arch == "arm64" -> "linux-arm64"
        osName.startsWith("mac") && arch == "x64" -> "macos-x64"
        osName.startsWith("mac") && arch == "arm64" -> "macos-arm64"
        else -> null
    }
}

tasks.register<Exec>("initSqlcipherSubmodule") {
    group = "sqlcipher"
    description = "Initializes SQLCipher upstream git submodule"
    commandLine("git", "submodule", "update", "--init", "--recursive", "third_party/sqlcipher/upstream")
}

tasks.register<Exec>("updateSqlcipherAmalgamation") {
    group = "sqlcipher"
    description = "Regenerates third_party/sqlcipher/sqlite3.c and sqlite3.h from upstream submodule sources"
    dependsOn("initSqlcipherSubmodule")

    workingDir = rootDir

    if (isWindowsHost) {
        val command = mutableListOf(
            "powershell",
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            "${rootDir.absolutePath}/scripts/update-sqlcipher-amalgamation.ps1"
        )

        if (!sqlcipherRef.isNullOrBlank()) {
            command += listOf("-Ref", sqlcipherRef)
        }

        commandLine(command)
    } else {
        val command = mutableListOf(
            "bash",
            "${rootDir.absolutePath}/scripts/update-sqlcipher-amalgamation.sh"
        )

        if (!sqlcipherRef.isNullOrBlank()) {
            command += sqlcipherRef
        }

        commandLine(command)
    }
}

tasks.register("verifySamples") {
    group = "verification"
    description = "Runs all executable sample verifications (JDBC, KMP basic, KMP SQLDelight)"
    dependsOn(
        ":samples:desktop-jvm:verifySample",
        ":samples:kmp-basic-app:verifySample",
        ":samples:kmp-sqldelight-app:verifySample"
    )
}

data class NativeArtifactGuardrailSpec(
    val targetId: String,
    val modulePath: String,
    val payloadVerificationTask: String,
    val jdbcCoreIntegrationTask: String,
)

val nativeArtifactGuardrailSpecs = listOf(
    NativeArtifactGuardrailSpec(
        targetId = "windows-x64",
        modulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-windows-x64",
        payloadVerificationTask = "verifyWindowsNativePayload",
        jdbcCoreIntegrationTask = "testIntegrationWindowsX64",
    ),
    NativeArtifactGuardrailSpec(
        targetId = "linux-x64",
        modulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-linux-x64",
        payloadVerificationTask = "verifyLinuxX64NativePayload",
        jdbcCoreIntegrationTask = "testIntegrationLinuxX64",
    ),
    NativeArtifactGuardrailSpec(
        targetId = "linux-arm64",
        modulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-linux-arm64",
        payloadVerificationTask = "verifyLinuxArm64NativePayload",
        jdbcCoreIntegrationTask = "testIntegrationLinuxArm64",
    ),
    NativeArtifactGuardrailSpec(
        targetId = "macos-x64",
        modulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-macos-x64",
        payloadVerificationTask = "verifyMacosX64NativePayload",
        jdbcCoreIntegrationTask = "testIntegrationMacosX64",
    ),
    NativeArtifactGuardrailSpec(
        targetId = "macos-arm64",
        modulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-macos-arm64",
        payloadVerificationTask = "verifyMacosArm64NativePayload",
        jdbcCoreIntegrationTask = "testIntegrationMacosArm64",
    )
)

nativeArtifactGuardrailSpecs.forEach { spec ->
    val payloadTaskPath = "${spec.modulePath}:${spec.payloadVerificationTask}"
    val integrationTaskPath = ":sqlcipher-multiplatform-jdbc-core:${spec.jdbcCoreIntegrationTask}"

    project(spec.modulePath) {
        gradle.projectsEvaluated {
            rootProject.tasks.findByPath(integrationTaskPath)?.mustRunAfter(payloadTaskPath)
        }

        tasks.register("verifyNativeArtifactGuardrails") {
            group = "verification"
            description = "Verifies native payload packaging and runs shared JDBC integration tests for ${spec.targetId}."
            dependsOn(
                payloadTaskPath,
                integrationTaskPath
            )

            doLast {
                logger.lifecycle("[PASS] Native artifact guardrails completed for ${spec.targetId}")
            }
        }
    }
}

tasks.register("verifyNativeArtifactsGuardrails") {
    group = "verification"
    description = "Runs guardrail verification for enabled native artifact targets."

    val enabledTargets = providers.gradleProperty("native.enabledTargets")
        .orNull
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        ?.toSet()

    val hostTargetId = detectHostNativeTargetId()

    val selectedSpecs = if (enabledTargets.isNullOrEmpty()) {
        if (hostTargetId == null) {
            emptyList()
        } else {
            nativeArtifactGuardrailSpecs.filter { it.targetId == hostTargetId }
        }
    } else {
        nativeArtifactGuardrailSpecs.filter { it.targetId in enabledTargets }
    }

    check(selectedSpecs.isNotEmpty()) {
        "No native targets selected for verifyNativeArtifactsGuardrails. Provide -Pnative.enabledTargets=... or run on a supported host target."
    }

    selectedSpecs.forEach { spec ->
        dependsOn("${spec.modulePath}:verifyNativeArtifactGuardrails")
    }

    doLast {
        logger.lifecycle("[PASS] Native artifact guardrails aggregate completed for targets: ${selectedSpecs.joinToString { it.targetId }}")
    }
}
