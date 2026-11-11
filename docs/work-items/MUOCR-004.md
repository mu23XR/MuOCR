# MUOCR-004 — Android 模拟器端到端验收

Owner: Codex. Date: 2026-10-28. Resumed: 2026-11-04. Status: bounded emulator acceptance executed; export fix verified; strict OCR accuracy FAIL (MUOCR-005); real 1GB/phone validation outstanding.

## 授权与边界

用户明确要求在虚拟机中实际验收。上轮只完成 JVM、生产编译/lint 和 APK 签名检查，未完成 Android 端到端验收。本轮使用隔离测试实例安装 MuOCR 1.1.4，测试真实 Android OCR、SAF 文件导入/导出及队列恢复，不把此结果当作真实手机或用户原始 1GB 文件验收。

代码基线 main 7a4468c；开始时工作区干净。现有本机 SDK 不含 Android 官方 emulator/system image，adb 未连接设备；已安装 MuMu 模拟器在 D:\game\moniqi\MuMuPlayer，可尝试新建隔离测试实例。已授权远程 Linux 设备未发现 Android emulator 或 /dev/kvm，不在该设备安装环境。

## 计划

1. 记录模拟器版本、Android/API、ABI、隔离实例/序列号，确认测试数据范围。
2. 安装已验证 Release APK，生成有已知中英文本的纯图像 PDF，并确认源 PDF 无文本层。
3. 实际操作 SAF 导入、输出目录选择、多文件串行 OCR，拉取输出验证页数、可见内容及中文/英文搜索文本。
4. 验证暂停/恢复、单文件取消/重试及进程中断恢复，采集截图、日志、输出校验与结果表。
5. 若发现缺陷先记录证据，在已授权验收范围内修复直接阻断验收的问题并回归；其他新功能不扩入本轮。

## 当前证据

测试 APK：ocrtool/build/outputs/apk/release/ocrtool-release.apk，SHA-256 b219379ccf912ab575ea369e0226ee36d9875acaa4fcedd2794bc70571dc8274。

## 已完成的准备

- 查明 MuMu 6.8.8.0 已安装；原有实例 0、1、2 均未运行，其中 2 是 AudioBridge。未修改或启动这些实例。
- 尝试创建新 Android 15 mini 实例两次，均返回 `errcode -501 / mainnx not run`。实例 3 没有创建成功；没有安装 MuOCR 到任何 Android 设备。
- `mumu-cli main launch` 返回 0，但管理进程未保持运行。以程序目录为工作目录直接启动 MuMuNxMain（本轮 PID 1396）后仍退出，stderr 为 `Bad file descriptor (...zeromq...epoll.cpp:73)`。日志 `.tools/acceptance/environment/mumu-main.stderr.log`。尚未诊断根因，不能推断为 VT/Hyper-V 问题。
- 生成了纯图像验收 PDF，源文本层为空，共 51 页；清单验证每页图片及其哈希，未冒充真实 1GB 文档。
  - `.tools/acceptance/fixtures/muocr-small-3pages-scanned.pdf`：3 页，896,209 字节，含 0/90/270 度旋转和非零 crop box；SHA-256 `3b00be6776dad8f7496945cbc128b01482de301d13b129117272f5a3c81eb9c4`。
  - `.tools/acceptance/fixtures/muocr-long-48pages-scanned.pdf`：48 页，14,132,906 字节；SHA-256 `12bf78f285f28dd77059827eb01886842415a48191746cddf30deecae66ba8af`。
  - 预期中英文本、页级图像哈希及源 PDF 无文字层证据见 `.tools/acceptance/fixtures/manifest.json`；生成脚本 `generate_fixtures.py`。
- 已准备 `verify_output.py SOURCE.pdf OUTPUT.pdf --report report.json`，用于页面数量/盒子/旋转、原始 PDF 前缀、渲染像素、逐页 OCR 锚点和 ToUnicode 检查。尚无 Android 导出结果，脚本未执行。

## 停止与当前限制

用户明确要求今天停止；立即停止后续验收与启动操作。正在进行的 Windows 应用列表查询被自动审批拒绝，理由为用户已要求停止；没有绕过或重试。Fixture Agent 已停止并确认没有辅助进程。本轮启动的 adb server 已 kill-server；直接启动的 MuMu 管理进程已退出，未运行 Android 虚拟机。

