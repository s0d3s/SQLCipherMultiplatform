package io.github.s0d3s.sqlcipher_multiplatform_jdbc

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Savepoint
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types

private const val SQLITE_ROW = 100
private const val SQLITE_DONE = 101
private const val SQLITE_INTEGER = 1
private const val SQLITE_FLOAT = 2
private const val SQLITE_BLOB = 4
private const val SQLITE_NULL = 5

private const val SQLITE_CONSTRAINT = 19
private const val SQLITE_BUSY = 5
private const val SQLITE_LOCKED = 6
private const val SQLITE_READONLY = 8
private const val SQLITE_AUTH = 23
private const val SQLITE_PERM = 3
private const val SQLITE_CANTOPEN = 14
private const val SQLITE_IOERR = 10
private const val SQLITE_NOTADB = 26
private const val SQLITE_CORRUPT = 11
private const val SQLITE_FULL = 13
private const val SQLITE_NOMEM = 7
private const val SQLITE_MISUSE = 21

internal object SqlCipherConnectionProxy {
    fun create(nativeHandle: Long): Connection {
        val state = ConnectionState(nativeHandle = nativeHandle)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            SqlCipherConnectionProxy::class.java.classLoader,
            arrayOf(Connection::class.java, SqlCipherSecurityOperations::class.java),
            ConnectionHandler(state)
        ) as Connection
    }
}

private data class ConnectionState(
    val nativeHandle: Long,
    var closed: Boolean = false,
    var autoCommit: Boolean = true,
    var savepointCounter: Int = 0
)

private data class SqlCipherSavepoint(
    private val savepointId: Int,
    private val savepointName: String?
) : Savepoint {
    override fun getSavepointId(): Int {
        if (savepointName != null) throw SQLException("Named savepoint does not have numeric id")
        return savepointId
    }

    override fun getSavepointName(): String {
        return savepointName ?: throw SQLException("Unnamed savepoint does not have name")
    }

    fun sqlName(): String = savepointName ?: "SP_$savepointId"
}

