# MUOCR-003 — 完成输出保护与断点持久化

Owner: Codex. Date: 2026-10-28. Status: complete for the bounded local fix pass.

## Scope

用户授权继续优化。此轮完成 MUOCR-002 的 R7（完成后的异常不得删除完整输出）、R1（备份恢复后的安全提交）与关联 R2（清理与重试互斥），并统一相关检查点的文件替换以验证 Windows 原有失败。保持包名、签名、串行队列、原有 JSON/断点格式及现有依赖。

持久待清理记录、输出目录重选、界面状态与空间预检仍保留在 MUOCR-002，后续分步处理。复审显示清理/重试竞态会绕过完成输出保护，因此本轮用最小共享租约一并修复，而不新增独立协调器。

## Plan

1. 用已有工具运行当前 38 项 JVM 测试并记录失败方法；不安装 SDK/依赖或启动模拟器。
2. 实现小型持久化替换 helper：同目录临时文件落盘后原子替换，不支持时返回明确错误并保留旧版本。只用经过验证的主记录更新备份。
3. 明确完整输出的提交边界；完成复制后发生状态/清理异常仍保留目标文件和必要恢复材料，移除重复完成写入。
4. 增加聚焦故障测试，运行完整测试对比并使用已有 Android 工具构建 APK；不自动安装缺失环境。

## Baseline

代码基线 f7f99d9，工作区开始时干净。当前基线已重跑：38 项中 19 通过/19 个 Windows 替换失败，与上轮失败集合完全一致。日志 `.tools/muocr003-baseline.log`，方法列表 `.tools/muocr003-baseline-summary.json`。

## 实现决定与阶段证据

- 新增小型 DurableFile：同目录临时文件写入/fsync后以 ATOMIC_MOVE + REPLACE_EXISTING 替换；不支持原子移动时明确失败，保留旧记录，不退回先删除再重命名。
- 队列只从经过完整验证的主记录生成原子备份；从备份恢复后保留该有效备份。PDF/OCR 检查点复用同一文件替换实现，不改既有记录格式。
- 完成状态只由队列提交一次；完成提交允许覆盖输出关闭后才到达的暂停/取消指令。完整输出随后才清理私有缓存，通知/清理失败不再触发失败输出删除。
- 复用现有目标 URI 与完整 PDF 导出 journal，在恢复时用固定大小缓冲区逐字节核对目标与 writing.pdf；确认相同后直接补交 DONE，不重做 OCR/导出。读取或检查点不明确时保留文件并报告错误，无需新增 sidecar 格式。
- 重试保留待核对的输出 URI，待核对完成状态使用明确的 FAILED 状态以支持重试，避免晚到取消将完整输出送入自动取消清理。
- 清理租约位于共享 ledger，同状态检查原子取得，文件 I/O 在队列锁外执行并在 finally 释放。重试/恢复/取消/移除/认领均遵守租约；Activity 重建不会产生独立的删除所有权。
- 带输出 URI 的取消保留 CANCELLING，由 Worker 在租约内先核对输出，再决定完成或取消，防止界面抢先删除恢复材料。全局暂停时也允许唤醒这项核对，不会启动新的 OCR。
- 旧单文件 WorkManager 请求同样在准备/删除之前核对已有导出；无法读取时保留文件，未打开写入的旧目标不会因准备失败被删除。
- 大文件恢复核对前进入前台模式；目标内容比较仅发生在异常恢复/待完成收尾，不对每个正常成功导出额外读一遍文件。
- 隔离验证已通过：持久化/队列/PDF journal 38/38；输出恢复 helper 12/12。最终整套回归及 Android 构建尚待统一执行，不能把两次子集数量相加当完整测试结果。
- 包名、证书保持；版本递增到 1.1.4/code10，便于覆盖安装并识别此轮修复。

## 验证阶段

- 中间完整 JVM 套件 67/67 通过，原先 19 项失败全部消除，新增 29 项通过。随后补充 4 项共享租约回归，最终套件 71/71 通过；中间日志 `.tools/muocr003-interim67-jvm.log`。
- 构建只使用本机已有 SDK/JDK/Gradle 与已缓存依赖，明确 --offline，并排除已独立校验的字体下载任务；不安装 SDK、模拟器或新依赖。

### 最终回归

- `python .tools/run_jvm_checks.py --all`：71/71 通过，0 失败；与基线 38 项相比，原有 19 项失败已消除，新增 33 项均通过。日志 `.tools/muocr003-final-jvm.log`；汇总 `.tools/muocr003-final-jvm-summary.json`。此 runner 使用已有 Kotlin 2.0.21 编译 JVM 类，不冒充生产 Gradle/Kotlin 2.2.0 测试。
- 实际 Gradle `:ocrtool:testDebugUnitTest :ocrtool:assembleRelease --offline` 因 PDFBox 2.0.31 的离线依赖索引缺失，在测试编译阶段失败，未得到该任务的测试结果。虽然已有同字节 JAR 并做了本地复用，未尝试联网、安装或改写 Gradle 元数据。日志 `.tools/muocr003-gradle-offline.log`。
- 单独离线 `:ocrtool:assembleRelease`：退出码 0，BUILD SUCCESSFUL，完整 lint 通过。已重新编译最新 Worker，使用既有 Gradle 8.13 / JDK 21、项目 Kotlin 2.2.0 与 SDK 36，未安装/下载工具或依赖。命令：`gradle :ocrtool:assembleRelease --offline -x :ocrtool:prepareMuOcrFont --no-daemon --no-watch-fs --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process`。

## 交付与限制

- APK：`ocrtool/build/outputs/apk/release/ocrtool-release.apk`，86,841,876 字节，`dev.munote.ocrtool`，1.1.4 / code10，非 debuggable。
- APK SHA-256：`b219379ccf912ab575ea369e0226ee36d9875acaa4fcedd2794bc70571dc8274`。
- APK v2 签名验证通过；证书 SHA-256：`293258f59770e0fd5e75f6497e506d9c95dfcf82c755de66dd76ded54a467d63`，与原安装身份一致；打包字体仍匹配原始 Git blob。
- 机器证据：`.tools/muocr003-verification-summary.json`；构建、测试、签名日志均在忽略目录 `.tools/`。所有本轮进程与下载/安装任务均已结束或从未启动。
- 尚未进行真实设备的 ML Kit/SAF 故障验收和用户 1GB PDF 全流程测试；不能把 JVM、桌面 PDF 测试和构建成功等同于这些验收。
- R3 持久待清理记录/缓存管理、R4 重选输出目录、R5 精度状态保存、R6 空间预检、耗时/分享等功能未在此轮实施；独立 GitHub 创建/上传继续待办。未新增依赖、未更改签名或原记录格式。
