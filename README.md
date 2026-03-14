# SQLiteMultiCrypt (WIP)

Windows-first MVP for a **Kotlin Multiplatform + JDBC wrapper** around **SQLCipher**.

## Implemented in this initial slice

- Multi-module Gradle project scaffold
- `:kmp-api` with common API and JVM actual implementation
- `:jdbc-sqlcipher-jvm` custom JDBC driver (`jdbc:sqlcipher:`)
- `:native-bridge` JNI bridge + CMake build scripts
- JDBC prepared statement MVP support (`setString/setInt/setLong/setObject`, execute/update/query)
- `:samples:desktop-jvm` runnable JVM sample
- `:samples:kmp-sqldelight-app` KMP + SQLDelight sample using encrypted SQLCipher DB

## Project structure

- `kmp-api` — common KMP API (`expect/actual`)
- `jdbc-sqlcipher-jvm` — JVM JDBC driver backed by JNI
- `native-bridge` — C JNI layer linked against SQLCipher
- `third_party/sqlcipher` — SQLCipher amalgamation source location
- `samples/desktop-jvm` — end-to-end usage sample
- `samples/kmp-sqldelight-app` — SQLDelight KMP sample (JVM target) using `jdbc:sqlcipher:`

## SQLCipher source requirement

For this MVP, native build expects SQLCipher **amalgamation files**:

- `third_party/sqlcipher/sqlite3.c`
- `third_party/sqlcipher/sqlite3.h`

Full SQLCipher upstream source is kept as git submodule:

- `third_party/sqlcipher/upstream`

Initialize/update it with:

- `git submodule update --init --recursive third_party/sqlcipher/upstream`

Regenerate local amalgamation from submodule sources with:

- `./gradlew updateSqlcipherAmalgamation`
- optional pinned ref/tag: `./gradlew updateSqlcipherAmalgamation -Psqlcipher.ref=v4.13.0`

See `third_party/sqlcipher/README.md`.

## Build and run (Phase 3 portability)

### 1) Compile JVM modules

- `./gradlew :jdbc-sqlcipher-jvm:compileKotlin :kmp-api:compileKotlinJvm :samples:desktop-jvm:compileKotlin :samples:kmp-sqldelight-app:compileKotlinJvm`
- Windows PowerShell: `./gradlew.bat :jdbc-sqlcipher-jvm:compileKotlin :kmp-api:compileKotlinJvm :samples:desktop-jvm:compileKotlin :samples:kmp-sqldelight-app:compileKotlinJvm`

### 2) Build native bridge with target parameters

Core task:

- `./gradlew :native-bridge:printNativeConfig :native-bridge:buildNative`

Supported Gradle properties:

- `-Pnative.target.os=windows|linux|macos`
- `-Pnative.target.arch=x64|arm64`
- `-Pnative.buildType=Release|RelWithDebInfo|Debug`
- `-Pnative.lib.basename=sqlcipher_jni` (unified JNI output basename)
- `-Pnative.vcpkgTriplet=<triplet>`
- `-Pnative.vcpkgRoot=<path>`
- `-Pnative.opensslRoot=<path>`
- `-Pnative.cmakeToolchainFile=<path>`

Examples:

- Windows x64:
  - `./gradlew.bat :native-bridge:buildNative -Pnative.target.os=windows -Pnative.target.arch=x64 -Pnative.vcpkgTriplet=x64-windows`
- Linux x64 (cross/host depending on toolchain):
  - `./gradlew :native-bridge:buildNative -Pnative.target.os=linux -Pnative.target.arch=x64 -Pnative.vcpkgTriplet=x64-linux`
- macOS arm64:
  - `./gradlew :native-bridge:buildNative -Pnative.target.os=macos -Pnative.target.arch=arm64 -Pnative.vcpkgTriplet=arm64-osx`

### 3) Run native smoke tests

- `./gradlew :jdbc-sqlcipher-jvm:nativeSmokeTest`

`nativeSmokeTest` automatically depends on `:native-bridge:buildNative` and wires:

- `sqlcipher.integration.enabled=true`
- `sqlcipher.native.path` to the platform-appropriate native output folder
- optional `sqlcipher.native.lib.basename`

### 4) Run sample app

- `./gradlew :samples:desktop-jvm:run`

The sample now prints target runtime info and native load configuration, then runs DB operations and a rekey operation.

The sample opens encrypted DB, applies key, creates table, inserts rows, and reads data.
It now exercises direct statements, prepared statements, and rekey flow.

### 5) Run SQLDelight KMP sample app (JVM)

- `./gradlew :samples:kmp-sqldelight-app:run`

This sample validates real KMP app usage through SQLDelight over the SQLCipher JDBC wrapper:

- opens encrypted DB with password (`KEY_BYTES`)
- creates SQLDelight-managed schema (`teams`, `scores`)
- inserts sample records
- executes aggregation queries (per-team and global)
- verifies encryption at rest (file header is not plain SQLite and plaintext markers are absent)
- verifies correct key can read data and wrong key is rejected

Note: the sample sets `scrubKeyMaterialAfterConnect=false` in connection properties because SQLDelight's JDBC driver keeps the same `Properties` instance for future connections.
The sample then manually zeroizes key byte arrays after completion.

## Next roadmap

1. Expand JDBC surface (metadata, transactions, pragmas, batch APIs)
2. Add more native bind types (double/blob) and stricter type mapping
3. Package per-platform JNI binaries in classifier artifacts
4. Port native build and CI to Linux/macOS
5. Add integration tests for all targets

## Production readiness roadmap

Detailed phased plan is available in:

- [`PRODUCTION_READINESS_PLAN.md`](./PRODUCTION_READINESS_PLAN.md)

## Immediate next implementation slice

Start with **Phase 1 (JDBC correctness)** in this order:

1. Add native/JNI BLOB + DOUBLE binding support
2. Add `PreparedStatement` batch operations
3. Implement transaction correctness tests (`autoCommit`, `commit`, `rollback`)
4. Add JDBC integration tests for update counts, null handling, and result metadata