private class ConnectionHandler(
    private val state: ConnectionState
) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (method.name) {
            "createStatement" -> {
                ensureOpen()
                SqlCipherStatementProxy.create(state, proxy as Connection)
            }

            "prepareStatement" -> {
                ensureOpen()
                val sql = arguments.firstOrNull() as? String ?: throw SQLException("prepareStatement requires SQL string")
                SqlCipherPreparedStatementProxy.create(state, proxy as Connection, sql)
            }

            "close" -> {
                closeConnection()
                null
            }

            "isClosed" -> state.closed
            "setAutoCommit" -> {
                ensureOpen()
                setAutoCommit(arguments[0] as Boolean)
                null
            }

            "getAutoCommit" -> state.autoCommit
            "commit" -> {
                ensureOpen()
                commitTransaction()
                null
            }

            "rollback" -> {
                ensureOpen()
                if (arguments.isEmpty()) rollbackTransaction() else rollbackToSavepoint(arguments[0] as Savepoint)
                null
            }

            "setSavepoint" -> {
                ensureOpen()
                if (arguments.isEmpty()) setSavepoint(null) else setSavepoint(arguments[0] as String)
            }

            "releaseSavepoint" -> {
                ensureOpen()
                releaseSavepoint(arguments[0] as Savepoint)
                null
            }

            "rekey" -> {
                ensureOpen()
                val newKey = arguments.firstOrNull() as? ByteArray ?: throw SQLException("rekey requires ByteArray key")
                rekey(newKey)
                null
            }

            "clearWarnings" -> null
            "getWarnings" -> null
            "isValid" -> !state.closed
            "nativeSQL" -> arguments[0] as String
            "unwrap" -> unwrap(proxy, arguments[0] as Class<*>)
            "isWrapperFor" -> (arguments[0] as Class<*>).isInstance(proxy)
            "toString" -> "SqlCipherConnection(native=${state.nativeHandle}, closed=${state.closed})"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> unsupported(method.name)
        }
    }

    private fun closeConnection() {
        if (state.closed) return
        NativeBridge.close(state.nativeHandle)
        state.closed = true
    }

    private fun ensureOpen() {
        if (state.closed) throw SQLException("Connection is closed")
    }

    private fun setAutoCommit(newValue: Boolean) {
        if (state.autoCommit == newValue) return
        safeExec(state.nativeHandle, if (newValue) "COMMIT" else "BEGIN IMMEDIATE")
        state.autoCommit = newValue
    }

    private fun commitTransaction() {
        if (state.autoCommit) throw SQLException("Cannot commit while autoCommit=true")
        safeExec(state.nativeHandle, "COMMIT")
        safeExec(state.nativeHandle, "BEGIN IMMEDIATE")
    }

    private fun rollbackTransaction() {
        if (state.autoCommit) throw SQLException("Cannot rollback while autoCommit=true")
        safeExec(state.nativeHandle, "ROLLBACK")
        safeExec(state.nativeHandle, "BEGIN IMMEDIATE")
    }

    private fun setSavepoint(name: String?): Savepoint {
        if (state.autoCommit) throw SQLException("Cannot create savepoint while autoCommit=true")
        val savepoint = SqlCipherSavepoint(++state.savepointCounter, name?.takeIf { it.isNotBlank() })
        safeExec(state.nativeHandle, "SAVEPOINT ${escapeIdentifier(savepoint.sqlName())}")
        return savepoint
    }

    private fun rollbackToSavepoint(savepoint: Savepoint) {
        if (state.autoCommit) throw SQLException("Cannot rollback(savepoint) while autoCommit=true")
        val sqlName = toSqlCipherSavepoint(savepoint).sqlName()
        safeExec(state.nativeHandle, "ROLLBACK TO SAVEPOINT ${escapeIdentifier(sqlName)}")
    }

    private fun releaseSavepoint(savepoint: Savepoint) {
        if (state.autoCommit) throw SQLException("Cannot releaseSavepoint while autoCommit=true")
        val sqlName = toSqlCipherSavepoint(savepoint).sqlName()
        safeExec(state.nativeHandle, "RELEASE SAVEPOINT ${escapeIdentifier(sqlName)}")
    }

    private fun toSqlCipherSavepoint(savepoint: Savepoint): SqlCipherSavepoint {
        return savepoint as? SqlCipherSavepoint ?: throw SQLException("Savepoint is not created by this connection")
    }

    private fun rekey(newKey: ByteArray) {
        if (newKey.isEmpty()) throw SQLException("Rekey key cannot be empty")
        val keyCopy = newKey.copyOf()
        try {
            NativeBridge.rekeyBytes(state.nativeHandle, keyCopy)
        } catch (e: SQLException) {
            throw withSqliteErrorMapping(e, state.nativeHandle)
        } finally {
            keyCopy.fill(0)
        }
    }
}

private object SqlCipherStatementProxy {
    fun create(state: ConnectionState, connection: Connection): Statement {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
            StatementHandler(state, connection)
        ) as Statement
    }
}

