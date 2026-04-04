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
        artifactId = "sqlcipher-multiplatform-jdbc-linux-arm64"
    }
}

val isLinuxHost = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
val hostArch = when (System.getProperty("os.arch").lowercase()) {
    "x86_64", "amd64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch").lowercase()
}
val externalLinuxArm64PayloadDir = providers.gradleProperty("native.linuxArm64PayloadDir").orNull?.let(::file)
val nativeOutDir = project(":native-bridge").layout.buildDirectory.dir("cmake/out")
val generatedNativeResources = layout.buildDirectory.dir("generated/native-resources")

val prepareLinuxArm64NativeResources = tasks.register<Sync>("prepareLinuxArm64NativeResources") {
    into(generatedNativeResources.map { it.dir("META-INF/sqlcipher/native/linux-arm64") })

    if (externalLinuxArm64PayloadDir?.exists() == true) {
        from(externalLinuxArm64PayloadDir) {
            include("*.so")
        }
    } else if (isLinuxHost && hostArch == "arm64") {
        dependsOn(":native-bridge:buildNative")
        from(nativeOutDir) {
            include("*.so")
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(prepareLinuxArm64NativeResources)
    from(generatedNativeResources)
}

tasks.named<Jar>("jar") {
    dependsOn(jdbcJarTask)
    from({ zipTree(jdbcJarTask.get().archiveFile.get().asFile) })
}

tasks.register("verifyLinuxArm64NativePayload") {
    group = "verification"
    description = "Verifies Linux arm64 native artifact payload contains JNI binary"
    dependsOn(tasks.named("jar"))

    doLast {
        val jarFile = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val hasJniSo = zipTree(jarFile).matching {
            include("META-INF/sqlcipher/native/linux-arm64/libsqlcipher_jni.so")
        }.files.isNotEmpty()

        check(hasJniSo) {
            "Linux arm64 native artifact does not contain libsqlcipher_jni.so. Ensure native payload directory is configured."
        }
    }
}
