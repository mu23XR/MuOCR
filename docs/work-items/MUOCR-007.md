# MUOCR-007 — 对比 munote 新发布版与独立版

Owner: Codex. Date: 2026-11-05. Status: comparison complete, documentation only.

## 范围与来源

用户问“和 munote 新改的 muocr1.1.14 有什么区别”。查询时 main 与最新正式发布实际均为 **1.1.4 / code10**，按这次新发布比较；不声称已排查所有其他分支。

- 上游：`mu23XR/munote` main/release目标 `569517c45f3130537cd1801e75cdbca34d306b1f`；MuOCR功能提交 `64754ff55e259c16953beb6fdcf8446366b390cc`；[发布页](https://github.com/mu23XR/munote/releases/tag/muocr-v1.1.4)。仅通过GitHub connector读取相关模块文件、提交/发布/CI元数据，没有克隆MuNote或复制其应用源码。
- 本地：开工 main `ee36574`，工作区干净，MuOCR **1.1.7 / code13**；APK SHA256 `4592acdabe6b66fe2888b6270029f8df58261b59d666b58f95f83bf49047793a`。本轮未改应用代码、配置、版本、依赖，未运行/安装上游APK，未启动模拟器或触发Actions。
- 两条开发线共同来源为上游1.1.3 `91bb4bb...`，较大的本地版本号不表示包含所有上游新功能。

## 先纠正字体判断

0.1.3本来已打包Noto Sans SC TrueType，并非那个版本没有内置字库。[0.1.3字体选择代码](https://github.com/mu23XR/munote/blob/3f399670c079115f1006c88a22ebc81a9c73875b/ocrtool/src/main/java/dev/munote/ocrtool/PdfSystemFontResolver.java)已经调用prepareBundledFont。此前将用户293/395页报错解释为“系统CJK依赖已被新版内置字库解决”不完整，应撤回这个根因判断。

本地实际只证明了普通合成文档与指定中文字符的字体/Unicode导出正确；仍整行调用fonts.resolve(text)。用户原始395页未提供，失败行可能有该字体不覆盖的混排符号，不能宣称原失败已修复。上游1.1.4新增的逐字符字体分段更有针对性，但同样未经该原文件验证。

## 对照

| 项目 | munote 1.1.4 | 独立版1.1.7 |
| --- | --- | --- |
| OCR引擎与基本流程 | ML Kit中文16.0.1；逐页识别；450万像素上限；每批最多16页增量导出 | 同引擎/基本流程；额外对平衡2200模式的低置信度页面补识2800，保守按行选优，可能增加耗时 |
| 混排字体 | OcrFontRuns逐字符选可嵌入字体并合并同字体片段；不支持字形用空格替代，仅一行所有汉字都无法解析时抛错 | 按整行找可完整覆盖文本的字体，不兼容行仍会失败；无逐字混排回退 |
| 简繁/不可见字符 | 搜索层强制转简体；清ISO控制、空白、FORMAT零宽字符；ICU失败被捕获并用13字映射作兜底，无用户切换 | 新任务默认简体但可关闭，旧队列默认原文；闲置任务可切换，选项持久化；空白规范化；ICU缺失明确报错，保留缓存；未做同样的FORMAT字符清理 |
| 缓存界面 | 首页总量及工作/字体拆分；清完成/取消、单项、全部、旧版/孤立目录；全部/旧版双确认；可清字体副本；不显示页数或完整路径 | 单独缓存页，显示各目录页记录数、逻辑大小和实际路径；逐份清理，保留字体；无批量全清入口 |
| 清理状态 | 可确认放弃WAITING/PAUSED/FAILED后标CANCELLED，再执行删除 | 要求任务闲置；保留暂停/失败状态并清进度，先保存discardLegacyCache，再清目录；有持久cleanup.pending与显式重试入口 |
| 旧缓存接续 | 旧页基本格式兼容；仅当前首选存储根；新pages目录非空就不继续迁移，逐文件复制错误被吞 | 两处存储根查找/重复身份报错；每次原子补齐所有缺页；明确报错；手动放弃的任务不再导回旧页 |
| 完成输出/断点保护 | 保留1.1.3的双重DONE、renameTo和备份写法；没有本地的完成输出核对与共享清理租约 | 单次完成提交，完成输出在晚到暂停/取消/收尾失败时保留；异常恢复按字节核对；验证过的备份及原子替换；清理/重试互斥 |
| 精度选项 | Activity字段默认2200，未持久化2800选择 | 精度选项保存，已实测重启/旋转；任务独立保留精度 |
| 发布/验证 | GitHub已发布APK，CI单测/组装/签名步骤成功；7个测试文件共38个@Test声明 | 本地APK，106项正式Gradle测试与3/48页及293页合成缓存验收；完整lint仍有6项既有RestrictedApi错误；未发布独立GitHub仓库 |

两边都没有完成用户真实395页/1GB设备验收。本轮没有对同一文档实跑两版，不给整体准确率、速度或内存优胜结论。测试声明数量也不等于识别准确率。

## 代码证据与静态风险

- [上游字体分段](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/ocrtool/src/main/java/dev/munote/ocrtool/OcrFontRuns.kt#L59)：缺字实际写入空格；Worker第773–775行直接showText(run.text)，没有另存ActualText恢复缺失字符。容错代价是复制/搜索可能漏符号，部分汉字缺失也可能被替换，不能照搬后宣称文字完整。
- [上游文字规范化](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/ocrtool/src/main/java/dev/munote/ocrtool/OcrTextNormalizer.kt)：强制转换及控制/FORMAT处理。本地OcrChineseText/OcrTextStyle提供开关与模式检查点，行为不同。
- [上游旧页迁移](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/ocrtool/src/main/java/dev/munote/ocrtool/LargePdfOcrWorker.kt#L297)：第310行新目录非空即不再复制，第313行runCatching隐藏失败；未包含MUOCR-006补齐逻辑。
- **完成后误删风险仍在。** [上游Worker](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/ocrtool/src/main/java/dev/munote/ocrtool/LargePdfOcrWorker.kt#L142)内层259–264提交DONE，finally289–292清缓存，外层142再finish(DONE)；第二次写入异常会进入170–175的deleteIncompleteOutput。这是MUOCR-003曾修的路径，源码仍存在；本轮未故障注入上游APK。
- **清理/重试竞态。** [授权](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/ocrtool/src/main/java/dev/munote/ocrtool/OcrQueueLedger.kt#L259)先标CANCELLED再释放锁；[清理](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/ocrtool/src/main/java/dev/munote/ocrtool/OcrCacheManager.kt)在锁外删除；retry316–327没有共享租约限制，Activity的cacheCleaning只控制当前对话框。静态上可交错为重试认领/创建新数据后被旧删除清掉。本轮未运行并发复现。
- **备份风险。** [上游Ledger](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/ocrtool/src/main/java/dev/munote/ocrtool/OcrQueueLedger.kt#L386)从backup恢复后，write409仍可能用坏primary覆盖backup；与本地MUOCR-003不同。
- 上游单项清理保留旧版目录，又没有discardLegacyCache字段；对该任务重试仍可能从旧目录导回已放弃页。cleanAll确实也删旧目录，这项差异针对单项清理。

上游CI [42006363710](https://github.com/mu23XR/munote/actions/runs/42006363710)的Test disk-backed PDF incremental writer、Build MuOCR APK、Verify APK均成功。[工作流](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/.github/workflows/muocr.yml)运行标准Gradle单测/Release构建，不包含设备验收或完整lintRelease。38是源码@Test计数，未下载JUnit XML；不冒称本轮重跑38/38。

## 安装与后续建议

两边源码applicationId同为dev.munote.ocrtool，签名keystore的Git blob同为08fa255c54cf6ff180683ffb2091f2370d66dda3，字体blob也相同。独立的是本地源码项目，安装身份没有另起一套。

[上游构建配置](https://github.com/mu23XR/munote/blob/569517c45f3130537cd1801e75cdbca34d306b1f/ocrtool/build.gradle.kts#L71)code10低于本地code13。已装本地1.1.7后常规回装上游1.1.4会受[Android降级限制](https://developer.android.com/studio/publish/versioning)；不应通过卸载来换包而丢失缓存。未来整合需要统一递增code并处理队列/导出文字策略标记。

建议保留本地完成输出/持久化/清理互斥保护，借鉴上游逐字字体分段、控制字符清理及批量缓存入口；对缺字明确提示并保留原识别信息，避免不知情地以空格代替。只是建议，未合并代码。下一步若用户授权实施，需先设计混排缺字策略并用真实失败页验收，不以版本号或测试数量代替结果。
