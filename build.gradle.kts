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
    group = "io.github.s0d3s.sqlcipher.multiplatform"
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
                            name.set(provider { project.findProperty("POM_DEVELOPER_NAME")?.toString() ?: "Sergio" })
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
