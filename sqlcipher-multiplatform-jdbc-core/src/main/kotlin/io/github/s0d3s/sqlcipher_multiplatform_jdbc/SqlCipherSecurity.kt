package io.github.s0d3s.sqlcipher_multiplatform_jdbc

object SqlCipherJdbcProperties {
    const val KEY = "key"
    const val KEY_BYTES = "keyBytes"
    const val SCRUB_KEY_MATERIAL_AFTER_CONNECT = "scrubKeyMaterialAfterConnect"

    const val CIPHER_COMPATIBILITY = "sqlcipher.pragma.cipherCompatibility"
    const val CIPHER_PAGE_SIZE = "sqlcipher.pragma.cipherPageSize"
    const val KDF_ITER = "sqlcipher.pragma.kdfIter"
    const val CIPHER_HMAC_ALGORITHM = "sqlcipher.pragma.cipherHmacAlgorithm"
    const val CIPHER_KDF_ALGORITHM = "sqlcipher.pragma.cipherKdfAlgorithm"
}

interface SqlCipherSecurityOperations {
    fun rekey(newKey: ByteArray)
}

internal object SqlCipherSecurityDefaults {
    private const val DEFAULT_CIPHER_COMPATIBILITY = "4"
    private const val DEFAULT_CIPHER_PAGE_SIZE = "4096"
    private const val DEFAULT_KDF_ITER = "256000"
    private const val DEFAULT_CIPHER_HMAC_ALGORITHM = "HMAC_SHA512"
    private const val DEFAULT_CIPHER_KDF_ALGORITHM = "PBKDF2_HMAC_SHA512"

    fun apply(nativeHandle: Long, properties: java.util.Properties?) {
        val securePragmas = listOf(
            "PRAGMA cipher_compatibility = ${properties.valueOrDefault(SqlCipherJdbcProperties.CIPHER_COMPATIBILITY, DEFAULT_CIPHER_COMPATIBILITY)}",
            "PRAGMA cipher_page_size = ${properties.valueOrDefault(SqlCipherJdbcProperties.CIPHER_PAGE_SIZE, DEFAULT_CIPHER_PAGE_SIZE)}",
            "PRAGMA kdf_iter = ${properties.valueOrDefault(SqlCipherJdbcProperties.KDF_ITER, DEFAULT_KDF_ITER)}",
            "PRAGMA cipher_hmac_algorithm = ${properties.valueOrDefault(SqlCipherJdbcProperties.CIPHER_HMAC_ALGORITHM, DEFAULT_CIPHER_HMAC_ALGORITHM)}",
            "PRAGMA cipher_kdf_algorithm = ${properties.valueOrDefault(SqlCipherJdbcProperties.CIPHER_KDF_ALGORITHM, DEFAULT_CIPHER_KDF_ALGORITHM)}"
        )

        securePragmas.forEach { pragma ->
            NativeBridge.exec(nativeHandle, pragma)
        }
    }

    private fun java.util.Properties?.valueOrDefault(key: String, defaultValue: String): String {
        val value = this?.getProperty(key)?.trim().orEmpty()
        return if (value.isNotEmpty()) value else defaultValue
    }
}