private class StatementHandler(
    private val state: ConnectionState,
    private val connection: Connection
) : InvocationHandler {
    private var closed = false
    private var currentResultSet: ResultSet? = null
    private var lastUpdateCount = -1

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (method.name) {
            "execute" -> executeSql(arguments[0] as String)
            "executeUpdate" -> executeUpdate(arguments[0] as String)
            "executeQuery" -> executeQuery(arguments[0] as String)
            "getResultSet" -> currentResultSet
            "getUpdateCount" -> if (currentResultSet == null) lastUpdateCount else -1
            "close" -> {
                closeStatement()
                null
            }

            "isClosed" -> closed
            "getConnection" -> connection
            "getWarnings", "clearWarnings" -> null
            "unwrap" -> unwrap(proxy, arguments[0] as Class<*>)
            "isWrapperFor" -> (arguments[0] as Class<*>).isInstance(proxy)
            "toString" -> "SqlCipherStatement(closed=$closed)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> unsupported(method.name)
        }
    }

    private fun executeSql(sql: String): Boolean {
        ensureOpen()
        return if (looksLikeQuery(sql)) {
            currentResultSet?.close()
            currentResultSet = executeQuery(sql)
            lastUpdateCount = -1
            true
        } else {
            currentResultSet?.close()
            currentResultSet = null
            safeExec(state.nativeHandle, sql)
            lastUpdateCount = NativeBridge.changes(state.nativeHandle)
            false
        }
    }

    private fun executeUpdate(sql: String): Int {
        ensureOpen()
        if (looksLikeQuery(sql)) throw SQLException("executeUpdate cannot be used for query SQL")
        currentResultSet?.close()
        currentResultSet = null
        safeExec(state.nativeHandle, sql)
        lastUpdateCount = NativeBridge.changes(state.nativeHandle)
        return lastUpdateCount
    }

    private fun executeQuery(sql: String): ResultSet {
        ensureOpen()
        if (!looksLikeQuery(sql)) throw SQLException("executeQuery requires a query SQL")
        val stmtHandle = try {
            NativeBridge.prepare(state.nativeHandle, sql)
        } catch (e: SQLException) {
            throw withSqliteErrorMapping(e, state.nativeHandle)
        }
        return SqlCipherResultSetProxy.create(
            statementHandle = stmtHandle,
            nativeHandle = state.nativeHandle,
            onClose = { NativeBridge.finalizeStmt(stmtHandle) }
        )
    }

    private fun closeStatement() {
        if (closed) return
        currentResultSet?.close()
        currentResultSet = null
        closed = true
    }

    private fun ensureOpen() {
        if (closed) throw SQLException("Statement is closed")
        if (state.closed) throw SQLException("Connection is closed")
    }
}

private object SqlCipherPreparedStatementProxy {
    fun create(state: ConnectionState, connection: Connection, sql: String): PreparedStatement {
        val stmtHandle = try {
            NativeBridge.prepare(state.nativeHandle, sql)
        } catch (e: SQLException) {
            throw withSqliteErrorMapping(e, state.nativeHandle)
        }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
            PreparedStatementHandler(state, connection, stmtHandle, sql)
        ) as PreparedStatement
    }
}