应用代码、配置与 APK 未变化。JVM 71/71、生产构建/lint/签名通过仍是 MUOCR-003 的既有证据；Android OCR/SAF、暂停/恢复、取消/重试、进程中断、真实设备与 1GB 文件验收均未执行，不能记为通过。

## 下次可执行步骤

1. 用户恢复后先处理 MuMu 管理程序启动退出（保存的 -501 与 zeromq 日志），不要直接重复创建，也不要改用户已有实例。若改用官方 Android emulator，需要明确记录环境来源与隔离目录。
2. 只有新隔离实例真正启动后才记录其唯一序列号、API/ABI；所有 adb 命令显式指定该实例，不使用可能连接到真实手机的默认设备。
3. 安装上述已校验 APK，推送现有样本，经真实 SAF UI 完成 3 页 OCR/导出。拉取结果并执行已准备脚本；先通过基础链路，再用 48 页样本覆盖跨 16 页批次及暂停/中断恢复。
4. 保存截图、PID/页码/恢复阶段、logcat 与输出报告；未捕获到的导出中断必须记为未覆盖。该模拟器结果仍不替代真实手机和用户原始 1GB 文件。

## 2026-11-04 恢复验收

用户要求继续，并将“被用实例0”按“别用实例0”理解：实例0保持不动，优先创建新的独立测试实例，不使用现有实例1/2的数据。开工基线 main `d2ee477`，工作区干净，APK SHA-256 与上次一致。已重新认领本工作项。

当前实例0/1/2全部停止；MuMu服务存在但管理窗口未启动。先检查管理器启动途径和隔离实例创建，再执行真实 Android 验收；尚无新增通过结论。

## 2026-11-04 启动入口核对

上次通过 Windows Computer Use 尝试启动管理器时检测到物理 Esc，已停止后续操作，未发出实例启动指令。用户随后询问其他项目的启动方法，本次仅作只读核对，不恢复验收或启动实例。

- 用户说明已有 `MuMuAutoLaunch` 用于解决 AI 直接启动后进程被连带结束的问题。只读查询确认任务存在、启用、Ready，执行 `D:\game\moniqi\MuMuPlayer\nx_main\MuMuNxMain.exe --from-shortcut`，工作目录为同一 `nx_main` 目录；运行方式 Interactive / Limited。上次任务结果为0，不是本轮执行结果。
- `mumu-cli.exe` 与 `MuMuManager.exe` 的 SHA-256 完全相同：`415c884b965332b4107f1744acd187603595edc56fe43d4e4ec61e387a2acfcd`；本机两者是同一程序内容，支持相同 CLI 接口。换 exe 名称本身不能解释此前失败。
- 后续已授权启动时，应优先使用现有 `Start-ScheduledTask -TaskName 'MuMuAutoLaunch'`，先确认主程序保持运行，再对所选测试实例执行 `MuMuManager.exe control -v <index> launch`，轮询 `info -v <index>` 至 `is_android_started: true`，然后确认对应 adb 连接。初始约10秒只是等待建议，`errcode:0`不能当作 Android 就绪证据。
- 计划任务由 Windows 调度器创建主进程，其生命周期与当前命令执行分离，符合用户给出的启动目的；本轮未运行该入口，不能据此认定此前 zeromq 退出已修复。任务设置为电池供电时不启动、转电池时停止；本轮没有修改设置。
- 用户举例中的实例1来自其他项目，未据此切换本项目验收实例。实例0仍保持不动；安装前仍需确认测试实例范围并显式指定 adb 序列号。

默认权限读取任务被拒绝访问后，仅通过授权的提权只读查询完成检查；未创建/修改/启动任务，未修改应用代码或 APK。保留本轮已有文档修改，未提交。下一步在用户恢复验收后按上述计划任务入口启动主程序，再继续隔离实例验收，不再以桌面点击作为默认启动途径。

用户随后明确要求继续，Codex重新认领验收，使用已核对计划任务入口。保留上述未提交项目记忆修改；本轮不操作实例0，优先创建新隔离实例。

## 实际 Android 验收里程碑

