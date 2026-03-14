package io.github.s0d3s.sqlcipher.multiplatform.jdbc

import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLException
import java.util.Properties
import java.util.logging.Logger

class SqlCipherDriver : Driver {

    override fun connect(url: String?, info: Properties?): Connection? {
        if (url == null || !acceptsURL(url)) {
            return null
        }

        val dbPath = url.removePrefix(URL_PREFIX)
        if (dbPath.isBlank()) {
            throw SQLException("Database path is missing in URL: $url")
        }

        NativeBridge.load()

        val nativeHandle = NativeBridge.open(dbPath)
        try {
            applyKey(nativeHandle, info)
            SqlCipherSecurityDefaults.apply(nativeHandle)

            return SqlCipherConnectionProxy.create(nativeHandle)
        } catch (e: Throwable) {
            NativeBridge.close(nativeHandle)
            throw e
        } finally {
            if (shouldScrubKeyProperties(info)) {
                scrubKeyProperties(info)
            }
        }
    }

    override fun acceptsURL(url: String?): Boolean {
        return url?.startsWith(URL_PREFIX) == true
    }

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> {
        val keyProperty = DriverPropertyInfo(PROP_KEY, info?.getProperty(PROP_KEY)).apply {
            description = "SQLCipher key (PRAGMA key)"
            required = false
        }

        return arrayOf(keyProperty)
    }

    override fun getMajorVersion(): Int = 0

    override fun getMinorVersion(): Int = 1

    override fun jdbcCompliant(): Boolean = false

    override fun getParentLogger(): Logger {
        throw SQLFeatureNotSupportedException("Logging is not implemented in MVP")
    }

    private fun applyKey(nativeHandle: Long, info: Properties?) {
        val rawBytes = info?.get(PROP_KEY_BYTES)
        val keyBytes = when (rawBytes) {
            is ByteArray -> rawBytes.copyOf()
            is String -> rawBytes.encodeToByteArray()
            else -> {
                val keyString = info?.getProperty(PROP_KEY).orEmpty()
                if (keyString.isNotEmpty()) keyString.encodeToByteArray() else null
            }
        }?.takeIf { it.isNotEmpty() } ?: return

        try {
            NativeBridge.keyBytes(nativeHandle, keyBytes)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun scrubKeyProperties(info: Properties?) {
        if (info == null) {
            return
        }

        (info[PROP_KEY_BYTES] as? ByteArray)?.fill(0)
        info.remove(PROP_KEY_BYTES)

        if (info.getProperty(PROP_KEY) != null) {
            info.setProperty(PROP_KEY, "")
        }
    }

    private fun shouldScrubKeyProperties(info: Properties?): Boolean {
        val raw = info?.get(PROP_SCRUB_KEY_MATERIAL)
        val parsed = when (raw) {
            is Boolean -> raw
            is String -> raw.equals("true", ignoreCase = true)
            else -> info?.getProperty(PROP_SCRUB_KEY_MATERIAL)?.equals("true", ignoreCase = true)
        }

        return parsed ?: true
    }

    companion object {
        private const val URL_PREFIX = "jdbc:sqlcipher:"
        private const val PROP_KEY = SqlCipherJdbcProperties.KEY
        private const val PROP_KEY_BYTES = SqlCipherJdbcProperties.KEY_BYTES
        private const val PROP_SCRUB_KEY_MATERIAL = SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT

        init {
            DriverManager.registerDriver(SqlCipherDriver())
        }
    }
}
