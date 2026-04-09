package io.github.s0d3s.sqlcipher.multiplatform.samples.kmpbasic

import io.github.s0d3s.sqlcipher.multiplatform.api.SqlCipherDatabase
import io.github.s0d3s.sqlcipher.multiplatform.api.SqlCipherDatabaseFactory
import java.io.File
import java.sql.SQLException

fun main() {
    printRuntimeTargetInfo()

    val dbFile = File("sample-kmp-basic-encrypted.db").absoluteFile
    if (dbFile.exists()) {
        dbFile.delete()
    }

    val key = "kmp-basic-secret-v1".encodeToByteArray()

    try {
        SqlCipherDatabaseFactory.initialize()
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

        println("KMP basic sample verification passed")
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

private fun runCrudFlow(path: String, key: ByteArray) {
    val db = SqlCipherDatabaseFactory.open(path, key)
    try {
        println("[CRUD] Creating schema")
        db.execute("DROP TABLE IF EXISTS logs")
        db.execute("DROP TABLE IF EXISTS projects")
        db.execute(
            """
            CREATE TABLE projects(
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execute(
            """
            CREATE TABLE logs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL REFERENCES projects(id),
                message TEXT NOT NULL
            )
            """.trimIndent()
        )

        println("[CRUD] Inserting projects")
        db.execute("INSERT INTO projects(id, title, status) VALUES (1, 'Alpha', 'NEW')")
        db.execute("INSERT INTO projects(id, title, status) VALUES (2, 'Beta', 'NEW')")
        db.execute("INSERT INTO projects(id, title, status) VALUES (3, 'Gamma', 'NEW')")

        println("[CRUD] Inserting logs")
        db.execute("INSERT INTO logs(project_id, message) VALUES (1, 'CREATE')")
        db.execute("INSERT INTO logs(project_id, message) VALUES (1, 'UPDATE')")
        db.execute("INSERT INTO logs(project_id, message) VALUES (2, 'DELETE')")

        println("[CRUD] Updating/deleting records")
        db.execute("UPDATE projects SET status = 'DONE' WHERE id = 1")
        db.execute("DELETE FROM projects WHERE id = 3")

        println("[CRUD] Selecting project titles")
        val titles = db.querySingleColumn("SELECT title FROM projects ORDER BY id")
        println("[CRUD] Selected titles=$titles")
        check(titles == listOf("Alpha", "Beta")) { "Unexpected project titles: $titles" }

        println("[CRUD] Selecting project statuses")
        val statuses = db.querySingleColumn("SELECT status FROM projects ORDER BY id")
        println("[CRUD] Selected statuses=$statuses")
        check(statuses == listOf("DONE", "NEW")) { "Unexpected statuses: $statuses" }

        println("[CRUD] Selecting logs count")
        val logCount = db.querySingleColumn("SELECT COUNT(*) FROM logs").single()?.toIntOrNull()
        println("[CRUD] Selected logCount=$logCount")
        check(logCount == 3) { "Expected 3 log rows, got $logCount" }
    } finally {
        db.close()
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

private fun verifyCanReadWithCorrectKey(path: String, key: ByteArray) {
    val db = SqlCipherDatabaseFactory.open(path, key)
    try {
        val projectCount = db.querySingleColumn("SELECT COUNT(*) FROM projects").single()?.toIntOrNull()
        check(projectCount == 2) { "Expected 2 projects, got $projectCount" }

        val doneCount = db.querySingleColumn("SELECT COUNT(*) FROM projects WHERE status = 'DONE'")
            .single()?.toIntOrNull()
        check(doneCount == 1) { "Expected 1 DONE project, got $doneCount" }
    } finally {
        db.close()
    }
}

private fun verifyWrongKeyRejected(path: String) {
    val wrongKey = "kmp-basic-wrong-key".encodeToByteArray()
    try {
        println("[INFO] Intentionally checking wrong-key rejection; SQLCipher may emit native ERROR CORE logs here")
        var rejected = false
        try {
            SqlCipherDatabaseFactory.open(path, wrongKey).useAndClose { db ->
                db.querySingleColumn("SELECT COUNT(*) FROM projects")
            }
        } catch (_: SQLException) {
            rejected = true
        }

        check(rejected) { "Wrong key unexpectedly succeeded" }
    } finally {
        wrongKey.fill(0)
    }
}

private inline fun SqlCipherDatabase.useAndClose(block: (SqlCipherDatabase) -> Unit) {
    try {
        block(this)
    } finally {
        close()
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
