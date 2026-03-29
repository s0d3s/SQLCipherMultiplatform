# Windows-first readiness plan (short)

Current release direction is **Windows first**.

## Scope for external users now

- Supported runtime target: **JVM on Windows x64**
- KMP API is available (JVM + Android actuals)
- Linux/macOS native artifacts remain stubbed (explicit not-yet-supported behavior)

## Priority steps before broader adoption

1. **JDBC correctness**
   - Finalize transaction semantics and savepoint behavior
   - Harden ResultSet metadata/type/null consistency
   - Stabilize SQLState/vendor error mapping

2. **Security hardening**
   - Keep `ByteArray`-first key flow and zeroization guarantees
   - Verify no key leakage in logs/exceptions
   - Lock default SQLCipher pragma policy and document it

3. **Windows release quality**
   - Keep CI green for `verifySamples`, integration, and native smoke tests
   - Ensure Maven publication/signing and consumer `publishToMavenLocal` flow are stable
   - Maintain clear README troubleshooting + known limitations

## Windows-first Definition of Done

- Windows x64 consumer can run without manual native path hacks
- Sample verification + JDBC integration tests are green
- Signed artifacts can be published and consumed from Maven
- Documentation clearly states support matrix and caveats

## Next after Windows-first

- Add real Linux/macOS native payloads
- Extend CI runtime verification on those platforms
- Promote support matrix from Windows-first to multi-platform