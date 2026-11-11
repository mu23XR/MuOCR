# Decisions

## 2026-10-27 — Independent repository boundary

Import only `ocrtool`, the source MIT license, public legacy signing key and required build configuration from munote commit `91bb4bb480886b0582a2a33ff540024447fcc558`. Start independent Git history and preserve upstream provenance here. No MuNote application code, complete source clone or its history is included.

Keep the module name `ocrtool` to avoid needless source/test path changes. Rename only the root Gradle project to MuOCR and relocate the legacy public test key to `signing/`. Keep application ID and certificate so installed MuOCR and saved work can be retained. The public key is inherited and is not a private production signing secret.

Keep optimization behavior unchanged, dependencies pinned, and leave real-device validation explicitly outstanding.

## 2026-10-28 — Preserve completed output across finalization failures

MUOCR-003 separates successful SAF close from queue completion and cache cleanup. Commit DONE exactly once, then clean private cache and publish notifications. A closed complete output wins over late pause/cancel directives; failure to persist or verify completion keeps the output URI and recovery files.

Reuse the existing output URI and export journal rather than add another durable format. On ambiguous recovery compare the target with the complete local work PDF using bounded buffers; equal bytes allow completion without redoing OCR, mismatch allows incomplete-output handling, and verification errors preserve files explicitly. Enter foreground mode before a potentially large SAF comparison.

Use one small fsynced atomic-replacement helper for queue, PDF and OCR checkpoints. Preserve a validated backup if a corrupt primary forced recovery. Unsupported atomic replacement is a contextual error, never a delete-first fallback. Keep a shared in-memory per-task cleanup lease in the existing ledger; it covers verification/deletion while leaving file I/O outside the ledger monitor, so Activity recreation cannot bypass retry/cleanup mutual exclusion.

## 2026-11-04 — Map every emitted glyph, including CJK spaces

Android acceptance exposed a used CJK space CID absent from ToUnicode. Coverage checks may ignore whitespace when choosing a font; Unicode collection must instead record every actually supported glyph (`gid > 0`). Retain the existing first-mapping/radical-alias preference and checkpoint formats. Share this small collection loop with the pure Java CMap utility so the real-font incremental-PDF test exercises production behavior and verifies text after reopening the file. Do not normalize OCR O/0 confusions or duplicate characters to make acceptance thresholds pass.

MuMu startup uses the existing `MuMuAutoLaunch` scheduled task and existing instance1 via CLI. Instance0 is excluded. Creating instance3 was an incorrect interpretation of the isolation requirement; it was shut down and deleted after the user corrected the scope. Do not create more instances without an explicit request.

## 2026-11-05 — Conservative review of uncertain OCR lines

MUOCR-005 compares actual engine output at the existing2200/2800 input dimensions. Only augment balanced2200 mode; reverse mixing regressed the existing high-resolution result. Release selection requires an uncertain base line (confidence≤0.8), a unique mutual geometric partner (IoU≥0.5 and at least80% coverage in both directions), and a confidence gain≥0.06. Keep original strings and bounds for all other lines. Candidate strings remain literal engine output, without O/0 substitution or removal of repeated Han characters.

The earlier0.01 and0.05 gates reduced net CER but introduced newly wrong characters; retain their reports as rejected experiments. A higher aggregate score alone does not establish absence of regressions. The final conservative gate still needs real-document evaluation and is not a guarantee of correctness.

Process the alternate bitmap only after recycling the original and preserve page-journal formats and cached pages. Persist quality selection in existing UI preferences, explicitly reporting failures. Do not introduce another OCR engine, dependency, online upload or new emulator instance in this change.

## 2026-11-05 — Explicit cache ownership and export-only Chinese conversion

MUOCR-006 retains upstream0.1.3 directory identity (URI/size/modified SHA256 prefix), five-column OCR pages and v1 PDF journal bytes. Search both internal/external work roots; reject ambiguous duplicate identities. Import every missing valid page atomically on every attempt and leave the old cache intact. A hard link may reuse source bytes; an unsupported link is logged before using the normal checked SAF copy.

