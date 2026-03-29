# Project Context Dump

This file summarizes the current state of the `SQLCipherMultiplatform` workspace so another agent can continue work quickly.

---

## 1) High-level goal

`SQLCipherMultiplatform` is a **Windows-first MVP** for a Kotlin Multiplatform API that exposes encrypted SQLite via SQLCipher.

Core direction:

- Provide a common KMP API (`SqlCipherDatabaseFactory`, `SqlCipherDatabase`)
- Implement JVM via a custom JDBC driver (`jdbc:sqlcipher:`) backed by JNI + native SQLCipher
- Implement Android via official SQLCipher for Android
- Validate behavior through executable sample apps that are CI-friendly

---

## 2) Current module structure

Defined in `settings.gradle.kts`:

- `:kmp-api`
- `:jdbc-sqlcipher-jvm`
- `:native-bridge`
- `:samples:desktop-jvm`
- `:samples:kmp-basic-app`
- `:samples:kmp-sqldelight-app`

### Module roles

#### `:kmp-api`

- KMP expect/actual surface.
- Common API file: `kmp-api/src/commonMain/.../SqlCipherDatabase.kt`
  - `SqlCipherDatabase` methods: `execute`, `querySingleColumn`, `rekey`, `close`
  - `SqlCipherDatabaseFactory` methods: `initialize`, `open`, `rekey`
- JVM actual: `kmp-api/src/jvmMain/.../SqlCipherDatabaseFactoryJvm.kt`
  - Uses `DriverManager.getConnection("jdbc:sqlcipher:...")`
  - Relies on JDBC SPI (`META-INF/services/java.sql.Driver`) for custom driver discovery (no explicit `Class.forName(...)` bootstrap)
  - Wipes copied key material in `finally`
- Android actual: `kmp-api/src/androidMain/.../SqlCipherDatabaseFactoryAndroid.kt`
  - Requires `initialize(context)` before `open`
  - Uses `net.sqlcipher.database.SQLiteDatabase`
  - Has compatibility logic for available open/rekey signatures

#### `:jdbc-sqlcipher-jvm`

- Kotlin/JVM module exposing custom JDBC driver behavior for SQLCipher.
- Has `nativeSmokeTest` task to run integration smoke test with JNI loading enabled.
- Task wires native path defaults to `native-bridge/build/cmake/out/...`.

#### `:native-bridge`

- C/JNI bridge and CMake build.
- CMake file: `native-bridge/CMakeLists.txt`
  - Builds static `sqlcipher` from amalgamation (`third_party/sqlcipher/sqlite3.c`)
  - Builds shared JNI library (`sqlcipher_jni` by default)
  - Requires OpenSSL and JNI
- Gradle file: `native-bridge/build.gradle.kts`
  - Cross-target parameters (`native.target.os`, `native.target.arch`, `native.buildType`, etc.)
  - Supports vcpkg toolchain detection and OpenSSL path wiring
  - Windows copies runtime DLL dependencies next to JNI output

#### Samples

- `:samples:desktop-jvm` (**App 1**): plain JDBC usage of library
- `:samples:kmp-basic-app` (**App 2**): KMP API usage
- `:samples:kmp-sqldelight-app` (**App 3**): SQLDelight on top of JDBC SQLCipher

All sample modules provide `run` alias + `verifySample` tasks.

---

## 3) Root Gradle tasks of interest

From `build.gradle.kts`:

- `initSqlcipherSubmodule`
  - `git submodule update --init --recursive third_party/sqlcipher/upstream`
- `updateSqlcipherAmalgamation`
  - Runs platform script to regenerate local `sqlite3.c/sqlite3.h`
  - Supports optional `-Psqlcipher.ref=<tag-or-commit>`
- `verifySamples`
  - Aggregates all 3 sample verification tasks

---

## 4) SQLCipher / SQLite amalgamation workflow (important)

The project uses a two-layer source layout:

1. **Build inputs used directly by native build** (tracked in `third_party/sqlcipher/`):
   - `sqlite3.c`
   - `sqlite3.h`
2. **Upstream SQLCipher repo as submodule**:
   - `third_party/sqlcipher/upstream`

Submodule config is in `.gitmodules`:

- URL: `https://github.com/sqlcipher/sqlcipher.git`

### Get/update upstream

```bash
git submodule update --init --recursive third_party/sqlcipher/upstream
```

### Regenerate amalgamation (recommended)

```bash
./gradlew updateSqlcipherAmalgamation
```

Optionally pin ref/tag:

```bash
./gradlew updateSqlcipherAmalgamation -Psqlcipher.ref=v4.13.0
```

### Direct scripts

- Linux/macOS/Git Bash:
  - `bash scripts/update-sqlcipher-amalgamation.sh`
  - `bash scripts/update-sqlcipher-amalgamation.sh v4.13.0`
- Windows PowerShell (with `nmake`):
  - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/update-sqlcipher-amalgamation.ps1`
  - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/update-sqlcipher-amalgamation.ps1 -Ref v4.13.0`

### Traceability