private class PreparedStatementHandler(
    private val state: ConnectionState,
    private val connection: Connection,
    private val statementHandle: Long,
    private val sql: String
) : InvocationHandler {
    private var closed = false
    private var currentResultSet: ResultSet? = null
    private var lastUpdateCount = -1
    private val querySql = looksLikeQuery(sql)
    private val currentBindings = linkedMapOf<Int, Any?>()
    private val batchBindings = mutableListOf<Map<Int, Any?>>()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (method.name) {
            "execute" -> if (arguments.isEmpty()) executePrepared() else unsupported(method.name)
            "executeUpdate" -> if (arguments.isEmpty()) executePreparedUpdate() else unsupported(method.name)
            "executeQuery" -> if (arguments.isEmpty()) executePreparedQuery() else unsupported(method.name)

            "setString" -> { setBinding(arguments[0] as Int, arguments[1] as String?); null }
            "setInt" -> { setBinding(arguments[0] as Int, arguments[1] as Int); null }
            "setLong" -> { setBinding(arguments[0] as Int, arguments[1] as Long); null }
            "setNull" -> { setBinding(arguments[0] as Int, null); null }
            "setObject" -> { setBinding(arguments[0] as Int, arguments[1]); null }
            "setBoolean" -> { setBinding(arguments[0] as Int, if (arguments[1] as Boolean) 1 else 0); null }
            "setShort" -> { setBinding(arguments[0] as Int, (arguments[1] as Short).toInt()); null }
            "setByte" -> { setBinding(arguments[0] as Int, (arguments[1] as Byte).toInt()); null }
            "setDouble" -> { setBinding(arguments[0] as Int, arguments[1] as Double); null }
            "setFloat" -> { setBinding(arguments[0] as Int, arguments[1] as Float); null }
            "setBytes" -> { setBinding(arguments[0] as Int, arguments[1] as ByteArray?); null }
            "setBigDecimal" -> { setBinding(arguments[0] as Int, arguments[1] as BigDecimal?); null }

            "addBatch" -> { addBatch(); null }
            "executeBatch" -> executeBatch()
            "clearBatch" -> { clearBatch(); null }

            "clearParameters" -> {
                ensureOpen()
                NativeBridge.clearBindings(statementHandle)
                currentBindings.clear()
                null
            }

            "getResultSet" -> currentResultSet
            "getUpdateCount" -> if (currentResultSet == null) lastUpdateCount else -1
            "getConnection" -> connection
            "getWarnings", "clearWarnings" -> null
            "close" -> { closePreparedStatement(); null }
            "isClosed" -> closed
            "unwrap" -> unwrap(proxy, arguments[0] as Class<*>)
            "isWrapperFor" -> (arguments[0] as Class<*>).isInstance(proxy)
            "toString" -> "SqlCipherPreparedStatement(sql=$sql, closed=$closed)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> unsupported(method.name)
        }
    }

    private fun executePrepared(): Boolean {
        ensureOpen()
        currentResultSet?.close()
        currentResultSet = null
        NativeBridge.reset(statementHandle)
        applyCurrentBindings()

        return if (querySql) {
            currentResultSet = SqlCipherResultSetProxy.create(
                statementHandle = statementHandle,
                nativeHandle = state.nativeHandle,
                onClose = { NativeBridge.reset(statementHandle) }
            )
            lastUpdateCount = -1
            true
        } else {
            executeNonQueryStep()
            false
        }
    }

    private fun executePreparedUpdate(): Int {
        ensureOpen()
        if (querySql) throw SQLException("executeUpdate cannot be used for query SQL")
        currentResultSet?.close()
        currentResultSet = null
        NativeBridge.reset(statementHandle)
        applyCurrentBindings()
        executeNonQueryStep()
        return lastUpdateCount
    }

    private fun executePreparedQuery(): ResultSet {
        ensureOpen()
        if (!querySql) throw SQLException("executeQuery cannot be used for non-query SQL")
        currentResultSet?.close()
        currentResultSet = null
        NativeBridge.reset(statementHandle)
        applyCurrentBindings()
        return SqlCipherResultSetProxy.create(
            statementHandle = statementHandle,
            nativeHandle = state.nativeHandle,
            onClose = { NativeBridge.reset(statementHandle) }
        ).also {
            currentResultSet = it
            lastUpdateCount = -1
        }
    }

    private fun executeNonQueryStep() {
        val rc = try {
            NativeBridge.step(statementHandle)
        } catch (e: SQLException) {
            throw withSqliteErrorMapping(e, state.nativeHandle)
        }
        when (rc) {
            SQLITE_DONE -> {
                lastUpdateCount = NativeBridge.changes(state.nativeHandle)
                NativeBridge.reset(statementHandle)
            }

            SQLITE_ROW -> {
                NativeBridge.reset(statementHandle)
                throw SQLException("Statement produced a result set. Use execute()/executeQuery()")
            }

            else -> {
                NativeBridge.reset(statementHandle)
                throw SQLException("Unexpected sqlite3_step result code: $rc")
            }
        }
    }

    private fun setBinding(index: Int, value: Any?) {
        ensureOpen()
        if (index <= 0) throw SQLException("PreparedStatement parameter index must start from 1")
        bindValue(index, value)
        currentBindings[index] = value
    }

    private fun addBatch() {
        ensureOpen()
        if (querySql) throw SQLException("Batch is supported only for non-query PreparedStatement")
        batchBindings += LinkedHashMap(currentBindings)
    }

    private fun executeBatch(): IntArray {
        ensureOpen()
        if (querySql) throw SQLException("executeBatch is supported only for non-query PreparedStatement")
        if (batchBindings.isEmpty()) return IntArray(0)

        currentResultSet?.close()
        currentResultSet = null

        val results = IntArray(batchBindings.size)
        for (i in batchBindings.indices) {
            val snapshot = batchBindings[i]
            NativeBridge.reset(statementHandle)
            applyBindings(snapshot)
            val rc = try {
                NativeBridge.step(statementHandle)
            } catch (e: SQLException) {
                throw withSqliteErrorMapping(e, state.nativeHandle)
            }
            when (rc) {
                SQLITE_DONE -> {
                    results[i] = NativeBridge.changes(state.nativeHandle)
                    NativeBridge.reset(statementHandle)
                }

                SQLITE_ROW -> {
                    NativeBridge.reset(statementHandle)
                    throw SQLException("Batch item produced result set unexpectedly")
                }

                else -> {
                    NativeBridge.reset(statementHandle)
                    throw SQLException("Unexpected sqlite3_step result code in batch: $rc")
                }
            }
        }

        lastUpdateCount = results.lastOrNull() ?: -1
        clearBatch()
        return results
    }

    private fun clearBatch() {
        batchBindings.clear()
    }

    private fun applyCurrentBindings() = applyBindings(currentBindings)

    private fun applyBindings(bindings: Map<Int, Any?>) {
        NativeBridge.clearBindings(statementHandle)
        for ((index, value) in bindings.entries.sortedBy { it.key }) {
            bindValue(index, value)
        }
    }

    private fun bindValue(index: Int, value: Any?) {
        try {
            when (value) {
                null -> NativeBridge.bindNull(statementHandle, index)
                is Int -> NativeBridge.bindInt(statementHandle, index, value)
                is Long -> NativeBridge.bindLong(statementHandle, index, value)
                is Short -> NativeBridge.bindInt(statementHandle, index, value.toInt())
                is Byte -> NativeBridge.bindInt(statementHandle, index, value.toInt())
                is Boolean -> NativeBridge.bindInt(statementHandle, index, if (value) 1 else 0)
                is Double -> NativeBridge.bindDouble(statementHandle, index, value)
                is Float -> NativeBridge.bindDouble(statementHandle, index, value.toDouble())
                is ByteArray -> NativeBridge.bindBlob(statementHandle, index, value)
                is BigDecimal -> NativeBridge.bindText(statementHandle, index, value.toPlainString())
                is String -> NativeBridge.bindText(statementHandle, index, value)
                else -> NativeBridge.bindText(statementHandle, index, value.toString())
            }
        } catch (e: SQLException) {
            throw withSqliteErrorMapping(e, state.nativeHandle)
        }
    }

    private fun closePreparedStatement() {
        if (closed) return
        currentResultSet?.close()
        currentResultSet = null
        NativeBridge.finalizeStmt(statementHandle)
        closed = true
    }

    private fun ensureOpen() {
        if (closed) throw SQLException("PreparedStatement is closed")
        if (state.closed) throw SQLException("Connection is closed")
    }
}

