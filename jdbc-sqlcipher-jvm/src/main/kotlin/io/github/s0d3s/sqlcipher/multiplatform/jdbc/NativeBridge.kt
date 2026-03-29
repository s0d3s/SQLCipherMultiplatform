package io.github.s0d3s.sqlcipher.multiplatform.jdbc

import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.UUID

internal object NativeBridge {
    private const val DEFAULT_LIB_BASENAME = "sqlcipher_jni"
    private const val PROPERTY_NATIVE_PATH = "sqlcipher.native.path"
    private const val PROPERTY_NATIVE_LIB_BASENAME = "sqlcipher.native.lib.basename"
    private const val NATIVE_RESOURCE_ROOT = "META-INF/sqlcipher/native"
    private val IS_WINDOWS = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    @Volatile
    private var loaded = false

    @Volatile
    private var extractedRuntimeDir: File? = null

    @Volatile
    private var cleanupHookRegistered = false

    @Synchronized
    fun load() {
        if (loaded) {
            return
        }

        val libBasename = System.getProperty(PROPERTY_NATIVE_LIB_BASENAME)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LIB_BASENAME

        val configuredPath = System.getProperty(PROPERTY_NATIVE_PATH)
        if (configuredPath.isNullOrBlank()) {
            loadFromClasspathOrSystem(libBasename)
        } else {
            val configured = File(configuredPath)
            val target = resolveTargetLibrary(configured, libBasename)

            if (target == null || !target.exists()) {
                throw UnsatisfiedLinkError(
                    "Native library not found under: ${configured.absolutePath}. " +
                        "Set -D$PROPERTY_NATIVE_PATH to a valid directory/file and optionally " +
                        "-D$PROPERTY_NATIVE_LIB_BASENAME to the JNI library basename."
                )
            }

            preloadWindowsDependencies(target)
            System.load(target.absolutePath)
        }

        loaded = true
    }

    private fun loadFromClasspathOrSystem(libBasename: String) {
        val platform = detectPlatformId()
        if (platform == null) {
            System.loadLibrary(libBasename)
            return
        }

        val manifestPath = "$NATIVE_RESOURCE_ROOT/$platform/manifest.properties"
        val manifest = readPropertiesResource(manifestPath)
        if (manifest == null) {
            System.loadLibrary(libBasename)
            return
        }

        val kind = manifest.getProperty("kind")?.trim()?.lowercase(Locale.ROOT)
        when (kind) {
            "real" -> {
                val extracted = extractNativePayload(platform, manifest, libBasename)
                preloadDependencies(extracted.dependencies)
                System.load(extracted.jniLibrary.absolutePath)
            }

            "stub" -> {
                val message = manifest.getProperty("message")
                    ?: "Native SQLCipher library for '$platform' is not bundled yet"
                throw UnsatisfiedLinkError(message)
            }

            else -> {
                throw UnsatisfiedLinkError(
                    "Unsupported manifest kind '$kind' in resource '$manifestPath'. " +
                        "Expected 'real' or 'stub'."
                )
            }
        }
    }

    @JvmStatic external fun open(path: String): Long
    @JvmStatic external fun key(handle: Long, key: String): Int
    @JvmStatic external fun keyBytes(handle: Long, key: ByteArray): Int
    @JvmStatic external fun rekeyBytes(handle: Long, key: ByteArray): Int
    @JvmStatic external fun close(handle: Long): Int
    @JvmStatic external fun exec(handle: Long, sql: String): Int
    @JvmStatic external fun prepare(handle: Long, sql: String): Long
    @JvmStatic external fun bindNull(statementHandle: Long, parameterIndex: Int): Int
    @JvmStatic external fun bindInt(statementHandle: Long, parameterIndex: Int, value: Int): Int
    @JvmStatic external fun bindLong(statementHandle: Long, parameterIndex: Int, value: Long): Int
    @JvmStatic external fun bindDouble(statementHandle: Long, parameterIndex: Int, value: Double): Int
    @JvmStatic external fun bindText(statementHandle: Long, parameterIndex: Int, value: String): Int
    @JvmStatic external fun bindBlob(statementHandle: Long, parameterIndex: Int, value: ByteArray?): Int
    @JvmStatic external fun clearBindings(statementHandle: Long): Int
    @JvmStatic external fun reset(statementHandle: Long): Int
    @JvmStatic external fun step(statementHandle: Long): Int
    @JvmStatic external fun changes(handle: Long): Int
    @JvmStatic external fun lastErrorCode(handle: Long): Int
    @JvmStatic external fun columnCount(statementHandle: Long): Int
    @JvmStatic external fun columnName(statementHandle: Long, columnIndex: Int): String
    @JvmStatic external fun columnDeclType(statementHandle: Long, columnIndex: Int): String?
    @JvmStatic external fun columnType(statementHandle: Long, columnIndex: Int): Int
    @JvmStatic external fun columnText(statementHandle: Long, columnIndex: Int): String?
    @JvmStatic external fun columnBlob(statementHandle: Long, columnIndex: Int): ByteArray?
    @JvmStatic external fun finalizeStmt(statementHandle: Long): Int

