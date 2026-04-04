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
        artifactId = "sqlcipher-multiplatform-jdbc-macos-x64"
    }
}

val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "x86_64", "amd64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch").lowercase()
}
val externalMacosX64PayloadDir = providers.gradleProperty("native.macosX64PayloadDir").orNull?.let(::file)
val nativeOutDir = project(":native-bridge").layout.buildDirectory.dir("cmake/out")
val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")

val prepareMacosX64NativeResources = tasks.register<Sync>("prepareMacosX64NativeResources") {
    into(generatedNativeResources.map { it.dir("META-INF/sqlcipher/native/macos-x64") })

    if (externalMacosX64PayloadDir?.exists() == true) {
        from(externalMacosX64PayloadDir) {
            include("*.dylib")
        }
    } else if (isMacHost && hostArch == "x64") {
        dependsOn(":native-bridge:buildNative")
        from(nativeOutDir) {
            include("*.dylib")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareMacosX64NativeResources)
    from(generatedNativeResources)
}

tasks.named<Jar>("jar") {
    dependsOn(jdbcJarTask)
    from({ zipTree(jdbcJarTask.get().archiveFile.get().asFile) })
}

tasks.register("verifyMacosX64NativePayload") {
    group = "verification"
    description = "Verifies macOS x64 native artifact payload contains JNI binary"
    dependsOn(tasks.named("jar"))

    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val hasJniDylib = zipTree(jarFile).matching {
            include("META-INF/sqlcipher/native/macos-x64/libsqlcipher_jni.dylib")
        }.files.isNotEmpty()

        check(hasJniDylib) {
            "macOS x64 native artifact does not contain libsqlcipher_jni.dylib. Ensure native payload directory is configured."
        }
    }
}
