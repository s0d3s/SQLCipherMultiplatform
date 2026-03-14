#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "sqlite3.h"

static void throw_sql_exception(JNIEnv *env, const char *message) {
    jclass ex = (*env)->FindClass(env, "java/sql/SQLException");
    if (ex == NULL) {
        return;
    }
    (*env)->ThrowNew(env, ex, message);
}

static sqlite3 *as_db(jlong handle) {
    return (sqlite3 *)(intptr_t)handle;
}

static sqlite3_stmt *as_stmt(jlong handle) {
    return (sqlite3_stmt *)(intptr_t)handle;
}

static void throw_db_error(JNIEnv *env, sqlite3 *db) {
    if (db == NULL) {
        throw_sql_exception(env, "SQLite operation failed");
        return;
    }
    throw_sql_exception(env, sqlite3_errmsg(db));
}

static void secure_zero(void *ptr, size_t len) {
    if (ptr == NULL || len == 0) {
        return;
    }

    volatile unsigned char *p = (volatile unsigned char *)ptr;
    while (len--) {
        *p++ = 0;
    }
}

static int apply_key_bytes(JNIEnv *env, sqlite3 *db, jbyteArray jkey, int is_rekey) {
    if (jkey == NULL) {
        return SQLITE_OK;
    }

    jsize len = (*env)->GetArrayLength(env, jkey);
    if (len <= 0) {
        return SQLITE_OK;
    }

    jbyte *bytes = (*env)->GetByteArrayElements(env, jkey, NULL);
    if (bytes == NULL) {
        throw_sql_exception(env, "Failed to access key bytes");
        return SQLITE_NOMEM;
    }

    int rc = is_rekey
        ? sqlite3_rekey(db, bytes, (int)len)
        : sqlite3_key(db, bytes, (int)len);

    secure_zero(bytes, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, jkey, bytes, 0);

    if (rc != SQLITE_OK) {
        throw_sql_exception(env, sqlite3_errmsg(db));
    }

    return rc;
}