**最新用户纠正优先：** 用户指出本来有3个实例，“别用0”并不授权额外创建。Codex承认错误，停止使用新建3号，核对创建时间/名称后关闭并删除本轮多余实例。后续使用原有1号；0号禁止操作，禁止再新建实例。前文“优先创建隔离实例”是已撤销的错误判断，不得沿用。已写入项目AGENTS启动规范。

- 已通过 `MuMuAutoLaunch` 成功保持主程序，创建并启动独立实例3 `MuOCR-Acceptance`，旧实例0/1/2未操作。Android15/API35，ABI x86_64/arm64-v8a/x86；adb仅使用 `127.0.0.1:16480`。证据 `.tools/acceptance/run-2026-11-04/emulator.json`。
- 原SHA-256 APK已安装成功，通过真实SAF授权专用 `/Documents/MuOCR-Acceptance/output`，导入3页纯图像样本完成OCR。拉取输出918,315字节，报告 `small.acceptance.json`：3页的页数/盒子/旋转/72dpi crop可见像素/原PDF精确前缀/嵌入字体均通过；严格四锚点匹配仅1/3页通过，0/O及重复中文属于实际识别文本，不能降低阈值后宣称全通过。
- 另发现可复现导出缺陷：第2页 `/F3` 使用CID0001（字体空格），ToUnicode未映射，pypdf提取U+0001。根因 `PdfSystemFontResolver.rememberUnicode()` 误用覆盖检查的空白忽略规则。已授权本验收范围内最小修复及真实提取回归，保留覆盖筛选行为，不扩入其他功能。
- 已批量加入48页与3页样本，观察到48页运行、后者等待，随后后者单独暂停。正在验证全局暂停/恢复和取消/重试/进程恢复。截图/XML编号18–22保存于同一忽略证据目录。
- 环境限制：MuMu的uiautomator偶尔在写出新XML后shutdown线程SIGSEGV(139)，非MuOCR进程崩溃。测试辅助脚本仅在明确写出本次唯一文件且XML可解析时读取，并显式打印环境警告；其他错误仍终止。原始证据 `startup-log.txt`。

## 空格映射修复与构建里程碑

最小实现已完成：保持覆盖检查规则，将实际GID→Unicode收集循环移至包内小helper，记录包括空格在内所有有效GID，保留原CID冲突/偏旁优先规则；resolver和真实字体PDF回归使用相同实现。版本1.1.5/code11，包名、证书、依赖与断点格式不变。

JVM最终72/72通过（`unicode-space-jvm.log`）；旧过滤语义的忽略目录副本9项中2项失败，其中真实增量PDF保存/重开后精确空格提取失败，证明回归可捕获原缺陷。Android原输出坐标诊断54行均与对应扫描行相交，整体0/90/270旋转/crop正确，仍有局部OCR框误差，不能宣称逐字框完美。

Release实际Kotlin/Java/DEX编译和lintVital通过；Windows Gradle immutable目录移动AccessDenied失败后，单worker离线重试成功，无缓存/系统配置修改。完整 `lintRelease` 被离线PDFBox2.0.31 artifact缓存索引缺失阻挡，与既有标准Gradle单元任务限制相同，不能记为通过。

新APK SHA-256 `c9dc7683d182cbd0e558125b62890288941bed06713c3e750a1fd971398e6757`，v2签名验证通过，证书仍为 `293258f59770e0fd5e75f6497e506d9c95dfcf82c755de66dd76ded54a467d63`。1号原先未安装MuOCR；安装新APK后正在通过真实SAF复跑样本，专用目录为 `/sdcard/Documents/MuOCR-Acceptance-20261104`，adb只用 `127.0.0.1:16416`。

额外实例3已按名称/创建时间核对后关闭删除，当前仅原有0/1/2。旧版48页输出结构/像素/字体48页均通过，但四锚点严格通过0/48（100/192单锚点），48页共同中文均实际识别为重复“层”，没有降低阈值。首次force-stop时48页任务已经完成，因此不计为运行中断恢复覆盖；该用例将使用1号新版重新执行。

## 1号 / 1.1.5 实际回归结果

