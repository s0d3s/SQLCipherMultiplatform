package dev.boosted.sqlcipher.jdbc

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLFeatureNotSupportedException
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types

private const val SQLITE_ROW = 100
private const val SQLITE_DONE = 101

internal object SqlCipherConnectionProxy {
    fun create(nativeHandle: Long): Connection {
        val state = ConnectionState(nativeHandle = nativeHandle)
        val handler = ConnectionHandler(state)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            SqlCipherConnectionProxy::class.java.classLoader,
            arrayOf(Connection::class.java, SqlCipherSecurityOperations::class.java),
            handler
        ) as Connection
    }
}

private data class ConnectionState(
    val nativeHandle: Long,
    var closed: Boolean = false,
    var autoCommit: Boolean = true
)

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
                val sql = arguments.firstOrNull() as? String
                    ?: throw SQLException("prepareStatement requires SQL string")
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
                rollbackTransaction()
                null
            }

            "rekey" -> {
                ensureOpen()
                val newKey = arguments.firstOrNull() as? ByteArray
                    ?: throw SQLException("rekey requires ByteArray key")
                rekey(newKey)
                null
            }

            "clearWarnings" -> {
                ensureOpen()
                null
            }

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
        if (state.closed) {
            return
        }

        NativeBridge.close(state.nativeHandle)
        state.closed = true
    }

    private fun ensureOpen() {
        if (state.closed) {
            throw SQLException("Connection is closed")
        }
    }

    private fun setAutoCommit(newValue: Boolean) {
        if (state.autoCommit == newValue) {
            return
        }

        if (newValue) {
            NativeBridge.exec(state.nativeHandle, "COMMIT")
        } else {
            NativeBridge.exec(state.nativeHandle, "BEGIN IMMEDIATE")
        }

        state.autoCommit = newValue
    }

    private fun commitTransaction() {
        if (state.autoCommit) {
            throw SQLException("Cannot commit while autoCommit=true")
        }

        NativeBridge.exec(state.nativeHandle, "COMMIT")
        NativeBridge.exec(state.nativeHandle, "BEGIN IMMEDIATE")
    }

    private fun rollbackTransaction() {
        if (state.autoCommit) {
            throw SQLException("Cannot rollback while autoCommit=true")
        }

        NativeBridge.exec(state.nativeHandle, "ROLLBACK")
        NativeBridge.exec(state.nativeHandle, "BEGIN IMMEDIATE")
    }

    private fun rekey(newKey: ByteArray) {
        if (newKey.isEmpty()) {
            throw SQLException("Rekey key cannot be empty")
        }

        val keyCopy = newKey.copyOf()
        try {
            NativeBridge.rekeyBytes(state.nativeHandle, keyCopy)
        } finally {
            keyCopy.fill(0)
        }
    }
}

private object SqlCipherStatementProxy {
    fun create(state: ConnectionState, connection: Connection): Statement {
        val handler = StatementHandler(state, connection)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
            handler
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
            NativeBridge.exec(state.nativeHandle, sql)
            lastUpdateCount = NativeBridge.changes(state.nativeHandle)
            false
        }
    }

    private fun executeUpdate(sql: String): Int {
        ensureOpen()
        if (looksLikeQuery(sql)) {
            throw SQLException("executeUpdate cannot be used for query SQL")
        }
        currentResultSet?.close()
        currentResultSet = null
        NativeBridge.exec(state.nativeHandle, sql)
        lastUpdateCount = NativeBridge.changes(state.nativeHandle)
        return lastUpdateCount
    }

    private fun executeQuery(sql: String): ResultSet {
        ensureOpen()
        if (!looksLikeQuery(sql)) {
            throw SQLException("executeQuery requires a query SQL")
        }
        val stmtHandle = NativeBridge.prepare(state.nativeHandle, sql)
        return SqlCipherResultSetProxy.create(
            statementHandle = stmtHandle,
            onClose = {
                NativeBridge.finalizeStmt(stmtHandle)
            }
        )
    }

    private fun closeStatement() {
        if (closed) {
            return
        }

        currentResultSet?.close()
        currentResultSet = null
        closed = true
    }

    private fun ensureOpen() {
        if (closed) {
            throw SQLException("Statement is closed")
        }
        if (state.closed) {
            throw SQLException("Connection is closed")
        }
    }
}

