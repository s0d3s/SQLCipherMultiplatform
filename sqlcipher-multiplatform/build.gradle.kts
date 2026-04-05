import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("maven-publish")
    id("signing")
}

fun currentDesktopPlatform(): String {
    val osName = System.getProperty("os.name")
        ?.lowercase()
        ?: error("Missing os.name system property")
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported architecture: ${System.getProperty("os.arch")}")
    }

    return when {
        osName.contains("windows") && arch == "x64" -> "windows-x64"
        osName.contains("linux") && arch == "x64" -> "linux-x64"
        osName.contains("linux") && arch == "arm64" -> "linux-arm64"
        osName.contains("mac") && arch == "x64" -> "macos-x64"
        osName.contains("mac") && arch == "arm64" -> "macos-arm64"
        else -> error("Unsupported OS/arch: $osName / $arch")
    }
}

val useLocalJdbcPlatforms = providers.gradleProperty("sqlcipher.useLocalJdbcPlatforms")
    .orElse(System.getenv("SQLCIPHER_USE_LOCAL_JDBC_PLATFORMS") ?: "true")
    .map { it.equals("true", ignoreCase = true) }
    .get()

val includeAllNativePlatforms = providers.gradleProperty("sqlcipher.includeAllNativePlatforms")
    .orElse(
        System.getenv("SQLCIPHER_INCLUDE_ALL_NATIVE_PLATFORMS")
            ?: if (useLocalJdbcPlatforms) "false" else "true"
    )
    .map { it.equals("true", ignoreCase = true) }
    .get()

val projectVersion = rootProject.version.toString()
val currentPlatform = currentDesktopPlatform()
val currentLocalJdbcModulePath = ":native-artifacts:sqlcipher-multiplatform-jdbc-$currentPlatform"
val allNativePlatforms = listOf(
    "windows-x64",
    "linux-x64",
    "linux-arm64",
    "macos-x64",
    "macos-arm64"
)

val selectedNativePlatforms = if (includeAllNativePlatforms) allNativePlatforms else listOf(currentPlatform)
val selectedLocalNativeModulePaths = selectedNativePlatforms.map { ":native-artifacts:sqlcipher-multiplatform-jdbc-$it" }
val selectedRemoteNativeCoordinates = selectedNativePlatforms.map {
    "io.github.s0d3s:sqlcipher-multiplatform-jdbc-$it:$projectVersion"
}

tasks.register("verifySingleLocalJdbcArtifact") {
    group = "verification"
    description = "Ensures local JVM runtime dependency set matches selected SQLCipher native platforms."

    doLast {
        if (!useLocalJdbcPlatforms) {
            logger.lifecycle("[INFO] verifySingleLocalJdbcArtifact: local JDBC mode disabled; skipping local-artifact guardrail")
            return@doLast
        }

        val jvmMainSourceSet = kotlin.sourceSets.getByName("jvmMain")
        val runtimeOnlyConfigurationName = jvmMainSourceSet.runtimeOnlyConfigurationName
        val runtimeOnlyConfiguration = configurations.getByName(runtimeOnlyConfigurationName)

        val localJdbcDependencies = runtimeOnlyConfiguration.dependencies
            .filterIsInstance<org.gradle.api.artifacts.ProjectDependency>()
            .map { it.dependencyProject.path }
            .filter { it.startsWith(":native-artifacts:sqlcipher-multiplatform-jdbc-") }
            .sorted()

        val expected = selectedLocalNativeModulePaths.sorted()

        check(localJdbcDependencies == expected) {
            "Expected local JDBC runtimeOnly dependency set $expected, but found $localJdbcDependencies"
        }

        logger.lifecycle("[PASS] verifySingleLocalJdbcArtifact: using local JDBC artifacts ${localJdbcDependencies.joinToString()}")
    }
}

tasks.register("verifyPublishedNativeArtifacts") {
    group = "verification"
    description = "Ensures published JVM runtime dependency set contains the expected SQLCipher native artifact coordinates."

    doLast {
        if (useLocalJdbcPlatforms) {
            throw GradleException(
                "Publishing is configured with local JDBC platform dependencies. " +
                    "Set -Psqlcipher.useLocalJdbcPlatforms=false for production publication."
            )
        }

        val jvmMainSourceSet = kotlin.sourceSets.getByName("jvmMain")
        val runtimeOnlyConfigurationName = jvmMainSourceSet.runtimeOnlyConfigurationName
        val runtimeOnlyConfiguration = configurations.getByName(runtimeOnlyConfigurationName)

        val declaredRemoteNativeDependencies = runtimeOnlyConfiguration.dependencies
            .filterIsInstance<org.gradle.api.artifacts.ExternalModuleDependency>()
            .mapNotNull { dep ->
                val group = dep.group ?: return@mapNotNull null
                val name = dep.name
                val version = dep.version ?: return@mapNotNull null
                "$group:$name:$version"
            }
            .filter { it.startsWith("io.github.s0d3s:sqlcipher-multiplatform-jdbc-") }
            .sorted()

        val expected = selectedRemoteNativeCoordinates.sorted()

        check(declaredRemoteNativeDependencies == expected) {
            "Expected published JVM runtimeOnly native coordinates $expected, but found $declaredRemoteNativeDependencies"
        }

        logger.lifecycle("[PASS] verifyPublishedNativeArtifacts: using published native artifacts ${declaredRemoteNativeDependencies.joinToString()}")
    }
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting {
            dependencies {
                if (useLocalJdbcPlatforms) {
                    implementation(project(":sqlcipher-multiplatform-jdbc-core"))
                    selectedLocalNativeModulePaths.forEach { modulePath ->
                        runtimeOnly(project(modulePath))
                    }
                } else {
                    implementation("io.github.s0d3s:sqlcipher-multiplatform-jdbc-core:$projectVersion")
                    selectedRemoteNativeCoordinates.forEach { coordinate ->
                        runtimeOnly(coordinate)
                    }
                }
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit5"))
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("net.zetetic:android-database-sqlcipher:4.5.4")
                implementation("androidx.sqlite:sqlite:2.2.0")
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    val nativePath = System.getProperty("sqlcipher.native.path")
    if (!nativePath.isNullOrBlank()) {
        systemProperty("sqlcipher.native.path", nativePath)
    }

    val nativeLibBasename = System.getProperty("sqlcipher.native.lib.basename")
    if (!nativeLibBasename.isNullOrBlank()) {
        systemProperty("sqlcipher.native.lib.basename", nativeLibBasename)
    }

    val integrationEnabled = System.getProperty("sqlcipher.integration.enabled")
    if (!integrationEnabled.isNullOrBlank()) {
        systemProperty("sqlcipher.integration.enabled", integrationEnabled)
    }
}

tasks.matching { it.name == "compileKotlinJvm" || it.name == "jvmProcessResources" }.configureEach {
    dependsOn("verifySingleLocalJdbcArtifact")
}

tasks.matching { it.name.startsWith("publish", ignoreCase = true) }.configureEach {
    dependsOn("verifyPublishedNativeArtifacts")
}

extensions.configure<PublishingExtension>("publishing") {
    publications.withType(MavenPublication::class.java).configureEach {
        when (name) {
            "kotlinMultiplatform" -> artifactId = "sqlcipher-multiplatform"
            "jvm" -> artifactId = "sqlcipher-multiplatform-jvm"
            else -> if (artifactId == project.name) {
                artifactId = "sqlcipher-multiplatform-$name"
            }
        }
    }
}

android {
    namespace = "io.github.s0d3s.sqlcipher.multiplatform.api"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
