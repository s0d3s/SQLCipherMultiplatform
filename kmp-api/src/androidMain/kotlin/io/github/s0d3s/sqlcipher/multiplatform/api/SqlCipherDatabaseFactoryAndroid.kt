package io.github.s0d3s.sqlcipher.multiplatform.api

import android.content.Context
import java.io.File
import net.sqlcipher.database.SQLiteDatabase

actual object SqlCipherDatabaseFactory {
    @Volatile
    private var applicationContext: Context? = null

    actual fun initialize(platformContext: Any?) {
        val context = platformContext as? Context
            ?: throw IllegalArgumentException(
                "Android SqlCipherDatabaseFactory.initialize(...) requires android.content.Context"
            )

        val appCtx = context.applicationContext
        applicationContext = appCtx
        SQLiteDatabase.loadLibs(appCtx)
    }

    actual fun open(path: String, key: ByteArray): SqlCipherDatabase {
        val context = requireContext()
        val keyCopy = key.copyOf()
        val dbFile = resolveDatabaseFile(context, path)

        return try {
            SQLiteDatabase.loadLibs(context)
            val database = openWithBestAvailableKeyApi(dbFile, keyCopy)
            AndroidSqlCipherDatabase(database)
        } finally {
            keyCopy.fill(0)
        }
    }

    actual fun open(path: String, key: String): SqlCipherDatabase {
        val keyBytes = key.encodeToByteArray()
        return try {
            open(path, keyBytes)
        } finally {
            keyBytes.fill(0)
        }
    }

    actual fun rekey(path: String, oldKey: ByteArray, newKey: ByteArray) {
        val db = open(path, oldKey)
        try {
            db.rekey(newKey)
        } finally {
            db.close()
        }
    }

    actual fun rekey(path: String, oldKey: String, newKey: String) {
        val oldKeyBytes = oldKey.encodeToByteArray()
        val newKeyBytes = newKey.encodeToByteArray()
        try {
            rekey(path, oldKeyBytes, newKeyBytes)
        } finally {
            oldKeyBytes.fill(0)
            newKeyBytes.fill(0)
        }
    }

    private fun requireContext(): Context {
        return applicationContext
            ?: throw IllegalStateException(
                "SqlCipherDatabaseFactory.initialize(context) must be called before open(...) on Android"
            )
    }

    private fun resolveDatabaseFile(context: Context, path: String): File {
        val asFile = File(path)
        if (asFile.isAbsolute) {
            asFile.parentFile?.mkdirs()
            return asFile
        }

        return context.getDatabasePath(path).also { file ->
            file.parentFile?.mkdirs()
        }
    }

    private fun openWithBestAvailableKeyApi(file: File, keyBytes: ByteArray): SQLiteDatabase {
        val byteArrayOpen = SQLiteDatabase::class.java.methods.firstOrNull { method ->
            method.name == "openOrCreateDatabase" &&
                (method.parameterTypes.size == 3 || method.parameterTypes.size == 4) &&
                method.parameterTypes[0] == File::class.java &&
                method.parameterTypes[1] == ByteArray::class.java
        }

        if (byteArrayOpen != null) {
            return when (byteArrayOpen.parameterTypes.size) {
                3 -> byteArrayOpen.invoke(null, file, keyBytes, null) as SQLiteDatabase
                4 -> byteArrayOpen.invoke(null, file, keyBytes, null, null) as SQLiteDatabase
                else -> error("Unsupported SQLCipher openOrCreateDatabase signature")
            }
        }

        return SQLiteDatabase.openOrCreateDatabase(file, keyBytes.decodeToString(), null)
    }
}

private class AndroidSqlCipherDatabase(
    private val database: SQLiteDatabase
) : SqlCipherDatabase {

    override fun execute(sql: String) {
        database.execSQL(sql)
    }

    override fun querySingleColumn(sql: String): List<String?> {
        val result = mutableListOf<String?>()
        val cursor = database.rawQuery(sql, emptyArray<String>())
        try {
            while (cursor.moveToNext()) {
                result += if (cursor.isNull(0)) null else cursor.getString(0)
            }
        } finally {
            cursor.close()
        }
        return result
    }

    override fun rekey(newKey: ByteArray) {
        val keyCopy = newKey.copyOf()
        try {
            val byteArrayChangePassword = database.javaClass.methods.firstOrNull { method ->
                method.name == "changePassword" &&
                    method.parameterTypes.contentEquals(arrayOf(ByteArray::class.java))
            }
            if (byteArrayChangePassword != null) {
                byteArrayChangePassword.invoke(database, keyCopy)
                return
            }

            val stringChangePassword = database.javaClass.methods.firstOrNull { method ->
                method.name == "changePassword" &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            if (stringChangePassword != null) {
                stringChangePassword.invoke(database, keyCopy.decodeToString())
                return
            }

            throw UnsupportedOperationException("SQLCipher Android runtime does not expose changePassword APIs")
        } finally {
            keyCopy.fill(0)
        }
    }

    override fun close() {
        database.close()
    }
}
