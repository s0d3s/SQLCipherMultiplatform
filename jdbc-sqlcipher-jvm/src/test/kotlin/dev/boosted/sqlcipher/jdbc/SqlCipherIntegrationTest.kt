package io.github.s0d3s.sqlcipher.multiplatform.jdbc

import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@EnabledIfSystemProperty(named = "sqlcipher.integration.enabled", matches = "true")
class SqlCipherIntegrationTest {

    @Test
    fun preparedStatement_batch_double_blob_shouldWork() {
        val dir = Files.createTempDirectory("sqlcipher-it-")
        val dbPath = dir.resolve("batch-test.db").toAbsolutePath().toString()

        openConnection(dbPath).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS metrics")
                st.execute(
                    """
                    CREATE TABLE metrics(
                        id INTEGER PRIMARY KEY,
                        name TEXT NOT NULL,
                        score REAL NOT NULL,
                        payload BLOB
                    )
                    """.trimIndent()
                )
            }

            connection.prepareStatement("INSERT INTO metrics(name, score, payload) VALUES (?, ?, ?)").use { ps ->
                ps.setString(1, "a")
                ps.setDouble(2, 1.5)
                ps.setBytes(3, byteArrayOf(1, 2, 3))
                ps.addBatch()

                ps.setString(1, "b")
                ps.setDouble(2, 2.5)
                ps.setBytes(3, byteArrayOf(9, 8))
                ps.addBatch()

                val counts = ps.executeBatch()
                assertContentEquals(intArrayOf(1, 1), counts)
            }

