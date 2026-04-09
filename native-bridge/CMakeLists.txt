cmake_minimum_required(VERSION 3.20)
project(sqlcipher_jni_bridge LANGUAGES C)

set(SQLCIPHER_NATIVE_LIB_BASENAME "sqlcipher_jni" CACHE STRING "Output basename for JNI library")
set(SQLCIPHER_TARGET_OS "" CACHE STRING "Target OS identifier for diagnostics")
set(SQLCIPHER_TARGET_ARCH "" CACHE STRING "Target architecture identifier for diagnostics")

set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(CMAKE_CONFIGURATION_TYPES)
    # Multi-config generators (e.g., Visual Studio)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out/$<CONFIG>)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out/$<CONFIG>)
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out/$<CONFIG>)
else()
    # Single-config generators (e.g., Ninja/Unix Makefiles)
    if(NOT CMAKE_BUILD_TYPE)
        set(CMAKE_BUILD_TYPE RelWithDebInfo)
    endif()
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out/${CMAKE_BUILD_TYPE})
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out/${CMAKE_BUILD_TYPE})
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/out/${CMAKE_BUILD_TYPE})
endif()

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

# Only require JNI headers for building the shared library.
# We don't link against libjvm or libawt — they are provided by the JVM at runtime.
# Specifying an empty COMPONENTS list avoids the default AWT+JVM requirement
# that fails on headless/minimal JDK installations (e.g. Homebrew OpenJDK on macOS).
if(CMAKE_VERSION VERSION_GREATER_EQUAL "3.24")
    find_package(JNI)
    if(NOT JNI_FOUND)
        # Fallback: just locate the headers manually
        find_path(JNI_INCLUDE_DIR jni.h HINTS ENV JAVA_HOME PATH_SUFFIXES include)
        find_path(JNI_MD_INCLUDE_DIR jni_md.h
            HINTS ENV JAVA_HOME
            PATH_SUFFIXES include include/darwin include/linux include/win32)
        if(NOT JNI_INCLUDE_DIR)
            message(FATAL_ERROR "JNI headers (jni.h) not found. Set JAVA_HOME.")
        endif()
        set(JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIR} ${JNI_MD_INCLUDE_DIR})
    endif()
else()
    find_package(JNI REQUIRED)
endif()

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

# On Linux, SQLite/SQLCipher requires pthreads and dlopen
if(UNIX AND NOT APPLE)
    find_package(Threads REQUIRED)
    target_link_libraries(sqlcipher_jni PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
endif()

message(STATUS "SQLCipher target: os='${SQLCIPHER_TARGET_OS}' arch='${SQLCIPHER_TARGET_ARCH}'")
message(STATUS "SQLCipher JNI output basename: ${SQLCIPHER_NATIVE_LIB_BASENAME}")
