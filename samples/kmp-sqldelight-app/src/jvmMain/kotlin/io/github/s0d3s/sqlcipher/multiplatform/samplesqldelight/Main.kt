package io.github.s0d3s.sqlcipher.multiplatform.samplesqldelight

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherJdbcProperties
import io.github.s0d3s.sqlcipher.multiplatform.samplesqldelight.db.SampleDatabase
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

fun main() {
    printRuntimeTargetInfo()

    val dbFile = File("sample-kmp-sqldelight-encrypted.db").absoluteFile
    if (dbFile.exists()) {
        dbFile.delete()
    }

    val key = "kmp-sqldelight-secret-v1".encodeToByteArray()

    try {
        runCheck("CRUD flow") {
            runCrudFlow(dbFile.absolutePath, key)
        }
        runCheck("Encrypted-at-rest validation") {
            verifyEncryptionAtRest(dbFile, plaintextMarkers = listOf("Alpha", "Beta", "DELETE"))
        }
        runCheck("Correct-key read validation") {
            verifyCanReadWithCorrectKey(dbFile.absolutePath, key)
        }
        runCheck("Wrong-key rejection") {
            verifyWrongKeyRejected(dbFile.absolutePath)
        }

        println("KMP SQLDelight sample verification passed")
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
    val keyCopyForProps = key.copyOf()
    val properties = Properties().apply {
        put(SqlCipherJdbcProperties.KEY_BYTES, keyCopyForProps)
        put(SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT, false)
    }

    try {
        val driver = JdbcSqliteDriver(url = "jdbc:sqlcipher:$dbPath", properties = properties)
        driver.use { sqlDriver ->
            println("[CRUD] Creating schema")
            SampleDatabase.Schema.create(sqlDriver)
            val database = SampleDatabase(sqlDriver)
            val queries = database.crudSampleQueries

            println("[CRUD] Inserting users")
            queries.insertUser(id = 1, name = "Alpha", age = 30)
            queries.insertUser(id = 2, name = "Beta", age = 25)
            queries.insertUser(id = 3, name = "Gamma", age = 40)

            println("[CRUD] Inserting tasks")
            queries.insertTask(user_id = 1, title = "CREATE", done = 0)
            queries.insertTask(user_id = 1, title = "UPDATE", done = 0)
            queries.insertTask(user_id = 2, title = "DELETE", done = 1)

            println("[CRUD] Updating/deleting records")
            queries.markTaskDoneByTitle(title = "UPDATE")
            queries.deleteTaskByTitle(title = "DELETE")

            println("[CRUD] Selecting task titles")
            val titles = queries.allTaskTitles().executeAsList()
            println("[CRUD] Selected titles=$titles")
            check(titles == listOf("CREATE", "UPDATE")) { "Unexpected task titles: $titles" }

            println("[CRUD] Selecting open tasks count")
            val openTasks = queries.openTasksCount().executeAsOne()
            println("[CRUD] Selected openTasks=$openTasks")
            check(openTasks == 1L) { "Expected 1 open task, got $openTasks" }

            println("[CRUD] Selecting done tasks count")
            val doneTasks = queries.doneTasksCount().executeAsOne()
            println("[CRUD] Selected doneTasks=$doneTasks")
            check(doneTasks == 1L) { "Expected 1 done task, got $doneTasks" }

            println("[CRUD] Selecting users count")
            val usersCount = queries.usersCount().executeAsOne()
            println("[CRUD] Selected usersCount=$usersCount")
            check(usersCount == 3L) { "Expected 3 users, got $usersCount" }
        }
    } finally {
        keyCopyForProps.fill(0)
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
    val keyCopy = key.copyOf()
    val props = Properties().apply {
        put(SqlCipherJdbcProperties.KEY_BYTES, keyCopy)
    }

    try {
        DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props).use { connection ->
            connection.createStatement().use { st ->
                val cipherVersion = st.executeQuery("PRAGMA cipher_version").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
                check(!cipherVersion.isNullOrBlank()) { "PRAGMA cipher_version returned blank" }

                val users = st.executeQuery("SELECT COUNT(*) FROM users").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
                val tasks = st.executeQuery("SELECT COUNT(*) FROM tasks").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }

                check(users == 3) { "Expected 3 users, got $users" }
                check(tasks == 2) { "Expected 2 tasks, got $tasks" }
            }
        }
    } finally {
        keyCopy.fill(0)
    }
}

private fun verifyWrongKeyRejected(dbPath: String) {
    val wrongKey = "kmp-sqldelight-wrong-key".encodeToByteArray()
    val props = Properties().apply {
        put(SqlCipherJdbcProperties.KEY_BYTES, wrongKey)
    }

    try {
        println("[INFO] Intentionally checking wrong-key rejection; SQLCipher may emit native ERROR CORE logs here")
        var rejected = false
        try {
            DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props).use { connection ->
                connection.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM users").use { rs ->
                        if (rs.next()) {
                            rs.getInt(1)
                        }
                    }
                }
            }
        } catch (_: SQLException) {
            rejected = true
        }

        check(rejected) { "Wrong key unexpectedly succeeded" }
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
