plugins {
    kotlin("multiplatform")
    id("app.cash.sqldelight")
}

val useLocalDependencies = providers.gradleProperty("sqlcipher.useLocal")
    .orElse(providers.gradleProperty("sqlcipher.useLocalJdbcPlatforms"))
    .orElse("true")
    .map { it.equals("true", ignoreCase = true) }
    .get()

val sqlcipherMultiplatformVersion = providers.gradleProperty("sqlcipher.version")
    .orElse(providers.environmentVariable("SQLCIPHER_MULTIPLATFORM_VERSION"))
    .orElse("latest.release")

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                if (useLocalDependencies) {
                    implementation(project(":sqlcipher-multiplatform"))
                } else {
                    implementation("io.github.s0d3s:sqlcipher-multiplatform:${sqlcipherMultiplatformVersion.get()}")
                }
                implementation("app.cash.sqldelight:runtime:2.0.2")
            }
        }

        val jvmMain by getting {
            dependencies {
                if (useLocalDependencies) {
                    implementation(project(":sqlcipher-multiplatform-jdbc-core"))
                } else {
                    implementation("io.github.s0d3s:sqlcipher-multiplatform-jdbc-core:${sqlcipherMultiplatformVersion.get()}")
                }
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
            }
        }
    }
}

sqldelight {
    databases {
        create("SampleDatabase") {
            packageName.set("io.github.s0d3s.sqlcipher.multiplatform.samplesqldelight.db")
        }
    }
}

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("runJvmSample") {
    group = "application"
    description = "Runs KMP SQLDelight sample on top of JDBC SQLCipher wrapper"
    dependsOn("jvmJar")

    mainClass.set("io.github.s0d3s.sqlcipher.multiplatform.samplesqldelight.MainKt")
    classpath(files(tasks.named("jvmJar"), jvmMainCompilation.runtimeDependencyFiles))
}

tasks.register("verifySample") {
    group = "verification"
    description = "Runs KMP SQLDelight sample end-to-end checks (CRUD + encrypted-at-rest + wrong-key rejection)"
    dependsOn("runJvmSample")
}
