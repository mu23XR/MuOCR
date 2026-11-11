# MUOCR-001 optimization record

Base: MuOCR 1.1.3 from munote `91bb4bb480886b0582a2a33ff540024447fcc558`.

## Changes and reasons

| Location | Change | Why / expected benefit | Risk |
| --- | --- | --- | --- |
| `LargePdfOcrWorker.kt` sanitization | Reuse immutable whitespace regex | Avoid recompilation for each OCR line; replacement semantics unchanged | Low |
| `OcrUnicodeCMap.java` hex encoding | Reuse hex digits | Avoid allocating the same 16-character array for each CID and Unicode unit; CMap bytes unchanged | Low |
| `PdfSystemFontResolver.java` font resolution | Try bundled font before scanning system directories | Successful bundled-font resolution avoids repeated directory traversal and sorting for PDF batches; fallback order retained | Low; Android runtime path still needs device validation |
| `OcrQueueLedger.kt` snapshot | Return freshly deserialized snapshot without a second deep copy | Avoid redundant Entry/list allocation on queue refresh; callers still receive isolated objects and fresh disk state | Low; regression tests cover mutation isolation |
| Gradle / CI | Root project MuOCR; only `ocrtool`; relocate unchanged legacy key; standalone workflow | Build and test independently of MuNote, preserve installed app compatibility, avoid release-tag collisions on routine pushes | Low; build/signature verification required |

No dependency added or upgraded. Allocation and filesystem-work reductions are expected benefits; no wall-clock performance claim is made without measurement.

## Deliberately deferred

- Do not cache PDF/TrueType font objects across batches; they belong to their PDDocument and explicit subset lifecycle.
- Keep 16-page PDF batches, resource release and GC behavior to preserve Android heap safeguards.
- Keep per-page durable queue writes; batching them would change crash recovery behavior.
- Existing backup overwrite interruption window after recovering a corrupt manifest and swallowed cleanup failures need separately scoped behavior-changing fixes.
- UI view recycling could help very large task lists but carries a larger regression surface than this pass.

## Validation

The original implementation uses `File.renameTo` to replace checkpoints on Android/Linux. An isolated Java probe and paired tests confirmed that replacing an existing destination fails on this Windows host, including outside the sandbox. Those original failures remain unchanged.

| Check | Result |
| --- | --- |
| Original JVM suite, `python .tools/run_jvm_checks.py --baseline --all` | 30 tests: 11 passed / 19 failed at existing checkpoint replacement |
| Optimized JVM suite, `python .tools/run_jvm_checks.py --all` | 38 tests: 19 passed / 19 failed; exact same 19 failed method names, no added failures |
| Added regression tests | All 8 passed (6 CMap, 2 snapshot isolation/freshness) |
| Existing CJK font and searchable-Chinese PDF tests | Passed in both final runs |
| `python .tools/run_worker_checks.py` | 1,000 randomized CMap byte comparisons and 1,006 text cleanup comparisons identical to original |
| Independent review | Single MuOCR module; original key Git blob `08fa255c54cf6ff180683ffb2091f2370d66dda3`; no MuNote application or source repository history |

The one-off local JVM runner uses existing cached Kotlin 2.0.21 and Java 21 for JVM-only files. Production Gradle remains Kotlin 2.2.0; CI uses Gradle 8.11.1 / JDK 17. Logs and machine-readable paired comparisons are retained under ignored `.tools/` and are not part of the standalone source repository. The pinned 17,772,300-byte font was separately verified against Git blob `fb0637bafbcd804fe32152370a1225990745b4bc` and placed in an isolated JVM working directory to avoid a race with Gradle's font-generation task.

Android Release APK build succeeded, including full lint: `gradle :ocrtool:assembleRelease -x :ocrtool:prepareMuOcrFont --no-daemon --no-watch-fs --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`. Local Gradle 8.13 / JDK 21 uses only process-local proxy/socket settings; its slow font-download task is excluded after independent verification and restoration of the exact pinned asset. CI retains the original font-download and verification task. Production Kotlin 2.2.0 and Java compilation both passed.

- APK: `ocrtool/build/outputs/apk/release/ocrtool-release.apk`, 86,809,108 bytes.
- Package: `dev.munote.ocrtool`; version 1.1.3 / code 9; release is not debuggable.
- APK SHA-256: `f0dbbec0846ef7f831b433bd403b9f22ee480d23484750030bf8a95a38da9e24`.
- `apksigner verify --verbose --print-certs`: passed (v2); certificate SHA-256 `293258f59770e0fd5e75f6497e506d9c95dfcf82c755de66dd76ded54a467d63`.
- A further Gradle `:ocrtool:testDebugUnitTest` run was started with production Kotlin 2.2.0; the user requested stopping for the day before its final result. Treat it as pending/cancelled, not passed. The completed paired JVM regressions above remain the test evidence.

The upstream real-device 1GB / 583-page PDF acceptance remains outstanding.

## 2026-10-28 — MUOCR-003 / v1.1.4

This authorized fix pass addresses the data-safety findings R1, R2 and R7 from MUOCR-002, with no new dependency or checkpoint-format change.

| Location | Change / reason | Risk and validation |
| --- | --- | --- |
| DurableFile / queue / OCR and PDF journals | Fsync then atomic replace; back up only a validated primary and preserve a recovered backup | Explicit failure if atomic replacement unsupported; replacement/failure-injection and continuous-write regressions |
| Worker / PdfOutputRecovery / ledger.complete | Commit DONE once after successful target close; preserve complete or ambiguous output on later errors; compare bounded chunks on recovery | Recovery may read a large target; foreground mode and exact-byte/short-read/failure tests; real SAF device acceptance remains pending |
| Ledger.withCleanup / MainActivity / Worker | Acquire a shared per-task cleanup lease atomically; keep file I/O outside queue monitor; block conflicting retries and recheck saved output before cancellation cleanup | Barrier tests for retry/remove/claim, exception release and stale snapshots; no cross-task cleanup sharing |

