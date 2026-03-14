# Production Readiness Plan (SQLCipher JDBC + KMP)

This document defines the path from current MVP to a production-grade library.

## Current baseline

- KMP API module with JVM actual
- JDBC wrapper module with custom `jdbc:sqlcipher:` driver
- JNI native bridge for SQLCipher calls
- Windows-first native build scaffold
- Basic statement + prepared-statement flow

---

## Phase 1 — API/JDBC correctness

### Goals
- Reach predictable JDBC behavior for common workloads.

### Tasks
- Complete `PreparedStatement` bindings:
  - `setDouble`, `setBytes` (BLOB), date/time/timestamp handling
- Support batch APIs:
  - `addBatch`, `executeBatch`, `clearBatch`
- Improve `ResultSet` behavior:
  - metadata, type getters, null/wasNull behavior consistency
- Improve transaction semantics:
  - `setAutoCommit`, `commit`, `rollback`, savepoints (if supported)
- Map SQLite/SQLCipher errors to stable SQLState + vendor codes.

### Exit criteria
- JDBC integration tests green for DDL/DML/query/transactions/batch.

---

## Phase 2 — Security hardening

### Goals
- Enforce secure key usage and safer defaults.

### Tasks
- Key input API for `ByteArray` (avoid long-lived String where possible)
- Zeroize key material after native handoff
- Add rekey flow (`PRAGMA rekey`) and migration utilities
- Define default cipher pragmas policy:
  - KDF iterations
  - page size
  - HMAC/cipher compatibility policy
- Ensure no key material in logs/exceptions.

### Exit criteria
- Security checklist passed, no key leakage in normal diagnostics.

---

## Phase 3 — Native portability

### Goals
- Reliable builds for all target desktop platforms.

### Target matrix
- Windows: x64
- Linux: x64, arm64
- macOS: x64, arm64

### Tasks
- Finalize CMake toolchains/options per platform
- Ensure SQLCipher/OpenSSL dependency strategy is reproducible
- Implement unified native library naming + load resolution
- Add platform smoke tests opening encrypted DB and verifying reads/writes.

### Exit criteria
- Native CI jobs pass for all target OS/arch combinations.

---

## Phase 4 — Packaging and publishing

### Goals
- Make artifacts consumable in real apps without manual native setup.

### Tasks
- Publish JVM artifacts with platform classifiers for natives
- Improve runtime loading strategy (extract/load or classpath-based)
- Sign artifacts and publish to Maven repository
- Generate BOM/version alignment if needed.

### Exit criteria
- Consumer sample imports from Maven and runs without local hacks.

---

## Phase 5 — Quality gates (CI/CD)

### Goals
- Automated confidence for release quality.

### Tasks
- Full CI matrix for compile + tests + integration + native checks
- Add sanitizers/static analysis for native code where feasible
- Add dependency and vulnerability scanning
- Add license/compliance checks for SQLCipher/OpenSSL chain.

### Exit criteria
- CI quality gates required for merges; no critical vulnerabilities.

---

## Phase 6 — Release readiness (v1.0)

### Goals
- Stable, documented, supportable first production release.

### Tasks
- Write migration + troubleshooting docs
- Freeze API for v1.0
- Run long-duration stress tests
- Publish release notes and compatibility guarantees.

### Exit criteria
- RC passes test matrix and user acceptance criteria.

---

## Suggested implementation order (practical)

1. JDBC correctness + transaction behavior
2. Security/key management hardening
3. Linux/macOS native support
4. Packaging/publication
5. Full CI matrix + quality gates
6. Release candidate, then v1.0
