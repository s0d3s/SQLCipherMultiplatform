plugins {
    kotlin("multiplatform")
    id("app.cash.sqldelight")
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:runtime:2.0.2")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":jdbc-sqlcipher-jvm"))
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

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeBuildType = providers.gradleProperty("native.buildType")
    .orElse(System.getenv("NATIVE_BUILD_TYPE") ?: if (isWindowsHost) "Release" else "RelWithDebInfo")
val nativeLibBasename = providers.gradleProperty("native.lib.basename").orElse("sqlcipher_jni")

val nativeOutDir = if (isWindowsHost) {
    project(":native-bridge").layout.buildDirectory.dir("cmake/out/${nativeBuildType.get()}")
} else {
    project(":native-bridge").layout.buildDirectory.dir("cmake/out")
}

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("runJvmSample") {
    group = "application"
    description = "Runs SQLDelight KMP JVM sample on top of JDBC SQLCipher wrapper"
    dependsOn(":native-bridge:buildNative", "jvmJar")

    mainClass.set("io.github.s0d3s.sqlcipher.multiplatform.samplesqldelight.MainKt")
    classpath(files(tasks.named("jvmJar"), jvmMainCompilation.runtimeDependencyFiles))

    jvmArgs("-Dsqlcipher.native.path=${nativeOutDir.get().asFile.absolutePath}")
    jvmArgs("-Dsqlcipher.native.lib.basename=${nativeLibBasename.get()}")
}

tasks.register("run") {
    group = "application"
    description = "Alias for runJvmSample"
    dependsOn("runJvmSample")
}