Generation writes `third_party/sqlcipher/AMALGAMATION_INFO.txt` with commit/tag/time/command.

Current recorded state (from file):

- upstream commit: `222bdcafad462a1080360de1928cd900a8bccd0a`
- describe: `v4.13.0`

---

## 5) Sample apps behavior contract (critical)

All 3 sample apps now implement the same verification contract:

1. Create encrypted DB with key
2. Create tables + perform CRUD
3. Verify encrypted-at-rest:
   - header is not plain `SQLite format 3\0`
   - plaintext markers are not present in DB file bytes
4. Verify correct key can read expected data
5. Verify wrong key is rejected

All checks are wrapped with:

- `[TEST] <name>`
- `[PASS] <name>`
- `[FAIL] <name> :: <reason>` then throw (fails Gradle task)

This is implemented in:

- `samples/desktop-jvm/.../samples/jdbc/Main.kt`
- `samples/kmp-basic-app/.../samples/kmpbasic/Main.kt`
- `samples/kmp-sqldelight-app/.../samplesqldelight/Main.kt`

Wrong-key checks also print:

- `[INFO] Intentionally checking wrong-key rejection; SQLCipher may emit native ERROR CORE logs here`

Why: SQLCipher native layer may print `ERROR CORE` decrypt/HMAC messages during wrong-key tests; this is expected negative-test behavior.

---

## 6) SQLDelight sample specifics

Files:

- Build config: `samples/kmp-sqldelight-app/build.gradle.kts`
- Schema/queries: `samples/kmp-sqldelight-app/src/commonMain/sqldelight/.../CrudSample.sq`
- App logic: `samples/kmp-sqldelight-app/src/jvmMain/.../Main.kt`

Notes:

- Uses generated `SampleDatabase` from SQLDelight.
- Uses `JdbcSqliteDriver(url = "jdbc:sqlcipher:...", properties=...)`.
- Sets `SqlCipherJdbcProperties.SCRUB_KEY_MATERIAL_AFTER_CONNECT=false`
  - Reason: SQLDelight JDBC may reuse `Properties`; key bytes are manually scrubbed later.

---

## 7) How to build/run quickly

### Compile key JVM modules

```bash
./gradlew :jdbc-sqlcipher-jvm:compileKotlin :kmp-api:compileKotlinJvm :samples:desktop-jvm:compileKotlin :samples:kmp-sqldelight-app:compileKotlinJvm
```

### Build native bridge

```bash
./gradlew :native-bridge:printNativeConfig :native-bridge:buildNative
```

### Run samples

```bash
./gradlew :samples:desktop-jvm:run
./gradlew :samples:kmp-basic-app:run
./gradlew :samples:kmp-sqldelight-app:run
```

### Run CI-style sample verification

```bash
./gradlew :samples:desktop-jvm:verifySample
./gradlew :samples:kmp-basic-app:verifySample
./gradlew :samples:kmp-sqldelight-app:verifySample
./gradlew verifySamples
```

### Run JNI integration smoke test

```bash
./gradlew :jdbc-sqlcipher-jvm:nativeSmokeTest
```

---

## 8) Native build tuning knobs

Important properties used by `:native-bridge`:

- `-Pnative.target.os=windows|linux|macos`
- `-Pnative.target.arch=x64|arm64`
- `-Pnative.buildType=Release|RelWithDebInfo|Debug`
- `-Pnative.lib.basename=sqlcipher_jni`
- `-Pnative.vcpkgTriplet=<triplet>`
- `-Pnative.vcpkgRoot=<path>`
- `-Pnative.opensslRoot=<path>`
- `-Pnative.cmakeToolchainFile=<path>`

Windows-first defaults are currently in place (Release output under `native-bridge/build/cmake/out/Release`).

---

## 9) Known runtime noise / caveats

1. **Wrong-key expected native errors**
   - During wrong-key checks SQLCipher may print `ERROR CORE ...` lines.
   - This is expected if wrong key is intentionally tested and check ends with `[PASS] Wrong-key rejection`.

2. **SQLDelight SLF4J warning**
   - You may see:
     - `SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"`
   - This is a no-op logger binding warning, not a sample verification failure.

3. **Android requires initialization**
   - Must call `SqlCipherDatabaseFactory.initialize(context)` before `open(...)` on Android.

---

## 10) Current roadmap pointer

`WINDOWS_FIRST_READINESS_PLAN.md` summarizes the current short roadmap for external use:

1. JDBC correctness hardening
2. Security hardening and key-handling safety
3. Windows x64 release quality and publishing reliability
4. Follow-up expansion to Linux/macOS payload support

---

## 11) Suggested handoff checklist for next agent

If continuing implementation, start by confirming:

- `./gradlew verifySamples` passes on current machine
- `third_party/sqlcipher/sqlite3.c` and `sqlite3.h` exist and match desired upstream ref
- `./gradlew :jdbc-sqlcipher-jvm:nativeSmokeTest` result

Then proceed with next roadmap slice (currently Phase 1 JDBC correctness tasks).
