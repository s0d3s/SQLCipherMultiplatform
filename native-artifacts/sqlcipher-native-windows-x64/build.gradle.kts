plugins {
    `java-library`
    id("maven-publish")
    id("signing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeBuildType = providers.gradleProperty("native.buildType")
    .orElse(System.getenv("NATIVE_BUILD_TYPE") ?: if (isWindowsHost) "Release" else "RelWithDebInfo")
val externalWindowsPayloadDir = providers.gradleProperty("native.windowsPayloadDir").orNull?.let(::file)

val nativeOutDir = if (isWindowsHost) {
    project(":native-bridge").layout.buildDirectory.dir("cmake/out/${nativeBuildType.get()}")
} else {
    project(":native-bridge").layout.buildDirectory.dir("cmake/out")
}

val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")

val prepareWindowsNativeResources = tasks.register<Sync>("prepareWindowsNativeResources") {
    into(generatedNativeResources.map { it.dir("META-INF/sqlcipher/native/windows-x64") })

    if (externalWindowsPayloadDir?.exists() == true) {
        from(externalWindowsPayloadDir) {
            include("*.dll")
        }
    } else if (isWindowsHost) {
        dependsOn(":native-bridge:buildNative")
        from(nativeOutDir) {
            include("*.dll")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareWindowsNativeResources)
    from(generatedNativeResources)
}

tasks.register("verifyWindowsNativePayload") {
    group = "verification"
    description = "Verifies Windows native artifact payload contains JNI binary"
    dependsOn(tasks.named("jar"))

    doLast {
        if (!isWindowsHost) {
            logger.lifecycle("Skipping Windows native payload binary check on non-Windows host")
            return@doLast
        }

        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val hasJniDll = zipTree(jarFile).matching {
            include("META-INF/sqlcipher/native/windows-x64/sqlcipher_jni.dll")
        }.files.isNotEmpty()

        check(hasJniDll) {
            "Windows native artifact does not contain sqlcipher_jni.dll. Ensure :native-bridge:buildNative succeeded."
        }
    }
}