private object SqlCipherPreparedStatementProxy {
    fun create(state: ConnectionState, connection: Connection, sql: String): PreparedStatement {
        val stmtHandle = NativeBridge.prepare(state.nativeHandle, sql)
        val handler = PreparedStatementHandler(state, connection, stmtHandle, sql)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            PreparedStatement::class.java.classLoader,
            arrayOf(PreparedStatement::class.java),
            handler
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
            "execute" -> {
                if (arguments.isEmpty()) executePrepared() else unsupported(method.name)
            }

            "executeUpdate" -> {
                if (arguments.isEmpty()) executePreparedUpdate() else unsupported(method.name)
            }

            "executeQuery" -> {
                if (arguments.isEmpty()) executePreparedQuery() else unsupported(method.name)
            }

            "setString" -> {
                setString(arguments[0] as Int, arguments[1] as String?)
                null
            }

            "setInt" -> {
                setInt(arguments[0] as Int, arguments[1] as Int)
                null
            }

            "setLong" -> {
                setLong(arguments[0] as Int, arguments[1] as Long)
                null
            }

            "setNull" -> {
                setNull(arguments[0] as Int)
                null
            }

            "setObject" -> {
                setObject(arguments[0] as Int, arguments[1])
                null
            }

            "setBoolean" -> {
                setInt(arguments[0] as Int, if (arguments[1] as Boolean) 1 else 0)
                null
            }

            "setShort" -> {
                setInt(arguments[0] as Int, (arguments[1] as Short).toInt())
                null
            }

            "setByte" -> {
                setInt(arguments[0] as Int, (arguments[1] as Byte).toInt())
                null
            }

            "setDouble" -> {
                setDouble(arguments[0] as Int, arguments[1] as Double)
                null
            }

            "setFloat" -> {
                setFloat(arguments[0] as Int, arguments[1] as Float)
                null
            }

            "setBytes" -> {
                setBytes(arguments[0] as Int, arguments[1] as ByteArray?)
                null
            }

            "setBigDecimal" -> {
                setBigDecimal(arguments[0] as Int, arguments[1] as BigDecimal?)
                null
            }

            "addBatch" -> {
                addBatch()
                null
            }

            "executeBatch" -> {
                executeBatch()
            }

            "clearBatch" -> {
                clearBatch()
                null
            }

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
            "close" -> {
                closePreparedStatement()
                null
            }

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
                onClose = {
                    NativeBridge.reset(statementHandle)
                }
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
        if (querySql) {
            throw SQLException("executeUpdate cannot be used for query SQL")
        }

        currentResultSet?.close()
        currentResultSet = null

        NativeBridge.reset(statementHandle)
        applyCurrentBindings()
        executeNonQueryStep()
        return lastUpdateCount
    }

    private fun executePreparedQuery(): ResultSet {
        ensureOpen()
        if (!querySql) {
            throw SQLException("executeQuery cannot be used for non-query SQL")
        }

        currentResultSet?.close()
        currentResultSet = null

        NativeBridge.reset(statementHandle)
        applyCurrentBindings()
        val rs = SqlCipherResultSetProxy.create(
            statementHandle = statementHandle,
            onClose = {
                NativeBridge.reset(statementHandle)
            }
        )
        currentResultSet = rs
        lastUpdateCount = -1
        return rs
    }

    private fun executeNonQueryStep() {
        val rc = NativeBridge.step(statementHandle)
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

    private fun setString(index: Int, value: String?) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, value)
    }

    private fun setInt(index: Int, value: Int) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, value)
    }

    private fun setLong(index: Int, value: Long) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, value)
    }

    private fun setDouble(index: Int, value: Double) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, value)
    }

    private fun setFloat(index: Int, value: Float) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, value)
    }

    private fun setBytes(index: Int, value: ByteArray?) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, value)
    }

    private fun setBigDecimal(index: Int, value: BigDecimal?) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, value)
    }

    private fun setNull(index: Int) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, null)
    }

    private fun setObject(index: Int, value: Any?) {
        ensureOpen()
        ensureParameterIndex(index)
        registerBinding(index, value)
    }

    private fun addBatch() {
        ensureOpen()
        if (querySql) {
            throw SQLException("Batch is supported only for non-query PreparedStatement")
        }

        batchBindings += LinkedHashMap(currentBindings)
    }

    private fun executeBatch(): IntArray {
        ensureOpen()
        if (querySql) {
            throw SQLException("executeBatch is supported only for non-query PreparedStatement")
        }

        if (batchBindings.isEmpty()) {
            return IntArray(0)
        }

        currentResultSet?.close()
        currentResultSet = null

        val results = IntArray(batchBindings.size)
        for (i in batchBindings.indices) {
            val snapshot = batchBindings[i]
            NativeBridge.reset(statementHandle)
            applyBindings(snapshot)

            val rc = NativeBridge.step(statementHandle)
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

        lastUpdateCount = if (results.isNotEmpty()) results.last() else -1
        clearBatch()
        return results
    }

    private fun clearBatch() {
        batchBindings.clear()
    }

    private fun applyCurrentBindings() {
        applyBindings(currentBindings)
    }

    private fun applyBindings(bindings: Map<Int, Any?>) {
        NativeBridge.clearBindings(statementHandle)
        for ((index, value) in bindings.entries.sortedBy { it.key }) {
            bindValue(index, value)
        }
    }

    private fun registerBinding(index: Int, value: Any?) {
        bindValue(index, value)
        currentBindings[index] = value
    }

    private fun bindValue(index: Int, value: Any?) {
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
    }

    private fun closePreparedStatement() {
        if (closed) {
            return
        }

        currentResultSet?.close()
        currentResultSet = null
        NativeBridge.finalizeStmt(statementHandle)
        closed = true
    }

    private fun ensureParameterIndex(index: Int) {
        if (index <= 0) {
            throw SQLException("PreparedStatement parameter index must start from 1")
        }
    }

    private fun ensureOpen() {
        if (closed) {
            throw SQLException("PreparedStatement is closed")
        }
        if (state.closed) {
            throw SQLException("Connection is closed")
        }
    }
}