private object SqlCipherResultSetProxy {
    fun create(statementHandle: Long, nativeHandle: Long, onClose: () -> Unit): ResultSet {
        val columnCount = NativeBridge.columnCount(statementHandle)
        val names = (0 until columnCount).map { NativeBridge.columnName(statementHandle, it) }
        val declaredTypes = (0 until columnCount).map { NativeBridge.columnDeclType(statementHandle, it) }

        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
            ResultSetHandler(statementHandle, nativeHandle, names, declaredTypes, onClose)
        ) as ResultSet
    }
}

private class ResultSetHandler(
    private val statementHandle: Long,
    private val nativeHandle: Long,
    private val columnNames: List<String>,
    private val declaredTypes: List<String?>,
    private val onClose: () -> Unit
) : InvocationHandler {
    private var closed = false
    private var lastWasNull = false

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (method.name) {
            "next" -> next()
            "close" -> { closeResultSet(); null }
            "isClosed" -> closed
            "getMetaData" -> SqlCipherResultSetMetaDataProxy.create(columnNames, declaredTypes)
            "getString" -> getString(arguments[0])
            "getInt" -> getInt(arguments[0])
            "getLong" -> getLong(arguments[0])
            "getDouble" -> getDouble(arguments[0])
            "getFloat" -> getFloat(arguments[0])
            "getBytes" -> getBytes(arguments[0])
            "getObject" -> getObject(arguments[0])
            "findColumn" -> findColumn(arguments[0] as String)
            "wasNull" -> lastWasNull
            "unwrap" -> unwrap(proxy, arguments[0] as Class<*>)
            "isWrapperFor" -> (arguments[0] as Class<*>).isInstance(proxy)
            "toString" -> "SqlCipherResultSet(columns=$columnNames, closed=$closed)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> unsupported(method.name)
        }
    }

    private fun next(): Boolean {
        ensureOpen()
        val rc = try {
            NativeBridge.step(statementHandle)
        } catch (e: SQLException) {
            throw withSqliteErrorMapping(e, nativeHandle)
        }
        return when (rc) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> throw SQLException("Unexpected sqlite3_step result code: $rc")
        }
    }

    private fun getString(column: Any?): String? {
        ensureOpen()
        val idx = resolveColumnIndex(column)
        val value = try {
            NativeBridge.columnText(statementHandle, idx)
        } catch (e: SQLException) {
            throw withSqliteErrorMapping(e, nativeHandle)
        }
        lastWasNull = value == null
        return value
    }

    private fun getInt(column: Any?): Int = getString(column)?.toIntOrNull().also { lastWasNull = it == null } ?: 0
    private fun getLong(column: Any?): Long = getString(column)?.toLongOrNull().also { lastWasNull = it == null } ?: 0L
    private fun getDouble(column: Any?): Double = getString(column)?.toDoubleOrNull().also { lastWasNull = it == null } ?: 0.0
    private fun getFloat(column: Any?): Float = getString(column)?.toFloatOrNull().also { lastWasNull = it == null } ?: 0f

    private fun getBytes(column: Any?): ByteArray? {
        ensureOpen()
        val idx = resolveColumnIndex(column)
        val value = try {
            NativeBridge.columnBlob(statementHandle, idx)
        } catch (e: SQLException) {
            throw withSqliteErrorMapping(e, nativeHandle)
        }
        lastWasNull = value == null
        return value
    }

    private fun getObject(column: Any?): Any? {
        ensureOpen()
        val idx = resolveColumnIndex(column)
        return when (NativeBridge.columnType(statementHandle, idx)) {
            SQLITE_NULL -> {
                lastWasNull = true
                null
            }

            SQLITE_INTEGER -> {
                val v = NativeBridge.columnText(statementHandle, idx)?.toLongOrNull()
                lastWasNull = v == null
                v
            }

            SQLITE_FLOAT -> {
                val v = NativeBridge.columnText(statementHandle, idx)?.toDoubleOrNull()
                lastWasNull = v == null
                v
            }

            SQLITE_BLOB -> {
                val v = NativeBridge.columnBlob(statementHandle, idx)
                lastWasNull = v == null
                v
            }

            else -> {
                val v = NativeBridge.columnText(statementHandle, idx)
                lastWasNull = v == null
                v
            }
        }
    }

    private fun findColumn(label: String): Int {
        val index = columnNames.indexOfFirst { it.equals(label, ignoreCase = true) }
        if (index < 0) throw SQLException("Column not found: $label")
        return index + 1
    }

    private fun resolveColumnIndex(column: Any?): Int {
        return when (column) {
            is Int -> {
                val index = column - 1
                if (index !in columnNames.indices) throw SQLException("Column index out of range: $column")
                index
            }

            is String -> {
                val index = columnNames.indexOfFirst { it.equals(column, ignoreCase = true) }
                if (index < 0) throw SQLException("Column not found: $column")
                index
            }

            else -> throw SQLException("Unsupported column identifier: $column")
        }
    }

    private fun closeResultSet() {
        if (closed) return
        onClose()
        closed = true
    }

    private fun ensureOpen() {
        if (closed) throw SQLException("ResultSet is closed")
    }
}

