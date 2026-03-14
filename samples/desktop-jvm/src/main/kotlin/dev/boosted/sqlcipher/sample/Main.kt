package io.github.s0d3s.sqlcipher.multiplatform.sample

import io.github.s0d3s.sqlcipher.multiplatform.api.SqlCipherDatabaseFactory
import java.sql.DriverManager
import java.util.Properties

fun main() {
    printRuntimeTargetInfo()

    val dbPath = "sample-encrypted.db"
    val key = "secret-passphrase"

    Class.forName("io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherDriver")

    val connection = DriverManager.getConnection(
        "jdbc:sqlcipher:$dbPath",
        Properties().apply { setProperty("key", key) }
    )

    connection.use { conn ->
        conn.createStatement().use { statement ->
            statement.execute("CREATE TABLE IF NOT EXISTS notes(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            statement.execute("DELETE FROM notes")
        }

        conn.prepareStatement("INSERT INTO notes(value) VALUES (?)").use { ps ->
            listOf("hello-sqlcipher", "kmp-jdbc-wrapper", "prepared-statement").forEach { value ->
                ps.setString(1, value)
                ps.executeUpdate()
            }
        }

        conn.prepareStatement("SELECT value FROM notes WHERE value LIKE ? ORDER BY id").use { ps ->
            ps.setString(1, "%sqlcipher%")
            ps.executeQuery().use { rs ->
                val rows = mutableListOf<String?>()
                while (rs.next()) {
                    rows += rs.getString(1)
                }
                println("Prepared query rows: $rows")
            }
        }
    }

    val db = SqlCipherDatabaseFactory.open(path = dbPath, key = key)

    try {
        val values = db.querySingleColumn("SELECT value FROM notes ORDER BY id")
        println("KMP API rows: $values")

        db.rekey("secret-passphrase-v2")
        println("Rekey completed")
    } finally {
        db.close()
    }
}

private fun printRuntimeTargetInfo() {
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    val nativePath = System.getProperty("sqlcipher.native.path") ?: "<java.library.path>"
    val nativeLibBase = System.getProperty("sqlcipher.native.lib.basename") ?: "sqlcipher_jni"

    println("Target runtime -> os=$os arch=$arch")
    println("Native loading -> base=$nativeLibBase path=$nativePath")
}
