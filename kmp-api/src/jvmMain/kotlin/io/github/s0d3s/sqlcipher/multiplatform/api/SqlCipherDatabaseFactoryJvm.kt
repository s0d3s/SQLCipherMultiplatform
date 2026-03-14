package io.github.s0d3s.sqlcipher.multiplatform.api

import io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherJdbcProperties
import io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherSecurityOperations
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

actual object SqlCipherDatabaseFactory {
    actual fun open(path: String, key: ByteArray): SqlCipherDatabase {
        Class.forName("io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherDriver")

        val keyBytes = key.copyOf()
        val props = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, keyBytes)
        }

        return try {
            val connection = DriverManager.getConnection("jdbc:sqlcipher:$path", props)
            JdbcSqlCipherDatabase(connection)
        } finally {
            keyBytes.fill(0)
        }
    }

    actual fun open(path: String, key: String): SqlCipherDatabase {
        val keyBytes = key.encodeToByteArray()
        return try {
            open(path, keyBytes)
        } finally {
            keyBytes.fill(0)
        }
    }

    actual fun rekey(path: String, oldKey: ByteArray, newKey: ByteArray) {
        val db = open(path, oldKey)
        try {
            db.rekey(newKey)
        } finally {
            db.close()
        }
    }

    actual fun rekey(path: String, oldKey: String, newKey: String) {
        val oldKeyBytes = oldKey.encodeToByteArray()
        val newKeyBytes = newKey.encodeToByteArray()
        try {
            rekey(path, oldKeyBytes, newKeyBytes)
        } finally {
            oldKeyBytes.fill(0)
            newKeyBytes.fill(0)
        }
    }
}

private class JdbcSqlCipherDatabase(
    private val connection: Connection
) : SqlCipherDatabase {

    override fun execute(sql: String) {
        val statement = connection.createStatement()
        try {
            statement.execute(sql)
        } finally {
            statement.close()
        }
    }

    override fun querySingleColumn(sql: String): List<String?> {
        val result = mutableListOf<String?>()
        val statement = connection.createStatement()
        try {
            val resultSet = statement.executeQuery(sql)
            try {
                while (resultSet.next()) {
                    result += resultSet.getString(1)
                }
            } finally {
                resultSet.close()
            }
        } finally {
            statement.close()
        }
        return result
    }

    override fun rekey(newKey: ByteArray) {
        val operations = connection as? SqlCipherSecurityOperations
            ?: throw UnsupportedOperationException("Active connection does not support SQLCipher rekey")
        operations.rekey(newKey)
    }

    override fun close() {
        connection.close()
    }
}
