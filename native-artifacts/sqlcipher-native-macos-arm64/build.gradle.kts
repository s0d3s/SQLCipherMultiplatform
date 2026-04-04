plugins {
    `java-library`
    id("maven-publish")
    id("signing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "x86_64", "amd64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch").lowercase()
}
val externalMacosArm64PayloadDir = providers.gradleProperty("native.macosArm64PayloadDir").orNull?.let(::file)
val nativeOutDir = project(":native-bridge").layout.buildDirectory.dir("cmake/out")
val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")

val prepareMacosArm64NativeResources = tasks.register<Sync>("prepareMacosArm64NativeResources") {
    into(generatedNativeResources.map { it.dir("META-INF/sqlcipher/native/macos-arm64") })

    if (externalMacosArm64PayloadDir?.exists() == true) {
        from(externalMacosArm64PayloadDir) {
            include("*.dylib")
        }
    } else if (isMacHost && hostArch == "arm64") {
        dependsOn(":native-bridge:buildNative")
        from(nativeOutDir) {
            include("*.dylib")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareMacosArm64NativeResources)
    from(generatedNativeResources)
}

tasks.register("verifyMacosArm64NativePayload") {
    group = "verification"
    description = "Verifies macOS arm64 native artifact payload contains JNI binary"
    dependsOn(tasks.named("jar"))

    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val hasJniDylib = zipTree(jarFile).matching {
            include("META-INF/sqlcipher/native/macos-arm64/libsqlcipher_jni.dylib")
        }.files.isNotEmpty()

        check(hasJniDylib) {
            "macOS arm64 native artifact does not contain libsqlcipher_jni.dylib. Ensure native payload directory is configured."
        }
    }
}
