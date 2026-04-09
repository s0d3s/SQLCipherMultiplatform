import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.api.file.DuplicatesStrategy
import com.vanniktech.maven.publish.DeploymentValidation
import java.io.File
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("jvm") version "2.3.20" apply false
    id("com.android.library") version "8.13.2" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false

    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    base
}

allprojects {
    group = "io.github.s0d3s"
    version = (findProperty("releaseVersion") as String?) ?: "0.1.0-SNAPSHOT"
}

fun loadSimpleEnvFile(envFile: File): Map<String, String> {
    if (!envFile.exists()) return emptyMap()

    val keyRegex = Regex("^[A-Z_][A-Z0-9_]*$")
    val result = linkedMapOf<String, String>()

    envFile.forEachLine { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachLine

        val separator = line.indexOf('=')
        if (separator <= 0) return@forEachLine

        val key = line.substring(0, separator).trim()
        if (!keyRegex.matches(key)) return@forEachLine

        val valueRaw = line.substring(separator + 1).trim()

        // Skip multiline quoted values (for example armored keys in publishing configs)
        if ((valueRaw.startsWith('"') && !valueRaw.endsWith('"')) ||
            (valueRaw.startsWith('\'') && !valueRaw.endsWith('\''))
        ) {
            return@forEachLine
        }

        val value = valueRaw.removeSurrounding("\"").removeSurrounding("'")
        result[key] = value
    }

    return result
}

val dotenv = loadSimpleEnvFile(rootProject.file(".env"))

fun envValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: dotenv[name]?.takeIf { it.isNotBlank() }

