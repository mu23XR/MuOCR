# MUOCR-002 — Further improvement review

Owner: Codex. Date: 2026-10-28. Status: review complete; recommendations only, no implementation authorized.

## Scope

User asked what else should be added or improved. This authorizes inspection and recommendations, not application code/configuration changes, publication, or implementation of features. Project memory documentation is updated as required by contributor instructions.

Base: main `3d8ba65`; clean working tree at start; no Git remote. MUOCR-001 remains paused for its separate publication/acceptance work.

2026-10-28 后续授权：用户要求继续优化，R1/R2/R7 已转入 MUOCR-003 实施与验证。本记录保留原始审查证据，其他建议尚未实施。

## Review approach

Review queue/checkpoint durability, task lifecycle and file cleanup, large-PDF resource/identity handling, user-facing recovery, and reproducible builds/tests. Reuse existing test evidence; no full build or performance benchmark is being rerun for a read-only audit.

## Findings

## 建议优先修复

### R1 / P1：备份恢复后的写入会破坏唯一有效备份

位置：`OcrQueueLedger.kt:338,355-358`；现有测试 `OcrQueueLedgerTest.kt:383`。

主队列 JSON 损坏时，`read()` 能使用有效 `.bak`。下一次编辑却会先把损坏主文件复制覆盖到 `.bak`，再尝试提交 `.new`。如果提交失败，`.new` 会在 finally 被删除，主文件与备份均损坏；如果进程恰好在覆盖备份后退出，读取路径也不会使用 `.new`。该提交顺序从代码可确认；本轮未现场注入故障。Windows 替换失败是上轮已有实测证据。

建议：保留经过验证的旧版本，统一经过目标平台验证的持久化替换协议，不能采用删除旧文件后再重命名的办法。增加“从备份恢复后再修改”的回归，以及每个提交步骤失败/中断后仍至少有一个有效版本的故障注入测试。验证时同时解决或明确隔离原有 Windows 替换限制。

### R2 / P1：旧清理线程可能删除重试任务的新缓存

位置：`MainActivity.kt:36,129-133,346-351,394-396`；`OcrQueueLedger.kt:266-275`。

`cleanupInProgress` 属于 Activity 实例，重试的 contains 检查与后台清理取得标记、检查 CANCELLED、删除目录并不构成同一原子操作。可发生：重试读到“不在清理” → 清理线程取得标记并读到 CANCELLED → 重试改为 WAITING 并唤醒 Worker → 清理线程删除该任务目录。Activity 重建还会产生互不共享的集合。风险是丢失断点或让新运行任务失败；这是静态可行的交错，尚未真机复现。

建议：把清理所有权与重试/认领状态转换放在同一任务协调机制中。清理持有所有权期间，重试应拒绝或排队；必须覆盖 Activity 重建。用线程屏障固定上述顺序验证，不需要增加整个应用架构。

### R3 / P2：删除失败后会丢掉重试清理所需的记录

位置：`OcrQueueScheduler.kt:68-78`；`LargePdfOcrWorker.kt:106-109,148-149`；`MainActivity.kt:414-416`；`OcrQueueLedger.kt:275`。

清理函数忽略异常和删除返回 false。调用方随后清空输出 URI，或者先移除任务再异步删除目录。权限撤销、提供者故障、磁盘删除失败时，可能遗留不完整 PDF 或大缓存，界面缺少待处理错误与重试入口。

建议：返回明确结果，保留 URI/任务目录及待清理状态直到成功；提供重试清理和可见的失败提示。完成任务的用户 PDF 不应被缓存清理删除。覆盖删除 false、异常、重启及重新授权。

### R4 / P2：选新输出文件夹无法修复旧任务

位置：`MainActivity.kt:53-55`；`OcrQueueScheduler.kt:54-64`；`OcrQueueLedger.kt:266-275`。

选文件夹只改变后续任务的全局 outputTree；已入队任务保留旧 treeUri，重试不替换它。旧目录被删除或不再可写时，用户按照错误提示重新选择目录，旧任务仍会再次写旧目录而失败。

建议增加单任务“更换输出文件夹并续做”，在任务不运行时安全更新目的地并保留 ID、OCR 页缓存和 PDF 导出断点；同时处理残留输出的清理记录。

### R5 / P2：界面重建会丢失识别精度与选择流程状态

位置：`MainActivity.kt:34-35,84,97-102,195,206`。

2800px 精度和 openFilesAfterFolder 只保存在实例字段。文件选择器期间旋转或系统重建 Activity 后，返回结果会按默认 2200px 入队，或不再自动继续打开文件选择器。建议保存/恢复这些状态或持久保存精度偏好；增加 Activity 重建场景验证。

### R6 / P2：空间检查太晚，可能完成 OCR 才发现不能导出

位置：`LargePdfOcrWorker.kt:369-375,529-538,610-615`。

初始检查只要求一份源副本加少量预留；OCR 完成后才检查第二份完整 PDF 的空间。若最终目标与工作目录处于同一卷，还需考虑最终输出。界面已有通用 4GB 提示，但没有按当前文件大小及实际可用空间预检。

建议：OCR 开始前显示和检查应用工作卷所需空间，区分原件副本、导出副本及预留；SAF 目标容量不可查询时明确显示未知，不声称保证足够。继续保留阶段内检查，避免运行中空间变化。

### R7 / P1：完成后的二次状态写入失败可能删除完整输出

位置：`LargePdfOcrWorker.kt:142-144,170-175,260-264,289-292`；`OcrQueueLedger.kt:183,325-328`。

