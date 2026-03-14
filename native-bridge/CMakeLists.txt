cmake_minimum_required(VERSION 3.20)
project(sqlcipher_jni_bridge LANGUAGES C)

set(SQLCIPHER_NATIVE_LIB_BASENAME "sqlcipher_jni" CACHE STRING "Output basename for JNI library")
set(SQLCIPHER_TARGET_OS "" CACHE STRING "Target OS identifier for diagnostics")
set(SQLCIPHER_TARGET_ARCH "" CACHE STRING "Target architecture identifier for diagnostics")

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out)

set(SQLCIPHER_DIR ${CMAKE_CURRENT_LIST_DIR}/../third_party/sqlcipher)

if(NOT EXISTS "${SQLCIPHER_DIR}/sqlite3.c" OR NOT EXISTS "${SQLCIPHER_DIR}/sqlite3.h")
    message(FATAL_ERROR "SQLCipher amalgamation not found. Expected files: ${SQLCIPHER_DIR}/sqlite3.c and sqlite3.h")
endif()

add_library(sqlcipher STATIC
    ${SQLCIPHER_DIR}/sqlite3.c
)

find_package(OpenSSL CONFIG REQUIRED)

target_include_directories(sqlcipher PUBLIC ${SQLCIPHER_DIR})
target_compile_definitions(sqlcipher PUBLIC
    SQLITE_HAS_CODEC
    SQLITE_EXTRA_INIT=sqlcipher_extra_init
    SQLITE_EXTRA_SHUTDOWN=sqlcipher_extra_shutdown
    SQLCIPHER_CRYPTO_OPENSSL
    HAVE_STDINT_H=1
    SQLITE_THREADSAFE=1
    SQLITE_TEMP_STORE=2
)

target_link_libraries(sqlcipher PUBLIC OpenSSL::Crypto)

find_package(JNI REQUIRED)

add_library(sqlcipher_jni SHARED
    src/main/c/sqlcipher_jni.c
)

set_target_properties(sqlcipher_jni PROPERTIES
    OUTPUT_NAME ${SQLCIPHER_NATIVE_LIB_BASENAME}
)

target_include_directories(sqlcipher_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${SQLCIPHER_DIR}
)

target_link_libraries(sqlcipher_jni PRIVATE sqlcipher)

if(JNI_LIBRARIES)
    target_link_libraries(sqlcipher_jni PRIVATE ${JNI_LIBRARIES})
endif()

message(STATUS "SQLCipher target: os='${SQLCIPHER_TARGET_OS}' arch='${SQLCIPHER_TARGET_ARCH}'")
message(STATUS "SQLCipher JNI output basename: ${SQLCIPHER_NATIVE_LIB_BASENAME}")
