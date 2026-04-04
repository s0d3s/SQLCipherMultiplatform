package io.github.s0d3s.sqlcipher.multiplatform.api

interface SqlCipherDatabase {
    fun execute(sql: String)
    fun querySingleColumn(sql: String): List<String?>
    fun rekey(newKey: ByteArray)
    fun close()

    fun rekey(newKey: String) {
        val keyBytes = newKey.encodeToByteArray()
        try {
            rekey(keyBytes)
        } finally {
            keyBytes.fill(0)
        }
    }
}

expect object SqlCipherDatabaseFactory {
    /**
     * Performs platform-specific SQLCipher runtime initialization.
     *
     * - JVM: no-op
     * - Android: pass Android Context (as [Any]) once before first `open(...)`
     */
    fun initialize(platformContext: Any? = null)

    fun open(path: String, key: ByteArray): SqlCipherDatabase
    fun open(path: String, key: String): SqlCipherDatabase

    fun rekey(path: String, oldKey: ByteArray, newKey: ByteArray)
    fun rekey(path: String, oldKey: String, newKey: String)
}
