plugins {
    kotlin("multiplatform")
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
            }
        }
        val jvmMain by getting
    }
}

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("runJvmSample") {
    group = "application"
    description = "Runs basic KMP sample on top of SqlCipherDatabaseFactory"
    dependsOn("jvmJar")

    mainClass.set("io.github.s0d3s.sqlcipher.multiplatform.samples.kmpbasic.MainKt")
    classpath(files(tasks.named("jvmJar"), jvmMainCompilation.runtimeDependencyFiles))
}

tasks.register("verifySample") {
    group = "verification"
    description = "Runs basic KMP sample end-to-end checks (CRUD + encrypted-at-rest + wrong-key rejection)"
    dependsOn("runJvmSample")
}
