import java.io.File

plugins {
    base
}

fun loadSimpleEnvFile(envFile: File): Map<String, String> {
    if (!envFile.exists()) return emptyMap()

    val keyRegex = Regex("^[A-Z_][A-Z0-9_]*$")
    val result = linkedMapOf<String, String>()

    envFile.forEachLine { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachLine

        val separator = line.indexOf('=')
        if (separator <= 0) return@forEachLine

        val key = line.substring(0, separator).trim()
        if (!keyRegex.matches(key)) return@forEachLine

        val valueRaw = line.substring(separator + 1).trim()

        // Skip multiline quoted values (for example armored keys in publishing configs)
        if ((valueRaw.startsWith('"') && !valueRaw.endsWith('"')) ||
            (valueRaw.startsWith('\'') && !valueRaw.endsWith('\''))
        ) {
            return@forEachLine
        }

        val value = valueRaw.removeSurrounding("\"").removeSurrounding("'")
        result[key] = value
    }

    return result
}

val cmakeBuildDir = layout.buildDirectory.dir("cmake")
val dotenv = loadSimpleEnvFile(rootProject.file(".env"))

fun envValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: dotenv[name]?.takeIf { it.isNotBlank() }

fun normalizeOs(name: String): String = when {
    name.startsWith("Windows", ignoreCase = true) -> "windows"
    name.startsWith("Mac", ignoreCase = true) || name.startsWith("Darwin", ignoreCase = true) -> "macos"
    name.startsWith("Linux", ignoreCase = true) -> "linux"
    else -> "unknown"
}

fun normalizeArch(name: String): String = when (name.lowercase()) {
    "x86_64", "amd64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> name.lowercase()
}

fun toConanOs(targetOs: String): String = when (targetOs) {
    "windows" -> "Windows"
    "linux" -> "Linux"
    "macos" -> "Macos"
    else -> error("Unsupported native.target.os='$targetOs'. Expected windows|linux|macos")
}

fun toConanArch(targetArch: String): String = when (targetArch) {
    "x64" -> "x86_64"
    "arm64" -> "armv8"
    else -> error("Unsupported native.target.arch='$targetArch'. Expected x64|arm64")
}

val targetOs = providers.gradleProperty("native.target.os")
    .orElse(envValue("NATIVE_TARGET_OS") ?: normalizeOs(System.getProperty("os.name")))
    .get()
val targetArch = providers.gradleProperty("native.target.arch")
    .orElse(envValue("NATIVE_TARGET_ARCH") ?: normalizeArch(System.getProperty("os.arch")))
    .get()

val isWindows = targetOs == "windows"
val defaultBuildType = if (isWindows) "Release" else "RelWithDebInfo"
val buildType = providers.gradleProperty("native.buildType")
    .orElse(envValue("NATIVE_BUILD_TYPE") ?: defaultBuildType)
    .get()
val nativeLibBasename = providers.gradleProperty("native.lib.basename")
    .orElse(envValue("NATIVE_LIB_BASENAME") ?: "sqlcipher_jni")
    .get()

val conanExecutable = providers.gradleProperty("native.conanExecutable")
    .orElse(envValue("NATIVE_CONAN_EXECUTABLE") ?: "conan")
    .get()
val conanProfile = providers.gradleProperty("native.conanProfile")
    .orElse(envValue("NATIVE_CONAN_PROFILE") ?: "default")
    .get()
val conanBuildProfile = providers.gradleProperty("native.conanBuildProfile")
    .orElse(envValue("NATIVE_CONAN_BUILD_PROFILE") ?: conanProfile)
    .get()
val conanOutputDir = providers.gradleProperty("native.conanOutputDir")
    .orElse(envValue("NATIVE_CONAN_OUTPUT_DIR") ?: layout.buildDirectory.dir("conan").get().asFile.absolutePath)
    .get()
val conanOutput = file(conanOutputDir)
fun resolveConanToolchainFile(): File {
    val explicit = providers.gradleProperty("native.conanToolchainFile")
        .orElse(envValue("NATIVE_CONAN_TOOLCHAIN_FILE") ?: "")
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?.let(::file)
    if (explicit != null) return explicit

    val candidates = listOf(
        conanOutput.resolve("build/$buildType/generators/conan_toolchain.cmake"),
        conanOutput.resolve("build/generators/conan_toolchain.cmake"),
        conanOutput.resolve("generators/conan_toolchain.cmake")
    )

    return candidates.firstOrNull { it.exists() }
        ?: conanOutput.resolve("build/$buildType/generators/conan_toolchain.cmake")
}