private object SqlCipherResultSetMetaDataProxy {
    fun create(columnNames: List<String>, declaredTypes: List<String?>): ResultSetMetaData {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            ResultSetMetaData::class.java.classLoader,
            arrayOf(ResultSetMetaData::class.java),
            ResultSetMetaDataHandler(columnNames, declaredTypes)
        ) as ResultSetMetaData
    }
}

private class ResultSetMetaDataHandler(
    private val columnNames: List<String>,
    private val declaredTypes: List<String?>
) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (method.name) {
            "getColumnCount" -> columnNames.size
            "getColumnName", "getColumnLabel" -> columnName(arguments[0] as Int)
            "getColumnType" -> columnType(arguments[0] as Int)
            "getColumnTypeName" -> columnTypeName(arguments[0] as Int)
            "getColumnClassName" -> columnClassName(arguments[0] as Int)
            "isNullable" -> ResultSetMetaData.columnNullableUnknown
            "isAutoIncrement" -> false
            "isCaseSensitive" -> true
            "isSearchable" -> true
            "isCurrency" -> false
            "isSigned" -> true
            "getSchemaName", "getTableName", "getCatalogName" -> ""
            "getPrecision", "getScale" -> 0
            "isReadOnly" -> true
            "isWritable", "isDefinitelyWritable" -> false
            "unwrap" -> unwrap(proxy, arguments[0] as Class<*>)
            "isWrapperFor" -> (arguments[0] as Class<*>).isInstance(proxy)
            "toString" -> "SqlCipherResultSetMetaData(columns=$columnNames)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> unsupported(method.name)
        }
    }

    private fun columnName(column: Int): String {
        val index = column - 1
        if (index !in columnNames.indices) throw SQLException("Column index out of range: $column")
        return columnNames[index]
    }

    private fun columnType(column: Int): Int {
        val type = declaredType(column)
        return when {
            type.contains("int") -> Types.INTEGER
            type.contains("double") || type.contains("float") || type.contains("real") -> Types.DOUBLE
            type.contains("blob") -> Types.BLOB
            type.contains("numeric") || type.contains("decimal") -> Types.NUMERIC
            type.contains("bool") -> Types.BOOLEAN
            else -> Types.VARCHAR
        }
    }

    private fun columnTypeName(column: Int): String {
        val type = declaredType(column)
        return if (type.isBlank()) "TEXT" else type.uppercase()
    }

    private fun columnClassName(column: Int): String {
        return when (columnType(column)) {
            Types.INTEGER, Types.SMALLINT, Types.TINYINT -> Int::class.java.name
            Types.BIGINT -> Long::class.java.name
            Types.DOUBLE, Types.FLOAT, Types.REAL, Types.NUMERIC, Types.DECIMAL -> Double::class.java.name
            Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> ByteArray::class.java.name
            Types.BOOLEAN, Types.BIT -> Boolean::class.java.name
            else -> String::class.java.name
        }
    }

    private fun declaredType(column: Int): String {
        val index = column - 1
        if (index !in columnNames.indices) throw SQLException("Column index out of range: $column")
        return declaredTypes.getOrNull(index)?.trim().orEmpty().lowercase()
    }
}

