package io.github.s0d3s.sqlcipher.multiplatform.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqlCipherDatabaseCommonTest {

    @Test
    fun rekey_stringDelegatesToByteArrayAndScrubsCapturedBytes() {
        val fakeDb = CapturingSqlCipherDatabase()

        fakeDb.rekey("secret-key")

        val captured = assertNotNull(fakeDb.lastCapturedKeyBytes)
        assertTrue(captured.isNotEmpty(), "Captured key should not be empty")
        assertContentEquals(ByteArray(captured.size), captured, "String overload should scrub temporary key bytes")
    }

    private class CapturingSqlCipherDatabase : SqlCipherDatabase {
        var lastCapturedKeyBytes: ByteArray? = null

        override fun execute(sql: String) = Unit

        override fun querySingleColumn(sql: String): List<String?> = emptyList()

        override fun rekey(newKey: ByteArray) {
            // Keep exact reference to verify it is scrubbed by default String overload.
            lastCapturedKeyBytes = newKey
        }

        override fun close() = Unit
    }
}
