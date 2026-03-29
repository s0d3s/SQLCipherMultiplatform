# Maven Central preparation plan (Windows-first MVP)

This document captures:

1. What is already ready in this repository
2. What is missing before Maven Central publication
3. How to implement OS-specific native loading/releasing behavior
4. CI/CD design (multi-runner native build + single publish point)
5. Required GitHub Secrets list

---

## 1) Current readiness audit

## ✅ Ready now

- Multi-module structure is already suitable for publication:
  - `:kmp-api`
  - `:jdbc-sqlcipher-jvm`
  - `:native-artifacts:sqlcipher-native-windows-x64`
  - `:native-artifacts:sqlcipher-native-linux-x64`
  - `:native-artifacts:sqlcipher-native-linux-arm64`
  - `:native-artifacts:sqlcipher-native-macos-x64`
  - `:native-artifacts:sqlcipher-native-macos-arm64`
- Group/version are centralized in root project.
- Windows native artifact already packages a real payload and native manifest (`kind=real`).
- Linux/macOS native artifacts exist as explicit stubs (`kind=stub`) with clear messaging.
- JVM native loading already supports manual overrides:
  - `-Dsqlcipher.native.path`
  - `-Dsqlcipher.native.lib.basename`
- Android implementation does not require JNI native artifact packaging.

## ❌ Not ready yet

- No Maven Central publishing/signing plugin configuration (`maven-publish`, `signing`).
- No Sonatype/Central repository credentials wiring.
- No repository-level GitHub Actions workflows (`.github/workflows`).
- No automatic classpath resource-based native resolver in JDBC runtime.
- No process cleanup strategy for extracted native files.

---

## 2) Work plan (implementation order)

## Phase A — Publishing foundation

1. Add shared Gradle publishing convention for all publishable modules.
2. Add required POM metadata:
   - `name`, `description`, `url`
   - `licenses`
   - `developers`
   - `scm`
3. Ensure sources/javadoc artifacts are published for each module type.

## Phase B — Signing + Maven Central upload

1. Configure `signing` plugin for all publications.
2. Configure Maven Central repository credentials.
3. Add release guards:
   - fail release publish when signing keys are missing
   - allow local/dev publishing without credentials when needed.

## Phase C — Runtime native usability (Windows first)

Implement a native resolver in `:jdbc-sqlcipher-jvm`:

1. Detect runtime platform (`windows-x64`, `linux-x64`, etc.).
2. Find `META-INF/sqlcipher/native/<platform>/manifest.properties` on classpath.
3. If `kind=real`:
   - extract native payload to process temp cache directory
   - on Windows, preload dependency DLLs first
   - load JNI binary via `System.load(...)`
4. If `kind=stub`:
   - throw clear runtime exception with actionable message.
5. Keep current manual path overrides as highest-priority fallback.

## Phase D — Releasing/cleanup semantics

JVM cannot safely unload an already-loaded native library from the same process.

Implement practical release strategy:

1. Continue deterministic DB/statement/result/connection close (already present).
2. Track extracted temp directory for native payload.
3. Register shutdown hook for best-effort cleanup of extracted files.
4. Document this behavior clearly in README.

## Phase E — CI/CD (multi-runner build, single publish)

Because native artifacts are OS-specific, build them on matching runners.

1. Create CI workflow for validation (PR/push):
   - matrix jobs on Windows/Linux/macOS
   - run build + tests/verification tasks
2. Create release workflow:
   - `build-windows-native` on `windows-latest`
   - `build-linux-native` on `ubuntu-latest`
   - `build-macos-native` on `macos-latest`
   - each job uploads prepared artifacts
   - final `publish` job depends on all build jobs and performs **single** signed publish to Maven Central

This avoids race conditions from parallel jobs publishing the same coordinates.

---

## 3) GitHub Secrets required

Use one consistent naming scheme. Recommended:

## Signing

- `SIGNING_KEY` (ASCII-armored private PGP key)
- `SIGNING_PASSWORD` (key passphrase)
- `SIGNING_KEY_ID` (optional but recommended)

## Maven Central credentials

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`

If using OSSRH naming compatibility, equivalents can be:

- `OSSRH_USERNAME`
- `OSSRH_PASSWORD`

---

## 4) Minimum Definition of Done

1. `publishToMavenLocal` succeeds for all publishable modules with complete POM metadata.
2. Windows runtime works out-of-box without `sqlcipher.native.path` manual configuration.
3. Non-Windows currently fails with explicit stub message (until real binaries are added).
4. GitHub Actions CI exists and runs verification across runners.
5. Release workflow publishes signed artifacts from a single publish job.
6. README includes consumer dependency examples and platform caveats.