JNIEXPORT jlong JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_open(
    JNIEnv *env,
    jclass clazz,
    jstring jpath
) {
    (void)clazz;
    if (jpath == NULL) {
        throw_sql_exception(env, "Database path cannot be null");
        return 0;
    }

    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    sqlite3 *db = NULL;
    int rc = sqlite3_open_v2(
        path,
        &db,
        SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX,
        NULL
    );
    (*env)->ReleaseStringUTFChars(env, jpath, path);

    if (rc != SQLITE_OK) {
        const char *msg = db != NULL ? sqlite3_errmsg(db) : "sqlite3_open_v2 failed";
        throw_sql_exception(env, msg);
        if (db != NULL) {
            sqlite3_close_v2(db);
        }
        return 0;
    }

    return (jlong)(intptr_t)db;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_key(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jstring jkey
) {
    (void)clazz;
    sqlite3 *db = as_db(handle);
    if (db == NULL) {
        throw_sql_exception(env, "Native DB handle is null");
        return SQLITE_MISUSE;
    }

    if (jkey == NULL) {
        return SQLITE_OK;
    }

    const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
    int rc = sqlite3_key(db, key, (int)strlen(key));
    (*env)->ReleaseStringUTFChars(env, jkey, key);

    if (rc != SQLITE_OK) {
        throw_sql_exception(env, sqlite3_errmsg(db));
    }

    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_keyBytes(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray jkey
) {
    (void)clazz;
    sqlite3 *db = as_db(handle);
    if (db == NULL) {
        throw_sql_exception(env, "Native DB handle is null");
        return SQLITE_MISUSE;
    }

    return apply_key_bytes(env, db, jkey, 0);
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_rekeyBytes(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jbyteArray jkey
) {
    (void)clazz;
    sqlite3 *db = as_db(handle);
    if (db == NULL) {
        throw_sql_exception(env, "Native DB handle is null");
        return SQLITE_MISUSE;
    }

    return apply_key_bytes(env, db, jkey, 1);
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_close(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    sqlite3 *db = as_db(handle);
    if (db == NULL) {
        return SQLITE_OK;
    }
    return sqlite3_close_v2(db);
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_exec(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jstring jsql
) {
    (void)clazz;
    sqlite3 *db = as_db(handle);
    if (db == NULL) {
        throw_sql_exception(env, "Native DB handle is null");
        return SQLITE_MISUSE;
    }

    const char *sql = (*env)->GetStringUTFChars(env, jsql, NULL);
    char *err = NULL;
    int rc = sqlite3_exec(db, sql, NULL, NULL, &err);
    (*env)->ReleaseStringUTFChars(env, jsql, sql);

    if (rc != SQLITE_OK) {
        const char *msg = err != NULL ? err : sqlite3_errmsg(db);
        throw_sql_exception(env, msg);
        if (err != NULL) {
            sqlite3_free(err);
        }
    }

    return rc;
}

JNIEXPORT jlong JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_prepare(
    JNIEnv *env,
    jclass clazz,
    jlong handle,
    jstring jsql
) {
    (void)clazz;
    sqlite3 *db = as_db(handle);
    if (db == NULL) {
        throw_sql_exception(env, "Native DB handle is null");
        return 0;
    }

    const char *sql = (*env)->GetStringUTFChars(env, jsql, NULL);
    sqlite3_stmt *stmt = NULL;
    int rc = sqlite3_prepare_v2(db, sql, -1, &stmt, NULL);
    (*env)->ReleaseStringUTFChars(env, jsql, sql);

    if (rc != SQLITE_OK) {
        throw_sql_exception(env, sqlite3_errmsg(db));
        return 0;
    }

    return (jlong)(intptr_t)stmt;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_step(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    int rc = sqlite3_step(stmt);
    if (rc != SQLITE_ROW && rc != SQLITE_DONE) {
        throw_sql_exception(env, sqlite3_errmsg(sqlite3_db_handle(stmt)));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_bindNull(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle,
    jint parameterIndex
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    int rc = sqlite3_bind_null(stmt, parameterIndex);
    if (rc != SQLITE_OK) {
        throw_db_error(env, sqlite3_db_handle(stmt));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_bindInt(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle,
    jint parameterIndex,
    jint value
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    int rc = sqlite3_bind_int(stmt, parameterIndex, value);
    if (rc != SQLITE_OK) {
        throw_db_error(env, sqlite3_db_handle(stmt));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_bindLong(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle,
    jint parameterIndex,
    jlong value
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    int rc = sqlite3_bind_int64(stmt, parameterIndex, (sqlite3_int64)value);
    if (rc != SQLITE_OK) {
        throw_db_error(env, sqlite3_db_handle(stmt));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_bindDouble(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle,
    jint parameterIndex,
    jdouble value
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    int rc = sqlite3_bind_double(stmt, parameterIndex, value);
    if (rc != SQLITE_OK) {
        throw_db_error(env, sqlite3_db_handle(stmt));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_bindText(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle,
    jint parameterIndex,
    jstring jvalue
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    if (jvalue == NULL) {
        int rc_null = sqlite3_bind_null(stmt, parameterIndex);
        if (rc_null != SQLITE_OK) {
            throw_db_error(env, sqlite3_db_handle(stmt));
        }
        return rc_null;
    }

    const char *value = (*env)->GetStringUTFChars(env, jvalue, NULL);
    int rc = sqlite3_bind_text(stmt, parameterIndex, value, -1, SQLITE_TRANSIENT);
    (*env)->ReleaseStringUTFChars(env, jvalue, value);

    if (rc != SQLITE_OK) {
        throw_db_error(env, sqlite3_db_handle(stmt));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_bindBlob(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle,
    jint parameterIndex,
    jbyteArray jvalue
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    if (jvalue == NULL) {
        int rc_null = sqlite3_bind_null(stmt, parameterIndex);
        if (rc_null != SQLITE_OK) {
            throw_db_error(env, sqlite3_db_handle(stmt));
        }
        return rc_null;
    }

    jsize len = (*env)->GetArrayLength(env, jvalue);
    jbyte *bytes = (*env)->GetByteArrayElements(env, jvalue, NULL);
    int rc = sqlite3_bind_blob(stmt, parameterIndex, bytes, (int)len, SQLITE_TRANSIENT);
    (*env)->ReleaseByteArrayElements(env, jvalue, bytes, JNI_ABORT);

    if (rc != SQLITE_OK) {
        throw_db_error(env, sqlite3_db_handle(stmt));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_clearBindings(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    int rc = sqlite3_clear_bindings(stmt);
    if (rc != SQLITE_OK) {
        throw_db_error(env, sqlite3_db_handle(stmt));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_reset(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return SQLITE_MISUSE;
    }

    int rc = sqlite3_reset(stmt);
    if (rc != SQLITE_OK) {
        throw_db_error(env, sqlite3_db_handle(stmt));
    }
    return rc;
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_changes(
    JNIEnv *env,
    jclass clazz,
    jlong handle
) {
    (void)env;
    (void)clazz;
    sqlite3 *db = as_db(handle);
    if (db == NULL) {
        return 0;
    }
    return sqlite3_changes(db);
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_columnCount(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle
) {
    (void)env;
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        return 0;
    }
    return sqlite3_column_count(stmt);
}

JNIEXPORT jstring JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_columnName(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle,
    jint columnIndex
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return NULL;
    }

    const char *name = sqlite3_column_name(stmt, columnIndex);
    if (name == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, name);
}

JNIEXPORT jstring JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_columnText(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle,
    jint columnIndex
) {
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        throw_sql_exception(env, "Native statement handle is null");
        return NULL;
    }

    const unsigned char *text = sqlite3_column_text(stmt, columnIndex);
    if (text == NULL) {
        return NULL;
    }
    return (*env)->NewStringUTF(env, (const char *)text);
}

JNIEXPORT jint JNICALL Java_dev_boosted_sqlcipher_jdbc_NativeBridge_finalizeStmt(
    JNIEnv *env,
    jclass clazz,
    jlong statementHandle
) {
    (void)env;
    (void)clazz;
    sqlite3_stmt *stmt = as_stmt(statementHandle);
    if (stmt == NULL) {
        return SQLITE_OK;
    }
    return sqlite3_finalize(stmt);
}
