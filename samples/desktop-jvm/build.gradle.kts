plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":kmp-api"))
}

application {
    mainClass.set("io.github.s0d3s.sqlcipher.multiplatform.sample.MainKt")
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

tasks.named<JavaExec>("run") {
    dependsOn(":native-bridge:buildNative")
    jvmArgs("-Dsqlcipher.native.path=${nativeOutDir.get().asFile.absolutePath}")
    jvmArgs("-Dsqlcipher.native.lib.basename=${nativeLibBasename.get()}")
}
