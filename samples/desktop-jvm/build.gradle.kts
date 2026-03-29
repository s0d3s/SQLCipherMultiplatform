plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":jdbc-sqlcipher-jvm"))
}

application {
    mainClass.set("io.github.s0d3s.sqlcipher.multiplatform.samples.jdbc.MainKt")
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

tasks.named<JavaExec>("run") {
    dependsOn(":native-bridge:buildNative")
    jvmArgs("-Dsqlcipher.native.path=${nativeLibraryPath.get()}")
    jvmArgs("-Dsqlcipher.native.lib.basename=${nativeLibBasename.get()}")
}

tasks.register<JavaExec>("verifySample") {
    group = "verification"
    description = "Runs JDBC sample end-to-end checks (CRUD + encrypted-at-rest + wrong-key rejection)"
    dependsOn(":native-bridge:buildNative", "classes")

    mainClass.set(application.mainClass)
    classpath(sourceSets.main.get().runtimeClasspath)

    jvmArgs("-Dsqlcipher.native.path=${nativeLibraryPath.get()}")
    jvmArgs("-Dsqlcipher.native.lib.basename=${nativeLibBasename.get()}")
}