private object SqlCipherResultSetProxy {
    fun create(statementHandle: Long, onClose: () -> Unit): ResultSet {
        val columnCount = NativeBridge.columnCount(statementHandle)
        val names = (0 until columnCount).map { index ->
            NativeBridge.columnName(statementHandle, index)
        }

        val handler = ResultSetHandler(statementHandle, names, onClose)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            ResultSet::class.java.classLoader,
            arrayOf(ResultSet::class.java),
            handler
        ) as ResultSet
    }
}

private class ResultSetHandler(
    private val statementHandle: Long,
    private val columnNames: List<String>,
    private val onClose: () -> Unit
) : InvocationHandler {

    private var closed = false
    private var lastWasNull = false

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (method.name) {
            "next" -> next()
            "close" -> {
                closeResultSet()
                null
            }

            "isClosed" -> closed
            "getMetaData" -> SqlCipherResultSetMetaDataProxy.create(columnNames)
            "getString" -> getString(arguments[0])
            "getInt" -> getString(arguments[0])?.toIntOrNull() ?: 0
            "getLong" -> getString(arguments[0])?.toLongOrNull() ?: 0L
            "getDouble" -> getString(arguments[0])?.toDoubleOrNull() ?: 0.0
            "getFloat" -> getString(arguments[0])?.toFloatOrNull() ?: 0f
            "getObject" -> getString(arguments[0])
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
        return when (val rc = NativeBridge.step(statementHandle)) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> throw SQLException("Unexpected sqlite3_step result code: $rc")
        }
    }

    private fun getString(column: Any?): String? {
        ensureOpen()
        val index = resolveColumnIndex(column)
        val value = NativeBridge.columnText(statementHandle, index)
        lastWasNull = value == null
        return value
    }

    private fun findColumn(label: String): Int {
        val index = columnNames.indexOfFirst { it.equals(label, ignoreCase = true) }
        if (index < 0) {
            throw SQLException("Column not found: $label")
        }
        return index + 1
    }

    private fun resolveColumnIndex(column: Any?): Int {
        return when (column) {
            is Int -> {
                val index = column - 1
                if (index !in columnNames.indices) {
                    throw SQLException("Column index out of range: $column")
                }
                index
            }

            is String -> {
                val index = columnNames.indexOfFirst { it.equals(column, ignoreCase = true) }
                if (index < 0) {
                    throw SQLException("Column not found: $column")
                }
                index
            }

            else -> throw SQLException("Unsupported column identifier: $column")
        }
    }

    private fun closeResultSet() {
        if (closed) {
            return
        }

        onClose()
        closed = true
    }

    private fun ensureOpen() {
        if (closed) {
            throw SQLException("ResultSet is closed")
        }
    }
}

private object SqlCipherResultSetMetaDataProxy {
    fun create(columnNames: List<String>): ResultSetMetaData {
        val handler = ResultSetMetaDataHandler(columnNames)
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            ResultSetMetaData::class.java.classLoader,
            arrayOf(ResultSetMetaData::class.java),
            handler
        ) as ResultSetMetaData
    }
}

private class ResultSetMetaDataHandler(
    private val columnNames: List<String>
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args ?: emptyArray()
        return when (method.name) {
            "getColumnCount" -> columnNames.size
            "getColumnName", "getColumnLabel" -> columnName(arguments[0] as Int)
            "getColumnType" -> Types.VARCHAR
            "getColumnTypeName" -> "TEXT"
            "getColumnClassName" -> String::class.java.name
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
        if (index !in columnNames.indices) {
            throw SQLException("Column index out of range: $column")
        }
        return columnNames[index]
    }
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
    if (!iface.isInstance(proxy)) {
        throw SQLException("Not a wrapper for ${iface.name}")
    }
    return proxy
}
