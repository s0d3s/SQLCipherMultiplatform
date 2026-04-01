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

fun defaultVcpkgTriplet(targetOs: String, targetArch: String): String = when (targetOs) {
    "windows" -> if (targetArch == "arm64") "arm64-windows" else "x64-windows"
    "linux" -> if (targetArch == "arm64") "arm64-linux" else "x64-linux"
    "macos" -> if (targetArch == "arm64") "arm64-osx" else "x64-osx"
    else -> "x64-windows"
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

val localVcpkgRoot = rootProject.layout.projectDirectory.dir("third_party/vcpkg").asFile
val defaultWindowsVcpkgRoot = envValue("NATIVE_DEFAULT_WINDOWS_VCPKG_ROOT")?.let(::file)
val gradleVcpkgRoot = providers.gradleProperty("native.vcpkgRoot").orNull?.let(::file)
val envVcpkgRoot = envValue("VCPKG_ROOT")?.let(::file)
val vcpkgRoot = when {
    gradleVcpkgRoot?.exists() == true -> gradleVcpkgRoot
    envVcpkgRoot?.exists() == true -> envVcpkgRoot
    localVcpkgRoot.exists() -> localVcpkgRoot
    isWindows && defaultWindowsVcpkgRoot?.exists() == true -> defaultWindowsVcpkgRoot
    else -> null
}
val vcpkgTriplet = providers.gradleProperty("native.vcpkgTriplet")
    .orElse(envValue("VCPKG_TARGET_TRIPLET") ?: defaultVcpkgTriplet(targetOs, targetArch))
    .get()

val gradleToolchainFile = providers.gradleProperty("native.cmakeToolchainFile").orNull?.let(::file)
val vcpkgToolchainFile = when {
    gradleToolchainFile?.exists() == true -> gradleToolchainFile
    vcpkgRoot != null -> vcpkgRoot.resolve("scripts/buildsystems/vcpkg.cmake").takeIf { it.exists() }
    else -> null
}

val opensslRootDir = providers.gradleProperty("native.opensslRoot")
    .orElse(envValue("OPENSSL_ROOT_DIR") ?: "")
    .orNull
    ?.takeIf { it.isNotBlank() }
val inferredOpenSslRootDir = vcpkgRoot?.resolve("installed/$vcpkgTriplet")?.takeIf { it.exists() }?.absolutePath

fun resolveNativeOutDir(): File {
    val outRoot = cmakeBuildDir.get().asFile.resolve("out")
    return if (isWindows) outRoot.resolve(buildType) else outRoot
}

val runtimeDependencyDirs = buildList {
    providers.gradleProperty("native.runtimeDepsDir").orNull?.let(::file)?.takeIf { it.exists() }?.let(::add)

    val effectiveOpenSslRoot = opensslRootDir ?: inferredOpenSslRootDir
    effectiveOpenSslRoot?.let { file(it).resolve("bin") }?.takeIf { it.exists() }?.let(::add)

    vcpkgRoot?.resolve("installed/$vcpkgTriplet/bin")?.takeIf { it.exists() }?.let(::add)
}

tasks.register("printNativeConfig") {
    group = "native"
    description = "Print native build configuration for current target"
    doLast {
        println("native.target.os=$targetOs")
        println("native.target.arch=$targetArch")
        println("native.buildType=$buildType")
        println("native.lib.basename=$nativeLibBasename")
        println("native.vcpkg.triplet=$vcpkgTriplet")
        println("native.vcpkg.root=${vcpkgRoot?.absolutePath ?: "<none>"}")
        println("native.openssl.root=${(opensslRootDir ?: inferredOpenSslRootDir) ?: "<none>"}")
        println("native.out.dir=${resolveNativeOutDir().absolutePath}")
    }
}

tasks.register<Exec>("configureNative") {
    group = "native"
    description = "Configure CMake for SQLCipher JNI bridge"

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

    if (vcpkgToolchainFile != null) {
        cmd += listOf(
            "-DCMAKE_TOOLCHAIN_FILE=${vcpkgToolchainFile.absolutePath.replace("\\", "/")}",
            "-DVCPKG_TARGET_TRIPLET=$vcpkgTriplet"
        )
    }

    val effectiveOpenSslRoot = opensslRootDir ?: inferredOpenSslRootDir
    if (!effectiveOpenSslRoot.isNullOrBlank()) {
        val normalized = effectiveOpenSslRoot.replace("\\", "/")
        val openSslDir = "$normalized/share/openssl"
        cmd += "-DOpenSSL_DIR=$openSslDir"
        cmd += "-DOPENSSL_ROOT_DIR=$normalized"
        cmd += "-DCMAKE_PREFIX_PATH=$normalized"
    }

    commandLine(cmd)
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

tasks.register<Copy>("copyRuntimeDependencies") {
    group = "native"
    description = "Copy OpenSSL/runtime dependencies next to JNI library output"
    onlyIf { isWindows }
    dependsOn("buildNative")

    from(runtimeDependencyDirs)
    include("libcrypto-3*.dll", "libssl-3*.dll", "legacy.dll", "zlib1.dll")
    into(resolveNativeOutDir())
}

if (isWindows) {
    tasks.named("buildNative") {
        finalizedBy("copyRuntimeDependencies")
    }
}

tasks.named("assemble") {
    dependsOn("buildNative")
}

tasks.register<Delete>("cleanNative") {
    delete(cmakeBuildDir)
}

tasks.named("clean") {
    dependsOn("cleanNative")
}
