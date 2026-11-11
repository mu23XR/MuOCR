# MuOCR contributor instructions

- Read `docs/STATUS.md`, the relevant `docs/work-items/` entry and latest `docs/handoffs/` before work. Claim a work item before changing code.
- This repository contains only MuOCR. Do not copy MuNote application sources or its complete repository into this workspace.
- Keep the Android package `dev.munote.ocrtool`, queue/checkpoint formats and legacy signing identity compatible with existing installations.
- Prefer small, behavior-preserving implementations without new dependencies. Keep per-page checkpoints, serial execution and PDF resource lifetimes intact.
- Use UTF-8. Do not silently swallow new configuration, persistence or external-operation errors.
- Before changes inspect branch, status and recent commits; preserve unrelated changes. Record evidence, tests, deferred issues and a short handoff at milestones.
- Verification: `gradle :ocrtool:testDebugUnitTest :ocrtool:assembleRelease` with JDK 17+, Android SDK 36 and Gradle 8.11.1. Never claim real-device 1GB PDF validation from JVM tests.
- `signing/muocr-legacy-test.keystore` is the original deliberately public test key, retained only for upgrade compatibility. Never commit private signing material, tokens, local.properties, caches or build outputs.
- MuMu acceptance uses existing instance **1**. Never operate instance **0** or create another instance unless the user explicitly asks. If the main program is absent, start existing Windows task `MuMuAutoLaunch`, then use `D:\game\moniqi\MuMuPlayer\nx_main\MuMuManager.exe control -v 1 launch`; poll `info -v 1` for `is_android_started: true` and use its explicit adb serial. Do not default to Windows desktop automation for startup.