Manual deletion is restricted to validated work-directory names/paths, rejects symbolic links, and never follows SAF output/input URIs. A worker/maintenance gate covers legacy and queued requests; the existing ledger cleanup lease blocks per-task retries. Persist a cleanup marker and the task's decision to discard legacy data before deletion; partial deletion remains visible and a later retry cannot resurrect the discarded OCR pages through legacy import. Keep unknown/complete retained outputs protected.

Use Android's existing ICU Traditional-Simplified only when explicitly selected for a task; default new tasks to simplified and preserve missing-field old queues as raw. Normalize line whitespace at export and retain raw OCR journals. Check available ICU IDs and report an unavailable converter explicitly; idle task cards can switch to raw without losing pages. A separate text-style marker invalidates only incompatible PDF export checkpoints before committing a new mode. This changes search/copy text, never scan pixels, and is not an engine-accuracy claim.

## 2026-11-12 — Mixed fonts without silently discarding searchable characters

MUOCR-008 changes only the independent MuOCR project. Split normalized lines by Unicode code point, cache choices within one open PDF batch, and reuse open compatible font resources. Group adjacent identical font instances, measure the total advance, and keep one text position/common horizontal scale across the line. Keep explicit subsetting and exact ToUnicode maps before the incremental save, with the resolver open through the save. Unsupported code points produce a page/character/U+ error and retain OCR pages; do not copy upstream's blank substitution policy.

Normalize Unicode whitespace and strip control/FORMAT characters at export, without changing raw OCR JSON or the optional simplification policy. v2 text-style markers invalidate v1/untagged PDF checkpoints once so one PDF never silently mixes normalization strategies; new markers still use the existing durable replacement helper.

Batch cleanup holds maintenance ownership and every selected task's cleanup lease for the whole operation. Revalidate all task statuses/pending outputs before deletion, operate only on the confirmed directory snapshot, persist legacy-discard decisions before each delete, and stop with an explicit partial count on failure. Input/output documents and font caches remain outside work-root deletion. Existing single cleanup delegates to the same path.

Keep the verified build-time font download outside declared task outputs, under ignored project `.gradle/muocr-fonts/<blob>.ttf`; Gradle deletes stale outputs before retrying failed tasks. Validate the original pin before reuse and never package leftover download temporary files. No new dependency, engine, upstream write, or emulator instance is introduced.

## 2026-11-12 — Public WorkManager boundary and bounded CI storage

MUOCR-009 keeps document outcomes private to the app (Completed pages / Failed error). Queue control flow never casts WorkManager's library-internal Result.Success/Failure or reads their restricted outputData. Legacy worker requests adapt using public Result factories at doWork's edge, preserving pages and error/stage/page/total fields, error truncation and all output-finalization/cancellation protections. The formerly unreachable Retry fallback is replaced by an exhaustive typed branch.

Gate independent-repository CI on unit tests, full lintRelease, Release assembly and package/signature verification. Normal push/PR runs produce summaries without artifact uploads or new Gradle cache writes. Upload the APK only for a workflow_dispatch explicitly selecting upload_apk, with one-day retention. Do not alter munote workflows/artifacts or the user's account billing settings. User explicitly chose public visibility for mu23XR/MuOCR.

## 2026-11-12 — Reuse the existing GitHub connection

The user initially required GitHub-side repository selection limited to MuOCR. Inspection found installation163265090 selects all repositories; a local URL allowlist is not equivalent to narrow authorization. After being informed, the user accepted all-repository access and requested reusing that connector. This supersedes the earlier restriction; no installation change or new GCM OAuth/device credential is needed. Publication writes remain limited to mu23XR/MuOCR, and munote must not change. Repository creation succeeded through authenticated Chrome; source upload and successful CI used the existing connector. Verify complete tree/blob identity because Git-data API commit hashes differ from local history. Preserve original local main and use codex/published-main tracking origin/main for future work. Documentation-only completion records may use [skip ci] after the code/workflow commit has passed all checks; never use it to bypass a failing code change.
