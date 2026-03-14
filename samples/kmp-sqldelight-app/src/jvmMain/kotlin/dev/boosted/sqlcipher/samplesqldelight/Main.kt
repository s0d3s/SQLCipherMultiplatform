package io.github.s0d3s.sqlcipher.multiplatform.samplesqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherJdbcProperties
import io.github.s0d3s.sqlcipher.multiplatform.samplesqldelight.db.SampleDatabase
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

fun main() {
    printRuntimeTargetInfo()

    val dbPath = File("sample-kmp-sqldelight-encrypted.db").absolutePath
    val dbFile = File(dbPath)
    if (dbFile.exists()) {
        dbFile.delete()
    }

    val key = "secret-passphrase-v1".encodeToByteArray()
    val keyCopyForProps = key.copyOf()

    Class.forName("io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherDriver")

    try {
        val props = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, keyCopyForProps)
            // SQLDelight JDBC driver may reuse one Properties object for future connections.
            // We keep key bytes until the driver is closed, then zeroize manually in finally.
            put(SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT, false)
        }

        val driver = JdbcSqliteDriver(
            url = "jdbc:sqlcipher:$dbPath",
            properties = props
        )

        driver.use { sqlDriver ->
            SampleDatabase.Schema.create(sqlDriver)
            val database = SampleDatabase(sqlDriver)
            val queries = database.teamStatsQueries

            queries.insertTeam(id = 1, name = "Lions")
            queries.insertTeam(id = 2, name = "Wolves")
            queries.insertTeam(id = 3, name = "Eagles")

            listOf(10L, 20L, 15L).forEach { points -> queries.insertScore(team_id = 1, points = points) }
            listOf(8L, 9L, 11L, 12L).forEach { points -> queries.insertScore(team_id = 2, points = points) }
            listOf(25L, 30L).forEach { points -> queries.insertScore(team_id = 3, points = points) }

            println("Team aggregations:")
            queries.teamAggregations().executeAsList().forEach { row ->
                println("  $row")
            }

            val global = queries.globalAggregations().executeAsOne()
            println("Global aggregations: $global")
        }

        verifyEncryptionAtRest(dbFile)
        verifyCanReadWithCorrectKey(dbPath, key)
        verifyWrongKeyRejected(dbPath)
        println("Security checks passed: encrypted at rest + wrong key rejected")
    } finally {
        key.fill(0)
        keyCopyForProps.fill(0)
    }
}

private fun verifyEncryptionAtRest(dbFile: File) {
    val bytes = dbFile.readBytes()
    val sqliteHeader = "SQLite format 3\u0000".encodeToByteArray()

    check(bytes.size > sqliteHeader.size) {
        "Database file is unexpectedly small"
    }

    val header = bytes.copyOfRange(0, sqliteHeader.size)
    check(!header.contentEquals(sqliteHeader)) {
        "Database header matches plain SQLite format. Expected SQLCipher-encrypted file"
    }

    val asLatin1 = bytes.toString(Charsets.ISO_8859_1)
    val plaintextMarkers = listOf("Lions", "Wolves", "Eagles")
    val leakedMarker = plaintextMarkers.firstOrNull { marker -> asLatin1.contains(marker) }
    check(leakedMarker == null) {
        "Detected plaintext marker in DB file: $leakedMarker"
    }

    println("At-rest verification: plaintext SQLite header and sample plaintext markers are absent")
}

private fun verifyCanReadWithCorrectKey(dbPath: String, key: ByteArray) {
    val keyCopy = key.copyOf()
    val props = Properties().apply {
        put(SqlCipherJdbcProperties.KEY_BYTES, keyCopy)
    }

    try {
        DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props).use { connection ->
            connection.createStatement().use { statement ->
                val cipherVersion = statement.executeQuery("PRAGMA cipher_version").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }

                check(!cipherVersion.isNullOrBlank()) {
                    "PRAGMA cipher_version returned empty value"
                }

                val rowCount = statement.executeQuery("SELECT COUNT(*) FROM scores").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }

                check(rowCount == 9) {
                    "Unexpected rows count with correct key: $rowCount"
                }

                println("Correct-key verification: cipher_version=$cipherVersion, rows=$rowCount")
            }
        }
    } finally {
        keyCopy.fill(0)
    }
}

private fun verifyWrongKeyRejected(dbPath: String) {
    val wrongKey = "totally-wrong-password".encodeToByteArray()
    val props = Properties().apply {
        put(SqlCipherJdbcProperties.KEY_BYTES, wrongKey)
    }

    try {
        var rejected = false
        try {
            DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM scores").use { rs ->
                        if (rs.next()) {
                            rs.getInt(1)
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            rejected = true
        }

        check(rejected) {
            "Wrong key unexpectedly succeeded"
        }
    } finally {
        wrongKey.fill(0)
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