val conanDeployDir = conanOutput.resolve("deploy")
val conanSettingsOs = toConanOs(targetOs)
val conanSettingsArch = toConanArch(targetArch)


fun resolveNativeOutDir(): File {
    val outRoot = cmakeBuildDir.get().asFile.resolve("out")
    return outRoot.resolve(buildType)
}

tasks.register("printNativeConfig") {
    group = "native"
    description = "Print native build configuration for current target"
    doLast {
        println("native.target.os=$targetOs")
        println("native.target.arch=$targetArch")
        println("native.buildType=$buildType")
        println("native.lib.basename=$nativeLibBasename")
        println("native.conan.executable=$conanExecutable")
        println("native.conan.profile=$conanProfile")
        println("native.conan.buildProfile=$conanBuildProfile")
        println("native.conan.output=$conanOutputDir")
        println("native.conan.setting.os=$conanSettingsOs")
        println("native.conan.setting.arch=$conanSettingsArch")
        val resolvedToolchain = resolveConanToolchainFile()
        println("native.conan.toolchain=${resolvedToolchain.absolutePath}")
        println("native.conan.toolchain.exists=${resolvedToolchain.exists()}")
        println("native.out.dir=${resolveNativeOutDir().absolutePath}")
    }
}

tasks.register<Exec>("conanInstallNative") {
    group = "native"
    description = "Install Conan dependencies for SQLCipher JNI bridge"

    val cmd = mutableListOf(
        conanExecutable,
        "install",
        project.projectDir.absolutePath,
        "--output-folder", conanOutput.absolutePath,
        "--build", "missing",
        "--deployer", "full_deploy",
        "--deployer-folder", conanDeployDir.absolutePath,
        "-pr:h", conanProfile,
        "-pr:b", conanBuildProfile,
        "-s:h", "os=$conanSettingsOs",
        "-s:h", "arch=$conanSettingsArch",
        "-s:h", "build_type=$buildType",
        "-s:b", "build_type=$buildType"
    )

    commandLine(cmd)
}

tasks.register<Exec>("configureNative") {
    group = "native"
    description = "Configure CMake for SQLCipher JNI bridge"
    dependsOn("conanInstallNative")

    val cmd = mutableListOf(
        "cmake",
        "-S", project.projectDir.absolutePath,
        "-B", cmakeBuildDir.get().asFile.absolutePath
    )

    cmd += "-DSQLCIPHER_NATIVE_LIB_BASENAME=$nativeLibBasename"
    cmd += "-DSQLCIPHER_TARGET_OS=$targetOs"
    cmd += "-DSQLCIPHER_TARGET_ARCH=$targetArch"

    if (!isWindows) {
        cmd += "-DCMAKE_BUILD_TYPE=$buildType"
    }

    if (targetOs == "macos") {
        cmd += "-DCMAKE_OSX_ARCHITECTURES=${if (targetArch == "arm64") "arm64" else "x86_64"}"
    }

    doFirst {
        val resolvedToolchain = resolveConanToolchainFile()
        check(resolvedToolchain.exists()) {
            "Conan toolchain file not found: ${resolvedToolchain.absolutePath}. " +
                "Ensure Conan 2 is installed and profile is initialized (conan profile detect --force)."
        }

        commandLine(cmd + "-DCMAKE_TOOLCHAIN_FILE=${resolvedToolchain.absolutePath.replace("\\", "/")}")
    }
}

tasks.register<Exec>("buildNative") {
    group = "native"
    description = "Build SQLCipher JNI bridge"
    dependsOn("configureNative")

    val cmd = mutableListOf(
        "cmake",
        "--build",
        cmakeBuildDir.get().asFile.absolutePath
    )

    if (isWindows) {
        cmd += listOf("--config", buildType)
    }

    commandLine(cmd)
}

tasks.register<Delete>("cleanNative") {
    delete(cmakeBuildDir)
    delete(layout.buildDirectory.dir("conan"))
}

tasks.named("clean") {
    dependsOn("cleanNative")
}
