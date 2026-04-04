# SQLCipherMultiplatform (WIP)

Windows-first MVP for a **Kotlin Multiplatform + JDBC wrapper** around **SQLCipher**.

## Implemented in this initial slice

- Multi-module Gradle project scaffold
- `:kmp-api` with unified common API and platform actuals:
  - JVM actual via custom JNI-backed JDBC driver
  - Android actual via official SQLCipher for Android (`net.zetetic:android-database-sqlcipher`)
- `:jdbc-sqlcipher-jvm` custom JDBC driver (`jdbc:sqlcipher:`)
  - JDBC SPI registration via `META-INF/services/java.sql.Driver` (no explicit `Class.forName(...)` needed)
- `:native-bridge` JNI bridge + CMake build scripts
- JDBC prepared statement MVP support (`setString/setInt/setLong/setObject`, execute/update/query)
- `:samples:desktop-jvm` JDBC-only sample app (encrypted DB + CRUD + wrong-key rejection checks)
- `:samples:kmp-basic-app` basic KMP sample app using `:kmp-api` (encrypted DB + CRUD + wrong-key rejection checks)
- `:samples:kmp-sqldelight-app` KMP + SQLDelight sample app (encrypted DB + CRUD + wrong-key rejection checks)

## Project structure

- `kmp-api` — common KMP API (`expect/actual`, JVM + Android actuals)
- `jdbc-sqlcipher-jvm` — JVM JDBC driver backed by JNI
- `native-bridge` — C JNI layer linked against SQLCipher
- `third_party/sqlcipher` — SQLCipher amalgamation source location
- `samples/desktop-jvm` — **App 1**: JDBC-only sample
- `samples/kmp-basic-app` — **App 2**: basic KMP sample through `SqlCipherDatabaseFactory`
- `samples/kmp-sqldelight-app` — **App 3**: KMP SQLDelight sample

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

## Unified API and platform behavior

`SqlCipherDatabaseFactory` is the unified KMP entrypoint exposed from `commonMain`.

- `initialize(platformContext)`
  - JVM: no-op
  - Android: required once before first `open(...)` (pass Android `Context`)
- `open(path, key)` and `rekey(path, oldKey, newKey)` are available with one stable API surface.

Android usage sketch:

```kotlin
SqlCipherDatabaseFactory.initialize(applicationContext)
val db = SqlCipherDatabaseFactory.open("app.db", "secret")
```

JVM usage sketch:

```kotlin
SqlCipherDatabaseFactory.initialize() // optional no-op
val db = SqlCipherDatabaseFactory.open("/abs/path/app.db", "secret")
```

JVM call sites do not need manual driver bootstrap (`Class.forName(...)`); the SQLCipher JDBC driver is discovered through JDBC SPI.

## Native loading behavior (JVM)

- Manual override is still supported:
  - `-Dsqlcipher.native.path=<dir-or-file>`
  - `-Dsqlcipher.native.lib.basename=<basename>`
- By default (without `sqlcipher.native.path`), runtime now tries classpath-native resolution using:
  - `META-INF/sqlcipher/native/<platform>/manifest.properties`
- Current platform support:
  - `windows-x64`: real native payload (JAR-embedded)
  - `linux-x64`: real native payload (JAR-embedded)
  - `linux-arm64`: real native payload (JAR-embedded)
  - `macos-x64`: real native payload (JAR-embedded)
  - `macos-arm64`: real native payload (JAR-embedded)

Note on “releasing” native libs: JVM does not provide safe in-process unloading for loaded native libraries. The runtime does best-effort cleanup of extracted temp files at process shutdown.

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

- JDBC sample: `./gradlew :samples:desktop-jvm:run`
- KMP basic sample: `./gradlew :samples:kmp-basic-app:run`
- KMP SQLDelight sample: `./gradlew :samples:kmp-sqldelight-app:run`

All sample apps perform the same verification contract:

- create encrypted DB with key
- create tables and perform CRUD operations
- verify encrypted-at-rest (non-plain SQLite header + no plaintext markers)
- verify correct key can read data
- verify wrong key is rejected

### 5) Run sample verification tasks (CI-friendly)

Each sample exposes `verifySample` task:

- `./gradlew :samples:desktop-jvm:verifySample`
- `./gradlew :samples:kmp-basic-app:verifySample`
- `./gradlew :samples:kmp-sqldelight-app:verifySample`

Aggregate task from root project:

- `./gradlew verifySamples`

Console output format for all sample verifications is test-like and CI-friendly:

- `[TEST] <check name>` when a check starts
- `[PASS] <check name>` when a check succeeds
- `[FAIL] <check name> :: <reason>` and process/task failure on any real check error

Wrong-key verification is an expected negative test. During this check SQLCipher native layer may print `ERROR CORE ...` decrypt/HMAC messages; this is expected behavior for rejected keys and the check still prints `[PASS] Wrong-key rejection` when rejection is confirmed.

Note: SQLDelight sample sets `scrubKeyMaterialAfterConnect=false` in connection properties because SQLDelight's JDBC driver may reuse one `Properties` instance for future connections. Key bytes are manually zeroized in finally blocks.

## Next roadmap

1. Expand JDBC surface (metadata, transactions, pragmas, batch APIs)
2. Add more native bind types (double/blob) and stricter type mapping
3. Package per-platform JNI binaries in classifier artifacts
4. Port native build and CI to Linux/macOS
5. Add integration tests for all targets

## Production readiness roadmap

Short Windows-first roadmap is available in:

- [`WINDOWS_FIRST_READINESS_PLAN.md`](./WINDOWS_FIRST_READINESS_PLAN.md)

## Maven Central publishing and CI secrets

Publishing/signing is wired through Gradle and GitHub Actions.

Required GitHub Secrets:

- `SIGNING_KEY` (ASCII-armored private key)
- `SIGNING_PASSWORD`
- `SIGNING_KEY_ID` (recommended)
- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`

OS-specific native build/publish strategy:

- Native payloads are built in a release matrix for `windows-x64`, `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`.
- Release workflow uses a single final publish job to avoid concurrent publication races for the same coordinates.

Published artifacts contract:

- `io.github.s0d3s.sqlcipher.multiplatform:jdbc-sqlcipher-jvm`
  - Pure JDBC API artifact.
  - Declares transitive runtime dependencies on all platform-native artifacts.
- `io.github.s0d3s.sqlcipher.multiplatform:kmp-api`
  - KMP wrapper publication.
  - JVM variant resolves transitively to `jdbc-sqlcipher-jvm` (and therefore native artifacts).
  - Android variant keeps Android SQLCipher dependencies.

Release publishing endpoint strategy:

- SNAPSHOTs: `https://central.sonatype.com/repository/maven-snapshots/`
- Releases (latest Central Portal flow): `https://central.sonatype.com/repository/maven-releases/`

## Immediate next implementation slice

Start with **Phase 1 (JDBC correctness)** in this order:

1. Add native/JNI BLOB + DOUBLE binding support
2. Add `PreparedStatement` batch operations
3. Implement transaction correctness tests (`autoCommit`, `commit`, `rollback`)
4. Add JDBC integration tests for update counts, null handling, and result metadata