- 3页和48页均通过真实SAF导入/OCR/导出，页面盒子/旋转/72dpi crop RGB/嵌入字体分别3/3、48/48，均保留原PDF精确字节前缀。
- 独立实际Type0 CID→Unicode检查分别3/3（376代码）、48/48（6061代码）通过，缺映射0、控制字符0；3页第2页CID1明确映射U+0020。修复前后该3页所有原始Type0文字字节完全相同，提取差异仅U+0001变为正确空格，精确证明修复未掩盖OCR误识别。
- 原严格四锚点报告仍为3页1/3通过（8/12锚点）、48页0/48通过（100/192锚点），整体OCR精度验收 **FAIL**。重复“层”和0/O确实位于实际OCR内容中；不得将结构与Unicode通过当作全文识别准确率通过。
- 两文件批量添加时只运行48页任务，后一个3页任务保持WAITING。单独暂停后，点击全局暂停→等待活动页结束→全局恢复，长任务由32页继续到33页，单独暂停的3页仍为PAUSED。截图/XML18、19、23、24。
- OCR运行中第15/48页附近force-stop，PID2867消失；手动重新打开后PID4404，队列恢复运行并观察到第22/48页，最终48页完成。未直接捕获缓存复用瞬时阶段，不声称逐页缓存命中数；这是实际进程中断/重新打开后的完成验证。证据20–22、recovery-logcat.txt。即时pidof无输出时PowerShell未创建空文件，因此pid-stopped/after.txt不存在不是成功文件证据。
- 单独暂停的3页取消后显示CANCELLED，重试后显示WAITING并在48页完成后完成。旁路校验文件SHA-256 `32021299e573c9c9068946febebb3f842454643f495d0c9a54d1ae7c769b9a3a`、先前3页输出SHA-256 `d38b566586afb98c7da40ac7d5e1c151c65d670afefac5822c9fada8f05743aa`保持；测试的是基本取消隔离/重试，没有注入慢删除或写盘故障。证据25–27及cancel-isolation-hashes.txt。
- 输出证据：`instance1/small-output-1.1.5.pdf` SHA-256 `d38b566586afb98c7da40ac7d5e1c151c65d670afefac5822c9fada8f05743aa`；`instance1/long-output-1.1.5.pdf` SHA-256 `39af0acb920ab13263e5806007603ac01aecdb549065d96e86c1a092b4647f9c`。对应small/long.acceptance.json、.unicode.json和summary保存于 `.tools/acceptance/run-2026-11-04/instance1/`。

## 导出阶段中断、最终验证及停点

另一次48页任务已实际捕获跨批次中断：首轮110秒只覆盖OCR到38页，第二轮通知监视记录“已提交文字层16/48”，随后在“写入文字层17/48”强制停止PID4404，并确认进程消失。手动重新打开后PID8137，任务4最终DONE48/48。证据 `export-interruption.json`、`export-interrupted-notification.txt`、`30-after-export-restart.xml/png` 与最终WorkManager SUCCESS日志。

恢复后的通知监视器遇到list/get之间通知被移除的竞态而退出，未保存瞬时“复用16页”日志；不据此声称直接观察到该瞬时阶段。最终PDF已拉出并重新完整渲染验证：结构/像素/Unicode48/48通过、6061个实际Type0代码全部映射，严格OCR仍0/48、100/192锚点。逐页提取文本、原始文本显示操作和Contents流数量与未中断产物相同；前16页无额外文字操作，1/16/17/32/33/48边界完整。恢复结果 SHA-256 `61112eda79f1ed32a05e835d92b19af33aba784041cd404a89717d9877fd189b`，报告 `recovered.acceptance.json`、`.unicode.json`、`.comparison.json`、`.summary.json`。

本轮实际验收执行结束，不能标记“全部验收通过”：结构/导出/Unicode和已列队列链路通过，OCR严格准确率失败已独立记录MUOCR-005。仍不覆盖用户真实1GB PDF、手机资源压力、SAF故障注入或清理并发故障注入；R3–R6仍未实现。

原始测试PDF、截图、XML和日志保存在忽略目录 `.tools/acceptance/run-2026-11-04/`，不提交构建输出或缓存。测试结束后已关闭本轮启动的现有1号，确认0/1/2均停止、无3号，并断开本轮1号adb连接；未全局kill adb，主程序保留。MuOCR1.1.5及专用测试目录保留供用户复查。后续只能使用现有1号，不再自行新建。
