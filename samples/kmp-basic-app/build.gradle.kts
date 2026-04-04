plugins {
    kotlin("multiplatform")
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
                implementation(project(":sqlcipher-multiplatform"))
            }
        }
        val jvmMain by getting
    }
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeBuildType = providers.gradleProperty("native.buildType")
    .orElse(System.getenv("NATIVE_BUILD_TYPE") ?: if (isWindowsHost) "Release" else "RelWithDebInfo")
val nativeLibBasename = providers.gradleProperty("native.lib.basename").orElse("sqlcipher_jni")
val nativeLibraryPath = providers.provider {
    val mappedName = System.mapLibraryName(nativeLibBasename.get())
    if (isWindowsHost) {
        project(":native-bridge").layout.buildDirectory.file("cmake/out/${nativeBuildType.get()}/$mappedName").get().asFile.absolutePath
    } else {
        project(":native-bridge").layout.buildDirectory.file("cmake/out/$mappedName").get().asFile.absolutePath
    }
}

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

tasks.register<JavaExec>("runJvmSample") {
    group = "application"
    description = "Runs basic KMP sample on top of SqlCipherDatabaseFactory"
    dependsOn(":native-bridge:buildNative", "jvmJar")

    mainClass.set("io.github.s0d3s.sqlcipher.multiplatform.samples.kmpbasic.MainKt")
    classpath(files(tasks.named("jvmJar"), jvmMainCompilation.runtimeDependencyFiles))

    jvmArgs("-Dsqlcipher.native.path=${nativeLibraryPath.get()}")
    jvmArgs("-Dsqlcipher.native.lib.basename=${nativeLibBasename.get()}")
}

tasks.register("run") {
    group = "application"
    description = "Alias for runJvmSample"
    dependsOn("runJvmSample")
}

tasks.register("verifySample") {
    group = "verification"
    description = "Runs basic KMP sample end-to-end checks (CRUD + encrypted-at-rest + wrong-key rejection)"
    dependsOn("runJvmSample")
}
