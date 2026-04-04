import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("multiplatform")
    id("com.android.library")
    id("maven-publish")
    id("signing")
}

fun currentDesktopClassifier(): Pair<String, String> {
    val osName = System.getProperty("os.name")
        ?.lowercase()
        ?: error("Missing os.name system property")
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported architecture: ${System.getProperty("os.arch")}")
    }

    val platform = when {
        osName.contains("windows") && arch == "x64" -> "windows-x64"
        osName.contains("linux") && arch == "x64" -> "linux-x64"
        osName.contains("linux") && arch == "arm64" -> "linux-arm64"
        osName.contains("mac") && arch == "x64" -> "macos-x64"
        osName.contains("mac") && arch == "arm64" -> "macos-arm64"
        else -> error("Unsupported OS/arch: $osName / $arch")
    }

    return platform to arch
}

val useLocalJdbcPlatforms = providers.gradleProperty("sqlcipher.useLocalJdbcPlatforms")
    .orElse("false")
    .map { it.equals("true", ignoreCase = true) }
    .get()

val projectVersion = rootProject.version.toString()

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

        val jvmMain by getting {
            dependencies {
                val (platform, _) = currentDesktopClassifier()

                if (useLocalJdbcPlatforms) {
                    runtimeOnly(project(":native-artifacts:sqlcipher-multiplatform-jdbc-$platform"))
                } else {
                    runtimeOnly("io.github.s0d3s:sqlcipher-multiplatform-jdbc-$platform:$projectVersion")
                }
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
