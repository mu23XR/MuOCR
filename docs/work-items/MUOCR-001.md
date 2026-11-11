# MUOCR-001 — Extract and optimize MuOCR

Owner: Codex. Started: 2026-10-27. Status: local extraction/optimization complete; independent publication resumed under MUOCR-009 on 2026-11-12, pending repository-creation authentication and cloud CI.

## Scope and authorization

User requested optimizing MuOCR from their GitHub munote project and creating a separate repository. User then explicitly requested deleting excess MuNote files. New repository will use the user's account, name `MuOCR`, and the same public visibility as the source.

## Diagnosis

- Upstream commit: `91bb4bb480886b0582a2a33ff540024447fcc558`; source branch `main`; no upstream project memory files found.
- Initial task directory was empty, with no Git repository or user modifications.
- `ocrtool` source/tests are self-contained; build depended on `app/keystore/munote-test.keystore` and root Gradle configuration.
- Identified repeated per-line regex compilation, per-glyph hex-table allocation, eager per-batch system-font scans and redundant queue snapshot copies.
- The complete temporary source clone was deleted after copying the MuOCR subset. Original GitHub munote is unchanged.

## Plan and checkpoints

1. Copy only MuOCR, its MIT license, public legacy test key and minimal build configuration; remove temporary clone.
2. Establish existing test baseline before production source optimization.
3. Apply low-risk optimizations and focused regression tests without adding dependencies.
4. Run unit/PDF regressions and APK build; verify package/signing; record limits.
5. Commit to and publish independent `mu23XR/MuOCR`; verify remote contents and CI.

## Deferred

- Cross-batch font caching, changing PDF batch size and delayed ledger persistence would affect resource or durability behavior.
- Existing backup-recovery interruption window and swallowed cleanup errors require separate behavior-changing fixes.

## Evidence

### Baseline (before production optimization)

- Saved only five relevant MuOCR core files and original tests under ignored `.tools/baseline-source` / `.tools/baseline-tests`.
- `python .tools/run_jvm_checks.py --baseline --all`: 30 existing tests; 11 passed, 19 failed at existing Windows replacement-by-`File.renameTo` checkpoint commits. Font/searchable-Chinese PDF tests passed.
- Android Gradle invocation initially failed on Java 21 local socket path, then dependency access. Process-local short socket directory and existing proxy settings allowed configuration/dependency resolution to continue; no global settings or dependency pins changed.

### Implementation

- Four low-risk optimizations implemented: shared regex, shared hex table, deferred system-font scan, no second snapshot deep copy.
- Added six CMap boundary/format tests and two snapshot isolation/freshness tests. The eight focused tests pass.
- `python .tools/run_worker_checks.py`: 1,000 randomized CMap byte-parity cases and 1,006 text-cleanup parity cases pass.
- Independent root README/NOTICE, license, legacy key location, Gradle settings and read-only CI workflow are ready; duplicate module README/NOTICE removed.

### Verification milestone

- Final paired JVM runs with an isolated verified font: original 30 = 11 passed / 19 failed; optimized 38 = 19 passed / 19 failed. Exact failing method names match; zero new failures. The original Windows replacement failures also reproduce outside the sandbox.
- Existing Chinese font/searchable-PDF tests pass in both final runs. All eight added tests pass.
- APK assembly completed successfully, including full lint, with the verified original font and local `-x :ocrtool:prepareMuOcrFont`; standard CI keeps the font task enabled. Production Kotlin 2.2.0 / Java sources compile.
- Independent read-only review found no additional correctness/build issue; signing key bytes match upstream Git blob `08fa255c54cf6ff180683ffb2091f2370d66dda3`.

### Stop checkpoint requested by user

- User explicitly requested stopping for the day after finding a suitable checkpoint. No further implementation, publication or background work should continue.
- Release APK is saved at `ocrtool/build/outputs/apk/release/ocrtool-release.apk`; signature verification passed. Package `dev.munote.ocrtool`, version 1.1.3/code 9, certificate preserved. APK SHA-256: `f0dbbec0846ef7f831b433bd403b9f22ee480d23484750030bf8a95a38da9e24`.
- Further production-Gradle unit test run was cancelled at the stop request without XML results; its daemon exited. All task-owned sessions and download helpers are stopped. Completed paired JVM test evidence is 19/38 pass with exactly the original 19 Windows failures, and all eight new tests passing.
- Local checkpoint commit contains the 36 necessary source/test/build/documentation files. Caches, one-off tooling, APK, local.properties and Kotlin working state are ignored. No unrelated user changes existed at start.
- Remaining: optionally rerun the actual Gradle unit task (same Windows limitation), then create and upload `mu23XR/MuOCR` when the user resumes and GitHub creation access is available. No remote has been created or configured. Real-device 1GB acceptance remains outstanding.

### Remote handoff

- GitHub connector has no repository-creation tool; local CLI has no authenticated account.
- Browser automation stopped because current Chrome URL could not be identified reliably for policy enforcement.
- User subsequently chose “先完成本地优化”; finish local validation and commit first. Independent GitHub repository creation/upload is pending and must not be reported as complete.
