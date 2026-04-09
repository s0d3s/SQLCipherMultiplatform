# SQLCipher source layout

This project uses a two-layer SQLCipher source layout:

1) **Build inputs (tracked directly in this folder):**

- `sqlite3.c`
- `sqlite3.h`

`native-bridge/CMakeLists.txt` consumes these files directly.

2) **Upstream source (git submodule):**

- `upstream/` (SQLCipher repository submodule)

The submodule is used to regenerate local amalgamation files in a reproducible way.

## Initialize SQLCipher submodule

From project root:

- `git submodule update --init --recursive third_party/sqlcipher/upstream`

## Regenerate local amalgamation from upstream

### Via Gradle (recommended)

- `./gradlew updateSqlcipherAmalgamation`

Optionally pin/update to a specific SQLCipher ref/tag in one run:

- `./gradlew updateSqlcipherAmalgamation -Psqlcipher.ref=v4.13.0`

### Via scripts directly

- Linux/macOS/Git Bash:
  - `bash scripts/update-sqlcipher-amalgamation.sh`
  - optional ref: `bash scripts/update-sqlcipher-amalgamation.sh v4.13.0`
- Windows (Developer PowerShell with `nmake` available):
  - `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/update-sqlcipher-amalgamation.ps1`
  - optional ref: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/update-sqlcipher-amalgamation.ps1 -Ref v4.13.0`

## Output metadata

Regeneration also updates:

- `AMALGAMATION_INFO.txt`

It records upstream commit/tag and generation details for traceability.

## Build native bridge

After amalgamation is present:

- `./gradlew :native-bridge:buildNative`

> Note: current build focus is Windows x64 first. Linux/macOS build tuning is planned next.