private fun safeExec(nativeHandle: Long, sql: String) {
    try {
        NativeBridge.exec(nativeHandle, sql)
    } catch (e: SQLException) {
        throw withSqliteErrorMapping(e, nativeHandle)
    }
}

private fun withSqliteErrorMapping(cause: SQLException, nativeHandle: Long?): SQLException {
    val code = nativeHandle?.let { NativeBridge.lastErrorCode(it) } ?: 0
    val sqlState = if (code == 0) "HY000" else sqliteCodeToSqlState(code)
    return SQLException(cause.message ?: "SQLite operation failed", sqlState, code, cause)
}

private fun sqliteCodeToSqlState(code: Int): String {
    return when (code and 0xFF) {
        SQLITE_CONSTRAINT -> "23000"
        SQLITE_BUSY, SQLITE_LOCKED -> "40001"
        SQLITE_READONLY, SQLITE_AUTH, SQLITE_PERM -> "42501"
        SQLITE_CANTOPEN, SQLITE_IOERR, SQLITE_NOTADB, SQLITE_CORRUPT -> "08000"
        SQLITE_FULL, SQLITE_NOMEM -> "53000"
        SQLITE_MISUSE -> "HY000"
        else -> "HY000"
    }
}

private fun escapeIdentifier(raw: String): String {
    return '"' + raw.replace("\"", "\"\"") + '"'
}

private fun looksLikeQuery(sql: String): Boolean {
    val normalized = sql.trimStart().lowercase()
    return normalized.startsWith("select") ||
        normalized.startsWith("pragma") ||
        normalized.startsWith("with") ||
        normalized.startsWith("explain")
}

private fun unsupported(methodName: String): Nothing {
    throw SQLFeatureNotSupportedException("$methodName is not implemented in MVP SQLCipher JDBC wrapper")
}

private fun unwrap(proxy: Any, iface: Class<*>): Any {
    if (!iface.isInstance(proxy)) throw SQLException("Not a wrapper for ${iface.name}")
    return proxy
}
