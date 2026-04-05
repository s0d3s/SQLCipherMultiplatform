package io.github.s0d3s.sqlcipher.multiplatform.api

import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SqlCipherDatabaseFactoryJvmIntegrationTest {

    private val createdPaths = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun cleanupTempFiles() {
        createdPaths.asReversed().forEach { path ->
            runCatching {
                if (java.nio.file.Files.isDirectory(path)) {
                    java.nio.file.Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach { it.deleteIfExists() }
                } else {
                    path.deleteIfExists()
                }
            }
        }
        createdPaths.clear()
    }

    @Test
    fun open_execute_query_rekey_shouldWork() {
        val tempDir = createTempDirectory("sqlcipher-kmp-test-")
        createdPaths.add(tempDir)
        val dbPath = tempDir.resolve("api-test.db")

        val initialKey = "initial-secret"
        val newKey = "rotated-secret"

        val db = SqlCipherDatabaseFactory.open(dbPath.toString(), initialKey)
        db.execute("CREATE TABLE IF NOT EXISTS t(v TEXT)")
        db.execute("DELETE FROM t")
        db.execute("INSERT INTO t(v) VALUES ('one'), ('two')")
        assertEquals(listOf("one", "two"), db.querySingleColumn("SELECT v FROM t ORDER BY v"))
        db.rekey(newKey)
        db.close()

        assertFails("Opening with old key should fail after rekey") {
            val oldKeyDb = SqlCipherDatabaseFactory.open(dbPath.toString(), initialKey)
            try {
                oldKeyDb.querySingleColumn("SELECT v FROM t")
            } finally {
                oldKeyDb.close()
            }
        }

        val reopened = SqlCipherDatabaseFactory.open(dbPath.toString(), newKey)
        assertEquals(listOf("one", "two"), reopened.querySingleColumn("SELECT v FROM t ORDER BY v"))
        reopened.close()

        assertTrue(java.nio.file.Files.exists(dbPath), "Database file should exist after lifecycle operations")
    }
}
