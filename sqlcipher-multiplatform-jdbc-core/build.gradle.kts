import java.io.File

plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test-junit5"))
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeBuildType = providers.gradleProperty("native.buildType")
    .orElse(System.getenv("NATIVE_BUILD_TYPE") ?: if (isWindowsHost) "Release" else "RelWithDebInfo")
val nativeOutRoot = rootProject.layout.projectDirectory.dir("native-bridge/build/cmake/out").asFile

fun defaultNativePath(): String {
    return if (isWindowsHost) {
        File(nativeOutRoot, nativeBuildType.get()).absolutePath
    } else {
        nativeOutRoot.absolutePath
    }
}

tasks.test {
    useJUnitPlatform()

    val integrationEnabled = System.getProperty("sqlcipher.integration.enabled")
    if (!integrationEnabled.isNullOrBlank()) {
        systemProperty("sqlcipher.integration.enabled", integrationEnabled)
    }

    val nativePath = System.getProperty("sqlcipher.native.path")
    if (!nativePath.isNullOrBlank()) {
        systemProperty("sqlcipher.native.path", nativePath)
    }

    val nativeLibBasename = System.getProperty("sqlcipher.native.lib.basename")
        ?: providers.gradleProperty("native.lib.basename").orNull
    if (!nativeLibBasename.isNullOrBlank()) {
        systemProperty("sqlcipher.native.lib.basename", nativeLibBasename)
    }
}

tasks.register<Test>("nativeSmokeTest") {
    group = "verification"
    description = "Runs SQLCipher JNI integration smoke tests with native library loading enabled"
    dependsOn(":native-bridge:buildNative")

    useJUnitPlatform()
    include("**/SqlCipherIntegrationTest.class")

    val nativePath = System.getProperty("sqlcipher.native.path") ?: defaultNativePath()
    val nativeLibBasename = System.getProperty("sqlcipher.native.lib.basename")
        ?: providers.gradleProperty("native.lib.basename").orNull

    systemProperty("sqlcipher.integration.enabled", "true")
    systemProperty("sqlcipher.native.path", nativePath)
    if (!nativeLibBasename.isNullOrBlank()) {
        systemProperty("sqlcipher.native.lib.basename", nativeLibBasename)
    }
}
