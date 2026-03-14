package dev.boosted.sqlcipher.api

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
    fun open(path: String, key: ByteArray): SqlCipherDatabase
    fun open(path: String, key: String): SqlCipherDatabase

    fun rekey(path: String, oldKey: ByteArray, newKey: ByteArray)
    fun rekey(path: String, oldKey: String, newKey: String)
}
