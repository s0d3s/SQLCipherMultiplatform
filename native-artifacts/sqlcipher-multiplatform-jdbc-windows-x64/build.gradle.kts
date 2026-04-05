plugins {
    `java-library`
    id("maven-publish")
    id("signing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

val jdbcJarTask = project(":sqlcipher-multiplatform-jdbc-core").tasks.named<Jar>("jar")

extensions.configure<org.gradle.api.publish.PublishingExtension>("publishing") {
    publications.withType(org.gradle.api.publish.maven.MavenPublication::class.java).configureEach {
        artifactId = "sqlcipher-multiplatform-jdbc-windows-x64"
    }
}

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val nativeBuildType = providers.gradleProperty("native.buildType")
    .orElse(System.getenv("NATIVE_BUILD_TYPE") ?: if (isWindowsHost) "Release" else "RelWithDebInfo")
val nativeLibBasename = providers.gradleProperty("native.lib.basename")
    .orElse(System.getenv("NATIVE_LIB_BASENAME") ?: "sqlcipher_jni")
    .get()
val expectedWindowsJniDllName = "$nativeLibBasename.dll"
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
        dependsOn(":native-bridge:buildNative", ":native-bridge:copyRuntimeDependencies")
        from(nativeOutDir) {
            include("*.dll")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareWindowsNativeResources)
    from(generatedNativeResources)
}

tasks.named<Jar>("jar") {
    dependsOn(jdbcJarTask)
    from({ zipTree(jdbcJarTask.get().archiveFile.get().asFile) })
}

tasks.register("verifyWindowsNativePayload") {
    group = "verification"
    description = "Verifies Windows native artifact payload contains JNI binary"
    dependsOn(tasks.named("jar"))

    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val hasJniDll = zipTree(jarFile).matching {
            include("META-INF/sqlcipher/native/windows-x64/$expectedWindowsJniDllName")
        }.files.isNotEmpty()

        check(hasJniDll) {
            "Windows native artifact does not contain $expectedWindowsJniDllName. Ensure :native-bridge:buildNative succeeded and native.lib.basename is consistent."
        }
    }
}