Final JVM regression: `python .tools/run_jvm_checks.py --all` **71 passed / 0 failed**, versus baseline 19 passed / 19 failed out of 38. All 19 original Windows replacement failures resolved; 33 tests added. Production Gradle unit task is separately blocked by offline PDFBox artifact metadata; it is not reported as passing. Actual APK build/signature result is recorded in MUOCR-003 and the session handoff.

Deferred: persistent cleanup-error records/cache manager (R3), changing an existing task's output folder (R4), Activity quality-selection preservation (R5), full space preflight (R6), UI row reuse, wrapper/CI enhancements, watchdog design and real 1GB device acceptance. These are not silently included in this release or claimed complete.

## 2026-11-04 — MUOCR-004 / v1.1.5 Android acceptance fix

Actual Android output exposed a CJK space CID with no ToUnicode entry. `PdfSystemFontResolver` reused a whitespace rule intended only for font coverage checks, so text extraction could return U+0001. Collect every emitted supported GID in the small package-private `OcrUnicodeCMap.rememberUnicode` helper, including spaces; retain the coverage rule and original CID/radical preference. No dependency or checkpoint format changes.

JVM suite: **72/72 passed**. The real-font incremental PDF test now saves/reopens and extracts Chinese text with spaces through the production collection helper; old collection semantics fail two regressions. Production Release build and lintVital pass; full lint and standard Gradle unit task are blocked by offline PDFBox cache metadata, not reported as passing.

On existing MuMu instance1, actual SAF/OCR/export of 3 and 48 image-only pages preserves page boxes/rotation, visible RGB and original PDF byte prefixes. Actual used Type0 CIDs all map correctly; the previously missing space now extracts as U+0020. Serial waiting, pause/resume, cancellation/retry and process interruption were exercised. Exact OCR anchor checks remain **FAIL** (1/3 and 0/48 pages) due to pre-existing O/0 and duplicate-Han recognition errors; these are not hidden with normalization. See MUOCR-004 for exact evidence and limitations. This does not validate the user's 1GB file or a physical phone.

## 2026-11-05 — MUOCR-005 / v1.1.6

| Location | Change / reason | Cost, risk and validation |
| --- | --- | --- |
| OcrResultSelector / LargePdfOcrWorker | Retry uncertain balanced-mode pages at the existing high-resolution size; adopt only complete, uniquely matched lines with a≥0.06 confidence gain | At most one extra inference per uncached page; one application-owned bitmap at a time. Confidence is not ground truth; real documents may still regress. Geometry, ambiguity, missing lines, repeated text, invalid scores and scaled bounds covered by16 tests |
| MainActivity | Save/restore selected quality and expose selected button state; surface preference errors | Small UI preference write; actual restart/rotation checks completed |

JVM baseline72/72, final88/88. Actual Release compilation, lintVital and legacy-certificate verification pass. Standard Gradle unit task/full lint remain blocked by offlinePDFBox2.0.31 artifact metadata, not reported as passing. Actual PDF comparison and rejected less-conservative candidates are recorded in MUOCR-005. Existing source images/checkpoints, legacy callers' other requested dimensions and high-resolution single-pass mode remain compatible; no dependency added.

Quality-selection loss (part of R5) is fixed. Folder-picker continuation across Activity recreation and R3/R4/R6 remain outside this change. Synthetic fixture results do not establish real1GB performance or general OCR accuracy.

## 2026-11-05 — MUOCR-006 / v1.1.7

| Location | Change / reason | Cost, risk and validation |
| --- | --- | --- |
| OcrCacheFiles / CacheActivity / scheduler / ledger | Show both work roots, per-directory bytes/pages and explicit confirmed cleanup; persist failed cleanup/discard intent and enforce worker + task ownership | Deleting selected OCR checkpoints is intentional and irreversible. Canonical-root/name/link checks, partial-delete fault injection, lease tests and actual293-page synthetic cleanup protect other data. No source/SAF output/font deletion |
| Worker legacy import | Retain0.1.3 identity and JSON; merge every missing valid page atomically, including a retry after partial migration | Old cache stays available until user clears it. A link or normal checked source copy can consume storage. Internal/external roots, identity pin and interrupted import are tested; same-signature upgrade preserved299 fixture files |
| OcrChineseText / OcrTextStyle / MainActivity | Optional offline simplified text layer; clean trailing Unicode spaces, keep raw journals, allow idle old tasks to switch modes and invalidate only incompatible PDF export state | Simplification can change legitimate traditional characters, so it is visible and reversible before export. It does not correct0/O or arbitrary OCR errors. Actual raw/simplified PDFs preserve pixels/boxes and explicit Unicode mappings |

Formal Gradle suite **106/106 passed**, baseline direct JVM88/88;18 cache/style tests added. Release assembly/lintVital/signature pass. Existing PDFBox test dependency was fetched through an authorized network retry; versions/dependencies unchanged. Full lint now exposes6 pre-existing WorkManager RestrictedApi errors (identical Result introspection exists at `7a75f2b`),27 warnings; no lint suppression/baseline was added. Two pre-existing misleading indentations in touched Worker loops were corrected without behavior changes.

R3 private cache management/failure visibility is implemented; durable SAF-output deletion records remain deferred, alongside R4/R6 and the folder-picker part of R5. The real395-page user document and1GB physical-device acceptance remain unverified.