`runDocument()` 在输出复制成功后记录 DONE，再设置 completed 并清理私有工作目录，返回 Success。外层 `runQueue()` 收到 Success 又执行一次 `ledger.finish(DONE)`，该方法即使状态已完成也会重写文件。如果第二次写入因 I/O/空间等失败，外层 catch 会调用 deleteIncompleteOutput 删除实际上已经完整的输出，此时 OCR 和导出缓存也已清除。该异常路径可直接从调用顺序确认；本轮未现场注入故障。

建议：只由一处负责持久化完成状态；导出成功后的状态/清理失败应当被识别为完成后的收尾问题，不能再套用“失败导出删除不完整输出”。增加只让第二次 finish 写入失败的聚焦测试，确认完整输出保留，并清楚呈现待收尾状态。

**下一轮修复顺序建议：R7 → R1 → R2，再处理 R3–R6 和 Android 验收。** 优先保护完成输出与断点，避免新功能扩大这些边界。

2026-11-05更新：MUOCR-006已补齐R3的私有工作缓存管理、删除失败持久标记及显式重试入口，并保留输入/已导出的PDF；SAF输出删除失败后的URI持久追踪仍未实现，不将整个R3宣称完成。详见MUOCR-006。

## 验收与工程保障

- **优先补真实 Android 验收。** 运行时是 PDFBox-Android 2.0.27.0，而当前 PDF 测试使用桌面 PDFBox 2.0.31（`ocrtool/build.gradle.kts:99-104`），未执行真实 Worker、ML Kit、Android 字体解析和 SAF。583 页测试使用空白/简单文本页，不等于 1GB 扫描 PDF。先小文档验证暂停/恢复、进程终止、串行多文件、输出失败续做，再跑用户真实大文件；检查输出可打开、页数/可见页面、中文搜索、内存和空间峰值。
- **补齐完整测试基线。** 上轮完成的 JVM 对比为原版 11/30 通过、优化版 19/38 通过，相同 19 项 Windows 替换失败；这既不是 Android 已失败的证据，也不是恢复链路已验收。生产 Gradle 单元测试任务被停止且没有 XML 结果。应在 Linux/Android 环境验证，并使 Windows 开发测试具备可用基线。
- **覆盖真实故障边界。** 现有 `IncrementalPdfPatchTest.kt:73` 手工模拟部分追加，未覆盖实际 Worker 中“pending 写入、追加/fsync、状态提交、pending 删除、SAF 输出 write/flush/close、完成后的收尾”所有异常点。逐点注入失败，确认无重复文字层、可回滚到有效 PDF、保留完整工作 PDF并只重试导出。
- **统一构建入口。** 仓库没有 Gradle Wrapper；CI 固定 8.11.1，本地已成功构建使用 8.13。可增加固定版本及校验值的 Wrapper，并让本地/CI 使用同一入口。
- **校验签名身份。** CI 目前验证签名有效及包名（`.github/workflows/android.yml:41-42`），可增加预期证书 SHA-256 对比，防止误换有效密钥后依然通过。保持现有证书 `293258f59770e0fd5e75f6497e506d9c95dfcf82c755de66dd76ded54a467d63`；本次不建议擅自更换历史签名。

## 可考虑增加的实用功能

1. 缓存管理：显示每个任务的空间占用、保留原因、待清理错误，提供安全清理已完成/取消任务缓存的入口；不得清理活跃任务或误删用户输出。
2. 输出定位和分享：显示各任务实际输出目录/文件名；现有“打开 PDF”之外可分享 PDF、定位目的地和复制错误详情。
3. 空间预检与阶段耗时：显示已用时间、当前阶段及可用空间；时间预估需以真实处理速率为依据，避免固定承诺。
4. 批量导入反馈：把文件名/权限查询放到后台，按文件报告成功或失败，避免单个文件失败导致整批不入队；当前全有全无逻辑位于 `MainActivity.kt:77-89`，变更前需明确产品行为。

## 较低优先级的性能改进

`MainActivity.kt:114,265,288-374` 每次进度变化都会在主线程读队列、重建全部卡片。任务较多时可按 ID 更新变化行，并异步读快照/导入元数据。收益为基于控制流的预期，未在本轮测量，不宣称具体速度提升。

## 需要先验证的边界建议

- `LargePdfOcrWorker.kt:467` 的 ML Kit 等待和 `:722-724` 的输入/输出读写没有截止时间；暂停/取消只在阻塞调用之间检查。若某次调用长期不返回，不会触发现有进程异常重启隔离。尚未复现挂起；先补最近进展、耗时和复制字节诊断。设计超时必须先确保识别任务停止访问位图，才能回收位图/启动下一个任务，不能简单超时返回。
- `LargePdfOcrWorker.kt:788` 将缩放下限固定为 0.001，极端页面尺寸可能超过 4.5MP 设计上限。建议增加超大页面边界计算测试，并检查像素上限；这是边界风险，未用真实 PDF 触发，不应描述为普通文档必然 OOM。

## 边界

仅审查和记录，未修改应用代码、Gradle/CI 配置、签名或外部仓库，未安装依赖或运行新构建。现有多文件串行、两阶段 OCR/导出、分批资源释放策略继续保留，待真机指标明确后再决定是否改变。上述建议尚未实施。

验证方式：三路并行只读审查，并由主 Agent 核对关键调用顺序、状态转换和现有测试。仅复用上一轮已保存的测试/构建证据，不把静态风险当作本轮已复现缺陷，不声称本轮新增测试通过。下一步需用户授权实现具体建议，随后创建修复工作项、补聚焦回归并按原包名/签名验收。
