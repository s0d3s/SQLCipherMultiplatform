package dev.boosted.sqlcipher.jdbc

import java.io.File

internal object NativeBridge {
    private const val DEFAULT_LIB_BASENAME = "sqlcipher_jni"
    private const val PROPERTY_NATIVE_PATH = "sqlcipher.native.path"
    private const val PROPERTY_NATIVE_LIB_BASENAME = "sqlcipher.native.lib.basename"
    private val IS_WINDOWS = System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    @Volatile
    private var loaded = false

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
            System.loadLibrary(libBasename)
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
    @JvmStatic external fun columnCount(statementHandle: Long): Int
    @JvmStatic external fun columnName(statementHandle: Long, columnIndex: Int): String
    @JvmStatic external fun columnText(statementHandle: Long, columnIndex: Int): String?
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
}
