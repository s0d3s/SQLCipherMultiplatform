package io.github.s0d3s.sqlcipher.multiplatform.jdbc

object SqlCipherJdbcProperties {
    const val KEY = "key"
    const val KEY_BYTES = "keyBytes"
    const val SCRUB_KEY_MATERIAL_AFTER_CONNECT = "scrubKeyMaterialAfterConnect"
}

interface SqlCipherSecurityOperations {
    fun rekey(newKey: ByteArray)
}

internal object SqlCipherSecurityDefaults {
    private val securePragmas = listOf(
        "PRAGMA cipher_compatibility = 4",
        "PRAGMA cipher_page_size = 4096",
        "PRAGMA kdf_iter = 256000",
        "PRAGMA cipher_hmac_algorithm = HMAC_SHA512",
        "PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA512"
    )

    fun apply(nativeHandle: Long) {
        securePragmas.forEach { pragma ->
            NativeBridge.exec(nativeHandle, pragma)
        }
    }
}