subprojects {
    pluginManager.withPlugin("java-library") {
        extensions.configure<JavaPluginExtension>("java") {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val hostOs: String = run {
    val name = System.getProperty("os.name").lowercase()
    when {
        name.startsWith("windows") -> "windows"
        name.startsWith("mac") -> "macos"
        name.startsWith("linux") -> "linux"
        else -> "unknown"
    }
}
val hostArch: String = when (System.getProperty("os.arch").lowercase()) {
    "x86_64", "amd64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch").lowercase()
}
val sqlcipherRef = providers.gradleProperty("sqlcipher.ref").orNull

fun detectHostNativeTargetId(): String? {
    if (hostOs == "unknown") return null
    val targetId = "$hostOs-$hostArch"
    return if (targetId in setOf("windows-x64", "linux-x64", "linux-arm64", "macos-x64", "macos-arm64")) targetId else null
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
        ":samples:kmp-basic-app:verifySample",
        ":samples:kmp-sqldelight-app:verifySample"
    )
}

val nativeBuildType = providers.gradleProperty("native.buildType")
    .orElse(envValue("NATIVE_BUILD_TYPE") ?: if (hostOs == "windows") "Release" else "RelWithDebInfo")
val nativeLibBasename = providers.gradleProperty("native.lib.basename")
    .orElse(envValue("NATIVE_LIB_BASENAME") ?: "sqlcipher_jni")
    .get()

data class NativePlatformSpec(
    val targetId: String,
    val targetOs: String,
    val targetArch: String,
    val moduleName: String,
    val modulePath: String,
    val payloadProperty: String,
    val nativeLibGlob: String,
    val expectedJniLib: String,
    val integrationTestTask: String,
)

fun Project.resolvePayloadDir(pathValue: String): File {
    val trimmed = pathValue.trim()
    val asFile = File(trimmed)
    return if (asFile.isAbsolute) asFile else rootProject.file(trimmed)
}

fun recursiveNativeIncludes(spec: NativePlatformSpec): List<String> =
    when (spec.targetOs) {
        "windows" -> listOf("**/*.dll")
        "linux" -> listOf("**/*.so", "**/*.so.*")
        "macos" -> listOf("**/*.dylib")
        else -> listOf("**/${spec.expectedJniLib}")
    }

val nativePlatformSpecs = listOf(
    NativePlatformSpec(
        "windows-x64", "windows", "x64",
        "sqlcipher-multiplatform-jdbc-windows-x64",
        ":native-artifacts:sqlcipher-multiplatform-jdbc-windows-x64",
        "native.windowsPayloadDir", "*.dll", "${nativeLibBasename}.dll",
        "testIntegrationWindowsX64"
    ),
    NativePlatformSpec(
        "linux-x64", "linux", "x64",
        "sqlcipher-multiplatform-jdbc-linux-x64",
        ":native-artifacts:sqlcipher-multiplatform-jdbc-linux-x64",
        "native.linuxX64PayloadDir", "*.so", "lib${nativeLibBasename}.so",
        "testIntegrationLinuxX64"
    ),
    NativePlatformSpec(
        "linux-arm64", "linux", "arm64",
        "sqlcipher-multiplatform-jdbc-linux-arm64",
        ":native-artifacts:sqlcipher-multiplatform-jdbc-linux-arm64",
        "native.linuxArm64PayloadDir", "*.so", "lib${nativeLibBasename}.so",
        "testIntegrationLinuxArm64"
    ),
    NativePlatformSpec(
        "macos-x64", "macos", "x64",
        "sqlcipher-multiplatform-jdbc-macos-x64",
        ":native-artifacts:sqlcipher-multiplatform-jdbc-macos-x64",
        "native.macosX64PayloadDir", "*.dylib", "lib${nativeLibBasename}.dylib",
        "testIntegrationMacosX64"
    ),
    NativePlatformSpec(
        "macos-arm64", "macos", "arm64",
        "sqlcipher-multiplatform-jdbc-macos-arm64",
        ":native-artifacts:sqlcipher-multiplatform-jdbc-macos-arm64",
        "native.macosArm64PayloadDir", "*.dylib", "lib${nativeLibBasename}.dylib",
        "testIntegrationMacosArm64"
    ),
)

nativePlatformSpecs.forEach { spec ->
    project(spec.modulePath) {
        // ---- Maven Publishing ----
        pluginManager.withPlugin("com.vanniktech.maven.publish") {
            extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
                publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.PUBLISHED)
                signAllPublications()
                coordinates(project.group.toString(), spec.moduleName, project.version.toString())
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
        }

        // ---- Native Resources ----
        val externalPayloadDir = providers.gradleProperty(spec.payloadProperty)
            .orNull
            ?.let { resolvePayloadDir(it) }
        val nativeBridgeBuildDir = project(":native-bridge").layout.buildDirectory
        val nativeOutDir = nativeBridgeBuildDir.dir(nativeBuildType.map { "cmake/out/$it" })
        val nativeConanDeployDir = nativeBridgeBuildDir.dir("conan/deploy")
        val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")
        val nativeIncludePatterns = recursiveNativeIncludes(spec)
        val expectedGeneratedJni = generatedNativeResources.map {
            it.file("META-INF/sqlcipher/native/${spec.targetId}/${spec.expectedJniLib}").asFile
        }

        val prepareNativeResources = tasks.register<Sync>("prepareNativeResources") {
            val copiedEntries = mutableListOf<String>()
            duplicatesStrategy = DuplicatesStrategy.INCLUDE

            into(generatedNativeResources.map { it.dir("META-INF/sqlcipher/native/${spec.targetId}") })
            if (externalPayloadDir?.exists() == true) {
                from(externalPayloadDir) {
                    nativeIncludePatterns.forEach { include(it) }
                    include("**/${spec.expectedJniLib}")
                }
            } else if (hostOs == spec.targetOs && hostArch == spec.targetArch) {
                dependsOn(":native-bridge:buildNative")
                from(nativeOutDir) {
                    nativeIncludePatterns.forEach { include(it) }
                    include("**/${spec.expectedJniLib}")
                }

                // On Windows CI, transitive runtime DLLs may remain under Conan deploy output
                // instead of being copied next to the JNI library in nativeOutDir.
                // Include both sources so packaged native artifacts always contain all required
                // dependencies declared in manifest.properties.
                if (spec.targetOs == "windows") {
                    from(nativeConanDeployDir) {
                        include("*.dll")
                    }
                }
            }

            eachFile {
                copiedEntries += path
                path = name
            }

            includeEmptyDirs = false

            doFirst {
                val sourceMode = when {
                    externalPayloadDir?.exists() == true -> "externalPayloadDir"
                    hostOs == spec.targetOs && hostArch == spec.targetArch -> "nativeOutDir"
                    else -> "none"
                }
                logger.lifecycle("[prepareNativeResources:${spec.targetId}] sourceMode=$sourceMode")
                logger.lifecycle("[prepareNativeResources:${spec.targetId}] nativeOutDir=${nativeOutDir.get().asFile.absolutePath}")
                logger.lifecycle("[prepareNativeResources:${spec.targetId}] nativeConanDeployDir=${nativeConanDeployDir.get().asFile.absolutePath}")
                logger.lifecycle("[prepareNativeResources:${spec.targetId}] externalPayloadDir=${externalPayloadDir?.absolutePath ?: "(not set)"}")
                logger.lifecycle("[prepareNativeResources:${spec.targetId}] includePatterns=${(nativeIncludePatterns + "**/${spec.expectedJniLib}").distinct().joinToString()}")
            }

            doLast {
                val copied = copiedEntries.distinct().sorted()
                logger.lifecycle("[prepareNativeResources:${spec.targetId}] copied entries (${copied.size}):")
                if (copied.isEmpty()) {
                    logger.lifecycle("  (none)")
                } else {
                    copied.forEach { logger.lifecycle("  - $it") }
                }

                val expectedJni = expectedGeneratedJni.get()
                logger.lifecycle("[prepareNativeResources:${spec.targetId}] expected JNI resource path: ${expectedJni.absolutePath}")
                logger.lifecycle("[prepareNativeResources:${spec.targetId}] expected JNI resource exists: ${expectedJni.exists()}")
            }
        }

        pluginManager.withPlugin("java-library") {
            val jdbcJarTask = project(":sqlcipher-multiplatform-jdbc-core").tasks.named<Jar>("jar")

            tasks.named<ProcessResources>("processResources") {
                dependsOn(prepareNativeResources)
                from(generatedNativeResources)
            }

            tasks.named<Jar>("jar") {
                dependsOn(jdbcJarTask)
                from({ zipTree(jdbcJarTask.get().archiveFile.get().asFile) })
            }

            // ---- Verify Payload ----
            tasks.register("verifyNativePayload") {
                group = "verification"
                description = "Verifies ${spec.targetId} native artifact payload contains JNI binary"
                dependsOn("jar")
                doLast {
                    val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile

                    if (!jarFile.exists()) {
                        logger.lifecycle("[SKIP] ${spec.targetId}: JAR not found, nothing to verify: ${jarFile.absolutePath}")
                        return@doLast
                    }

                    val nativePrefix = "META-INF/sqlcipher/native/"
                    ZipFile(jarFile).use { zip ->
                        val entryNames = zip.entries().asSequence()
                            .map { it.name }
                            .toList()

                        val topLevel = entryNames
                            .mapNotNull { entry ->
                                val trimmed = entry.trimEnd('/')
                                when {
                                    trimmed.isBlank() -> null
                                    '/' !in trimmed -> trimmed
                                    else -> trimmed.substringBefore('/')
                                }
                            }
                            .toSortedSet()

                        val nativeTree = entryNames
                            .filter { it.startsWith(nativePrefix) }
                            .map { it.trimEnd('/') }
                            .filter { it.isNotBlank() }
                            .toSortedSet()

                        logger.lifecycle("[verifyNativePayload] JAR: ${jarFile.absolutePath}")
                        logger.lifecycle("[verifyNativePayload] Top-level entries:")
                        if (topLevel.isEmpty()) {
                            logger.lifecycle("  (empty)")
                        } else {
                            topLevel.forEach { logger.lifecycle("  - $it") }
                        }

                        logger.lifecycle("[verifyNativePayload] Entries under $nativePrefix:")
                        if (nativeTree.isEmpty()) {
                            logger.lifecycle("  (none)")
                        } else {
                            nativeTree.forEach { logger.lifecycle("  - $it") }
                        }

                        val expectedEntry = "${nativePrefix}${spec.targetId}/${spec.expectedJniLib}"
                        val hasJniLib = expectedEntry in entryNames

                        check(hasJniLib) {
                            "${spec.targetId} native artifact does not contain $expectedEntry"
                        }
                    }
                }
            }
        }

        // ---- Guardrails ----
        val integrationTaskPath = ":sqlcipher-multiplatform-jdbc-core:${spec.integrationTestTask}"
        gradle.projectsEvaluated {
            rootProject.tasks.findByPath(integrationTaskPath)?.mustRunAfter("${spec.modulePath}:verifyNativePayload")
        }

        tasks.register("verifyNativeArtifactGuardrails") {
            group = "verification"
            description = "Verifies native payload packaging and runs shared JDBC integration tests for ${spec.targetId}."
            dependsOn("verifyNativePayload", integrationTaskPath)
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
            nativePlatformSpecs.filter { it.targetId == hostTargetId }
        }
    } else {
        nativePlatformSpecs.filter { it.targetId in enabledTargets }
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
