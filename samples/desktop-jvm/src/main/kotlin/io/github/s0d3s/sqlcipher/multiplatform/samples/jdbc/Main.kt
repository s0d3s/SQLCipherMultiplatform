package io.github.s0d3s.sqlcipher.multiplatform.samples.jdbc

import io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherJdbcProperties
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

fun main() {
    printRuntimeTargetInfo()

    val dbFile = File("sample-jdbc-encrypted.db").absoluteFile
    if (dbFile.exists()) {
        dbFile.delete()
    }

    val key = "jdbc-sample-secret-v1".encodeToByteArray()

    try {
        runCheck("CRUD flow") {
            runCrudFlow(dbFile.absolutePath, key)
        }
        runCheck("Encrypted-at-rest validation") {
            verifyEncryptionAtRest(dbFile, plaintextMarkers = listOf("Ada", "Grace", "Ship MVP"))
        }
        runCheck("Correct-key read validation") {
            verifyCanReadWithCorrectKey(dbFile.absolutePath, key)
        }
        runCheck("Wrong-key rejection") {
            verifyWrongKeyRejected(dbFile.absolutePath)
        }

        println("JDBC sample verification passed")
    } finally {
        key.fill(0)
    }
}

private inline fun runCheck(name: String, block: () -> Unit) {
    println("[TEST] $name")
    try {
        block()
        println("[PASS] $name")
    } catch (t: Throwable) {
        println("[FAIL] $name :: ${t.message}")
        throw t
    }
}

private fun runCrudFlow(dbPath: String, key: ByteArray) {
    openConnection(dbPath, key).use { connection ->
        connection.createStatement().use { st ->
            st.execute("DROP TABLE IF EXISTS tasks")
            st.execute("DROP TABLE IF EXISTS users")
            st.execute(
                """
                CREATE TABLE users(
                    id INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    age INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute(
                """
                CREATE TABLE tasks(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL REFERENCES users(id),
                    title TEXT NOT NULL,
                    done INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        connection.prepareStatement("INSERT INTO users(id, name, age) VALUES (?, ?, ?)").use { ps ->
            insertUser(ps, 1, "Ada", 31)
            insertUser(ps, 2, "Grace", 28)
            insertUser(ps, 3, "Linus", 40)
        }

        connection.prepareStatement("INSERT INTO tasks(user_id, title, done) VALUES (?, ?, ?)").use { ps ->
            insertTask(ps, 1, "Ship MVP", done = false)
            insertTask(ps, 1, "Write tests", done = false)
            insertTask(ps, 2, "Refactor docs", done = true)
        }

        connection.prepareStatement("UPDATE tasks SET done = 1 WHERE title = ?").use { ps ->
            ps.setString(1, "Write tests")
            val updated = ps.executeUpdate()
            check(updated == 1) { "Expected one updated row, got $updated" }
        }

        connection.prepareStatement("DELETE FROM tasks WHERE title = ?").use { ps ->
            ps.setString(1, "Refactor docs")
            val deleted = ps.executeUpdate()
            check(deleted == 1) { "Expected one deleted row, got $deleted" }
        }

        val openTasks = queryInt(connection, "SELECT COUNT(*) FROM tasks WHERE done = 0")
        check(openTasks == 1) { "Expected one open task, got $openTasks" }

        val doneTasks = queryInt(connection, "SELECT COUNT(*) FROM tasks WHERE done = 1")
        check(doneTasks == 1) { "Expected one completed task, got $doneTasks" }

        val taskTitles = queryStrings(connection, "SELECT title FROM tasks ORDER BY id")
        check(taskTitles == listOf("Ship MVP", "Write tests")) {
            "Unexpected task titles: $taskTitles"
        }
    }
}

private fun verifyEncryptionAtRest(dbFile: File, plaintextMarkers: List<String>) {
    val bytes = dbFile.readBytes()
    val sqliteHeader = "SQLite format 3\u0000".encodeToByteArray()

    check(bytes.size > sqliteHeader.size) { "Database file is unexpectedly small" }

    val header = bytes.copyOfRange(0, sqliteHeader.size)
    check(!header.contentEquals(sqliteHeader)) {
        "DB header matches plain SQLite format (expected encrypted SQLCipher file)"
    }

    val asLatin1 = bytes.toString(Charsets.ISO_8859_1)
    val leaked = plaintextMarkers.firstOrNull { marker -> asLatin1.contains(marker) }
    check(leaked == null) { "Detected plaintext marker in encrypted file: $leaked" }
}

private fun verifyCanReadWithCorrectKey(dbPath: String, key: ByteArray) {
    openConnection(dbPath, key).use { connection ->
        val cipherVersion = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA cipher_version").use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }

        check(!cipherVersion.isNullOrBlank()) { "PRAGMA cipher_version returned blank" }

        val users = queryInt(connection, "SELECT COUNT(*) FROM users")
        val tasks = queryInt(connection, "SELECT COUNT(*) FROM tasks")
        check(users == 3) { "Expected 3 users, got $users" }
        check(tasks == 2) { "Expected 2 tasks, got $tasks" }
    }
}

private fun verifyWrongKeyRejected(dbPath: String) {
    val wrongKey = "jdbc-wrong-key".encodeToByteArray()
    try {
        println("[INFO] Intentionally checking wrong-key rejection; SQLCipher may emit native ERROR CORE logs here")
        var rejected = false
        try {
            openConnection(dbPath, wrongKey).use { connection ->
                queryInt(connection, "SELECT COUNT(*) FROM users")
            }
        } catch (_: SQLException) {
            rejected = true
        }

        check(rejected) { "Wrong key unexpectedly succeeded" }
    } finally {
        wrongKey.fill(0)
    }
}

private fun openConnection(dbPath: String, key: ByteArray): Connection {
    val keyCopy = key.copyOf()
    val props = Properties().apply {
        put(SqlCipherJdbcProperties.KEY_BYTES, keyCopy)
    }

    return try {
        DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props)
    } finally {
        keyCopy.fill(0)
    }
}

private fun queryInt(connection: Connection, sql: String): Int {
    connection.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            check(rs.next()) { "No row returned for query: $sql" }
            return rs.getInt(1)
        }
    }
}

private fun queryStrings(connection: Connection, sql: String): List<String> {
    connection.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val values = mutableListOf<String>()
            while (rs.next()) {
                values += rs.getString(1)
            }
            return values
        }
    }
}

private fun insertUser(ps: java.sql.PreparedStatement, id: Int, name: String, age: Int) {
    ps.setInt(1, id)
    ps.setString(2, name)
    ps.setInt(3, age)
    val inserted = ps.executeUpdate()
    check(inserted == 1) { "Expected one inserted user row, got $inserted" }
}

private fun insertTask(ps: java.sql.PreparedStatement, userId: Int, title: String, done: Boolean) {
    ps.setInt(1, userId)
    ps.setString(2, title)
    ps.setInt(3, if (done) 1 else 0)
    val inserted = ps.executeUpdate()
    check(inserted == 1) { "Expected one inserted task row, got $inserted" }
}

private fun printRuntimeTargetInfo() {
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")
    val nativePath = System.getProperty("sqlcipher.native.path") ?: "<java.library.path>"
    val nativeLibBase = System.getProperty("sqlcipher.native.lib.basename") ?: "sqlcipher_jni"

    println("Target runtime -> os=$os arch=$arch")
    println("Native loading -> base=$nativeLibBase path=$nativePath")
}