    private fun resolveTargetLibrary(configuredPath: File, libBasename: String): File? {
        if (!configuredPath.isDirectory) {
            return configuredPath
        }

        val mappedName = System.mapLibraryName(libBasename)
        val candidates = listOf(
            File(configuredPath, mappedName),
            File(configuredPath, "Release/$mappedName"),
            File(configuredPath, "RelWithDebInfo/$mappedName"),
            File(configuredPath, "Debug/$mappedName"),
            File(configuredPath, "out/$mappedName"),
            File(configuredPath, "out/Release/$mappedName"),
            File(configuredPath, "out/RelWithDebInfo/$mappedName"),
            File(configuredPath, "out/Debug/$mappedName")
        )

        return candidates.firstOrNull { it.exists() }
    }

    private fun preloadWindowsDependencies(nativeLibFile: File) {
        if (!IS_WINDOWS) {
            return
        }

        val parent = nativeLibFile.parentFile ?: return
        val dependencyCandidates = listOf(
            "zlib1.dll",
            "libcrypto-3-x64.dll",
            "libssl-3-x64.dll",
            "legacy.dll",
            "libcrypto-3.dll",
            "libssl-3.dll"
        )

        dependencyCandidates
            .map { File(parent, it) }
            .filter { it.exists() }
            .forEach { System.load(it.absolutePath) }
    }

    private fun preloadDependencies(files: List<File>) {
        files.filter { it.exists() }.forEach { System.load(it.absolutePath) }
    }

    private data class ExtractedPayload(
        val jniLibrary: File,
        val dependencies: List<File>
    )

    private fun extractNativePayload(platform: String, manifest: Properties, libBasename: String): ExtractedPayload {
        val resourcePrefix = manifest.getProperty("resourcePrefix")
            ?.takeIf { it.isNotBlank() }
            ?: "$NATIVE_RESOURCE_ROOT/$platform"

        val outputDir = extractedRuntimeDir ?: createExtractionDir(platform).also {
            extractedRuntimeDir = it
            registerCleanupHookIfNeeded()
        }

        val dependencies = manifest.getProperty("dependencies")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
            .mapNotNull { dependencyName ->
                val dependencyResourcePath = "$resourcePrefix/$dependencyName"
                if (resourceExists(dependencyResourcePath)) {
                    extractResource(dependencyResourcePath, outputDir.resolve(dependencyName))
                } else {
                    null
                }
            }

        val requestedName = System.mapLibraryName(libBasename)
        val fallbackName = System.mapLibraryName(DEFAULT_LIB_BASENAME)
        val jniName = when {
            resourceExists("$resourcePrefix/$requestedName") -> requestedName
            resourceExists("$resourcePrefix/$fallbackName") -> fallbackName
            else -> throw UnsatisfiedLinkError(
                "JNI library resource not found under '$resourcePrefix'. Tried '$requestedName' and '$fallbackName'."
            )
        }

        val jniLibrary = extractResource("$resourcePrefix/$jniName", outputDir.resolve(jniName))
        return ExtractedPayload(jniLibrary = jniLibrary, dependencies = dependencies)
    }

    private fun createExtractionDir(platform: String): File {
        val dir = File(
            File(System.getProperty("java.io.tmpdir"), "sqlcipher-native"),
            "$platform-${UUID.randomUUID()}"
        )
        check(dir.mkdirs() || dir.exists()) {
            "Unable to create temporary native extraction directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun registerCleanupHookIfNeeded() {
        if (cleanupHookRegistered) {
            return
        }

        // JVM native libraries are not unloadable in-process in a safe/public way.
        // We only do best-effort cleanup of extracted files on process shutdown.
        val cleanupThread = Thread {
            extractedRuntimeDir?.let { deleteRecursivelyQuietly(it) }
        }
        Runtime.getRuntime().addShutdownHook(cleanupThread)
        cleanupHookRegistered = true
    }

    private fun deleteRecursivelyQuietly(target: File) {
        if (!target.exists()) {
            return
        }

        target.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteRecursivelyQuietly(child)
            } else {
                child.delete()
            }
        }
        target.delete()
    }

    private fun readPropertiesResource(path: String): Properties? {
        return classLoader.getResourceAsStream(path)?.use { stream ->
            Properties().apply { load(stream) }
        }
    }

    private fun resourceExists(path: String): Boolean {
        return classLoader.getResource(path) != null
    }

    private fun extractResource(resourcePath: String, destination: File): File {
        classLoader.getResourceAsStream(resourcePath)?.use { input ->
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
            return destination
        }

        throw UnsatisfiedLinkError("Native resource '$resourcePath' was not found on classpath")
    }

    private val classLoader: ClassLoader
        get() = Thread.currentThread().contextClassLoader ?: NativeBridge::class.java.classLoader

    private fun detectPlatformId(): String? {
        val osName = System.getProperty("os.name")?.lowercase(Locale.ROOT).orEmpty()
        val osArch = System.getProperty("os.arch")?.lowercase(Locale.ROOT).orEmpty()

        val os = when {
            osName.contains("win") -> "windows"
            osName.contains("mac") || osName.contains("darwin") -> "macos"
            osName.contains("linux") -> "linux"
            else -> return null
        }

        val arch = when (osArch) {
            "x86_64", "amd64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }

        return "$os-$arch"
    }
}
