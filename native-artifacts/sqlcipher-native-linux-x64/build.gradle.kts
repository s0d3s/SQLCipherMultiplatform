plugins {
    `java-library`
    id("maven-publish")
    id("signing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

val isLinuxHost = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "x86_64", "amd64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch").lowercase()
}
val externalLinuxX64PayloadDir = providers.gradleProperty("native.linuxX64PayloadDir").orNull?.let(::file)
val nativeOutDir = project(":native-bridge").layout.buildDirectory.dir("cmake/out")
val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")

val prepareLinuxX64NativeResources = tasks.register<Sync>("prepareLinuxX64NativeResources") {
    into(generatedNativeResources.map { it.dir("META-INF/sqlcipher/native/linux-x64") })

    if (externalLinuxX64PayloadDir?.exists() == true) {
        from(externalLinuxX64PayloadDir) {
            include("*.so")
        }
    } else if (isLinuxHost && hostArch == "x64") {
        dependsOn(":native-bridge:buildNative")
        from(nativeOutDir) {
            include("*.so")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareLinuxX64NativeResources)
    from(generatedNativeResources)
}

tasks.register("verifyLinuxX64NativePayload") {
    group = "verification"
    description = "Verifies Linux x64 native artifact payload contains JNI binary"
    dependsOn(tasks.named("jar"))

    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val hasJniSo = zipTree(jarFile).matching {
            include("META-INF/sqlcipher/native/linux-x64/libsqlcipher_jni.so")
        }.files.isNotEmpty()

        check(hasJniSo) {
            "Linux x64 native artifact does not contain libsqlcipher_jni.so. Ensure native payload directory is configured."
        }
    }
}