            connection.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*), CAST(SUM(score) AS INT), SUM(length(payload)) FROM metrics").use { rs ->
                    rs.next()
                    assertEquals(2, rs.getInt(1))
                    assertEquals(4, rs.getInt(2))
                    assertEquals(5, rs.getInt(3))
                }
            }
        }
    }

    @Test
    fun transaction_commit_rollback_shouldRespectAutoCommitMode() {
        val dir = Files.createTempDirectory("sqlcipher-it-")
        val dbPath = dir.resolve("tx-test.db").toAbsolutePath().toString()

        openConnection(dbPath).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS tx_test")
                st.execute("CREATE TABLE tx_test(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            }

            assertFailsWith<Exception> {
                connection.commit()
            }

            connection.autoCommit = false
            connection.createStatement().use { st ->
                st.execute("INSERT INTO tx_test(value) VALUES ('first')")
            }
            connection.rollback()

            assertEquals(0, queryCount(connection, "tx_test"))

            connection.createStatement().use { st ->
                st.execute("INSERT INTO tx_test(value) VALUES ('second')")
            }
            connection.commit()

            assertEquals(1, queryCount(connection, "tx_test"))

            connection.autoCommit = true
            connection.createStatement().use { st ->
                st.execute("INSERT INTO tx_test(value) VALUES ('third')")
            }

            assertEquals(2, queryCount(connection, "tx_test"))
        }
    }

    @Test
    fun resultSet_metadata_and_null_semantics_shouldWork() {
        val dir = Files.createTempDirectory("sqlcipher-it-")
        val dbPath = dir.resolve("meta-null-test.db").toAbsolutePath().toString()

        openConnection(dbPath).use { connection ->
            connection.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS meta_test")
                st.execute("CREATE TABLE meta_test(id INTEGER PRIMARY KEY, note TEXT, amount REAL)")
                st.execute("INSERT INTO meta_test(id, note, amount) VALUES (1, NULL, 3.5)")
            }

            connection.createStatement().use { st ->
                st.executeQuery("SELECT id, note, amount FROM meta_test").use { rs ->
                    val md = rs.metaData
                    assertEquals(3, md.columnCount)
                    assertEquals("id", md.getColumnName(1))
                    assertEquals("note", md.getColumnLabel(2))
                    assertEquals("amount", md.getColumnName(3))

                    rs.next()
                    assertEquals(1, rs.getInt("id"))
                    assertEquals(null, rs.getString("note"))
                    assertEquals(true, rs.wasNull())
                    assertEquals(3.5, rs.getDouble("amount"))
                    assertEquals(false, rs.wasNull())
                }
            }
        }
    }

    @Test
    fun keyBytes_and_rekey_flow_shouldWork() {
        val dir = Files.createTempDirectory("sqlcipher-it-")
        val dbPath = dir.resolve("rekey-test.db").toAbsolutePath().toString()

        val oldKey = "phase2-old-secret".encodeToByteArray()
        val newKey = "phase2-new-secret".encodeToByteArray()

        openConnectionWithKeyBytes(dbPath, oldKey).use { connection ->
            connection.createStatement().use { st ->
                st.execute("CREATE TABLE IF NOT EXISTS secure_data(id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                st.execute("INSERT INTO secure_data(value) VALUES ('row-1')")
            }

            (connection as SqlCipherSecurityOperations).rekey(newKey)
        }

        assertFailsWith<Exception> {
            openConnectionWithKeyBytes(dbPath, "wrong-old-secret".encodeToByteArray()).use { connection ->
                connection.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM secure_data").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        }

        openConnectionWithKeyBytes(dbPath, newKey).use { connection ->
            connection.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM secure_data").use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun keyBytes_property_shouldBeZeroized_afterConnect() {
        val dir = Files.createTempDirectory("sqlcipher-it-")
        val dbPath = dir.resolve("key-bytes-zeroize.db").toAbsolutePath().toString()

        val keyBytes = "phase2-zeroize-secret".encodeToByteArray()
        val props = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, keyBytes)
            setProperty(SqlCipherJdbcProperties.KEY, "legacy-string-key")
        }

        Class.forName("io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherDriver")
        DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props).use { connection ->
            connection.createStatement().use { st ->
                st.execute("CREATE TABLE IF NOT EXISTS t(id INTEGER PRIMARY KEY)")
            }
        }

        assertTrue(keyBytes.all { it == 0.toByte() })
        assertEquals(null, props[SqlCipherJdbcProperties.KEY_BYTES])
        assertEquals("", props.getProperty(SqlCipherJdbcProperties.KEY))
    }

    @Test
    fun keyBytes_property_shouldNotBeScrubbed_whenDisabled() {
        val dir = Files.createTempDirectory("sqlcipher-it-")
        val dbPath = dir.resolve("key-bytes-no-scrub.db").toAbsolutePath().toString()

        val keyBytes = "phase3-no-scrub-secret".encodeToByteArray()
        val keyBytesOriginal = keyBytes.copyOf()
        val props = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, keyBytes)
            setProperty(SqlCipherJdbcProperties.KEY, "legacy-string-key")
            put(SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT, false)
        }

        Class.forName("io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherDriver")
        DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props).use { connection ->
            connection.createStatement().use { st ->
                st.execute("CREATE TABLE IF NOT EXISTS t(id INTEGER PRIMARY KEY)")
            }
        }

        assertContentEquals(keyBytesOriginal, keyBytes)
        assertContentEquals(keyBytesOriginal, props[SqlCipherJdbcProperties.KEY_BYTES] as ByteArray)
        assertEquals("legacy-string-key", props.getProperty(SqlCipherJdbcProperties.KEY))
    }

    private fun openConnection(dbPath: String): Connection {
        Class.forName("io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherDriver")

        val props = Properties().apply {
            setProperty("key", "integration-secret")
        }

        return DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props)
    }

    private fun queryCount(connection: Connection, table: String): Int {
        connection.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                rs.next()
                return rs.getInt(1)
            }
        }
    }

    private fun openConnectionWithKeyBytes(dbPath: String, key: ByteArray): Connection {
        Class.forName("io.github.s0d3s.sqlcipher.multiplatform.jdbc.SqlCipherDriver")

        val keyCopy = key.copyOf()
        val props = Properties().apply {
            put(SqlCipherJdbcProperties.KEY_BYTES, keyCopy)
        }

        return DriverManager.getConnection("jdbc:sqlcipher:$dbPath", props)
    }
}
