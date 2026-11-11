# MUOCR-009 — 完整 Lint、独立 GitHub 发布与 CI

Owner: Codex. Claimed: 2026-11-12. Status: local implementation and full checks complete; public MuOCR repository created; source upload/CI in progress using the existing connector.

## 授权与边界

用户明确要求修复6个Lint错误、上传到独立MuOCR仓库并将CI跑通；munote不得改动。沿用MUOCR-001已确定的公开 `mu23XR/MuOCR` 目标。只上传本项目已跟踪源码、测试及文档，不上传构建/缓存/本地配置/私密凭证，不触碰munote文件、Actions或其他外部状态。用户已授权完成仓库创建/推送及CI运行，无需再次请求发布确认。

开工 main `004273a`，工作区干净、无remote。已阅读AGENTS、STATUS、MUOCR-001/008与最新交接。按review-optimize的基线/最小实现/回归流程修复，不屏蔽Lint、不添加依赖、不改OCR引擎/页格式/完成输出保护。

## 诊断与计划

- 6个RestrictedApi来自队列解析WorkManager内部Result.Success/Failure及outputData。将文档处理结果与WorkManager边界分开，内部传页数/失败信息，只在doWork边界用公开Result工厂方法转换；保留原失败截断、stage/page/total字段、暂停/取消传播与完成输出保护。
- 当前CI仅测试/构建/验签，没有完整Lint；每次推送默认上传约82MB APK并保留30天，会积累用户Actions存储。新增完整Lint门禁，普通CI只保留摘要，APK上传改为明确手动选择、短保留期；不清理munote产物或改账号计费设置。
- 先跑本地基线与故障证据，再修复、验证、提交；检查目标仓库和认证，再创建/非强制推送独立仓库；核对远端文件与提交、等待该提交的云端CI成功，失败修复后复验。

## 验证基线

本轮正式Gradle基线已重跑：单测119/119；完整Lint复现6错误/23warnings（51s），日志 `.tools/muocr009-baseline.log`。首次命令因PowerShell对未引用的-P参数拆分失败，已修正整段引用后重跑；不是应用错误。

## 实现节点

- Worker新增私有Completed/Failed结果，队列直接使用类型安全页数/错误；旧请求在doWork边界转为公开Result工厂。保留600字错误截断、stage/page/total元数据、协程/暂停/取消传播与完成输出保护；删除原不可能由runDocument返回的Retry兜底。
- 独立版递增1.1.9/code15。CI新增完整lint、明确安装SDK36/build-tools35、30分钟超时；正常push/PR不上传任何artifact、不写Gradle远端缓存，仅输出摘要。手动勾选upload_apk才上传APK且只保留1天。
- GitHub connector登录mu23XR，但无建库工具；Chrome已打开github.com/new，用户已手动完成双重验证。Git本机没有已存GitHub凭据。将优先使用现有connector的Git数据能力上传，避免额外凭据；Chrome输入偶有超时，需重新观察确认界面，不盲目重复提交。
- 最终本地 `testDebugUnitTest lintRelease assembleRelease --offline --no-daemon` 成功（2m23s），119/119、**完整Lint0错误/23warnings**。没有SuppressLint/Lint基线。CI YAML已解析检查。见 `.tools/muocr009-local.log`。
- 用户明确选择**公开**。Chrome浏览器控制最终因无法可靠确认当前URL而被工具强制停止，已终止所有UI操作。用户要求自行建库，故改用本机GCM设备授权+GitHub API/普通git push；不再要求用户手动建库。GitHub连接器没有建库端点，不能代替授权。
- 初次GCM授权未沿用进程现有代理，等待后已中止；仅为该次命令指定已存在的127.0.0.1:7890代理、未改全局网络，设备授权已返回并等待用户完成。授权码/令牌不写入项目记忆或Git。
- 用户询问是否已有授权后，停止旧设备码流程并补充核对：通过Git凭据接口分别查询指定/默认GitHub用户均无可用凭据；标准GitHub CLI配置/程序不存在。现有GitHub connector登录有效，但它的授权不会导出到本机Git，仍缺建库API权限/本机认证。未读取或记录其他服务凭据，未创建/覆盖远端仓库。
- 已准备忽略目录中的create_repo.py：仅可用现有Git凭据访问GitHub本人身份并创建公开mu23XR/MuOCR；核对账号、拒绝非空或可见性不同的既有仓库、禁止重定向转发凭据。不包含令牌，认证完成后可直接执行。当前APK为1.1.9/code15，SHA256 `bb09862449bc6a60104f43ee2641785b7a76a67a53787af137ffbee26ee79f22`，原证书验签通过。

## 下一步

用户最初要求GitHub端只授权MuOCR；检查现有GitHub App安装163265090的repository_selection为all后，已明确说明这不是仓库级限制，也不能把本地URL白名单当作授权范围。随后用户明确回复“全部都可以访问吧”，授权复用现有连接并要求最后说明其他对话的使用方式。这一最新指示取代先前仅选MuOCR的条件；不再等待更改安装范围，不新增GCM OAuth/设备码。旧create_repo.py方案不再作为当前下一步。

Chrome已成功创建公开空仓库mu23XR/MuOCR（ID1454335693）；API确认private=false、default_branch=main、branches=[]。页面输入和提交后的观察多次超时，但复核API确认创建已成功，不重复创建。随后Windows工具因无法可靠确认当前网址而强制停止，已结束全部UI操作。GitHub插件无修改安装授权范围的接口；已请求用户在https://github.com/settings/installations/163265090将All repositories改为Only select repositories，仅保留MuOCR并保存，不要求令牌/设备码。

本地唯一origin已配置为https://github.com/mu23XR/MuOCR.git。应用代码和本地测试结论不变，不为文档/远端URL修改重复构建。

按最新授权使用现有connector上传已跟踪的独立源码；不新增本机凭据、不写入其他仓库。空仓库可先用create_file初始化，再用Git数据接口原子提交完整树；须逐文件/整树核对与本地待发布快照一致，记录远端新提交与本地实现c2ff322的对应关系，保留本地历史。随后跟进对应远端提交CI到成功；原395页/物理1GB不因云端CI绿而视为已验收。

## 设备码风险核对（只读，2026-11-12）

用户询问授权码和风险。已查[GitHub设备授权流程](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow)、[OAuth范围](https://docs.github.com/en/enterprise-cloud%40latest/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps)与[撤销授权](https://docs.github.com/en/apps/oauth-apps/using-oauth-apps/reviewing-your-authorized-oauth-apps)。设备码用于把网页批准与本机工具关联；批准后工具可获得访问令牌。码默认15分钟过期，不表示已授予的令牌同时失效。旧本机流程已停止，不等同于已在GitHub撤销任何既有授权；旧码不要继续使用。

本轮只读核对和更新本工作项/交接，未新发设备码、登录、创建或修改远端/权限，未改代码；这两份文档更新尚未提交。后续不可用“正常登录工具”代替实际权限核验。

用户追问“是否只有本机能操作”：Windows Credential Manager默认在本机存放凭据，不表示普通OAuth Bearer令牌绑定该电脑/硬件/IP。令牌若被取出复制，其他设备也可能在有效权限内使用；其他设备不会因此自动登录。仅限仓库、最小权限与失效/撤销应分别核验，不能用“本机保存”保证“只限本机”。官方依据为[GCM凭据存储](https://github.com/git-ecosystem/git-credential-manager/blob/main/docs/credstores.md)及[Bearer令牌定义](https://www.rfc-editor.org/rfc/rfc6750#section-1.2)。未新发码或修改认证状态。

## 上传与首轮云端诊断

按用户最新全仓库连接授权，使用connector初始化README并发布完整快照；没有新增Git/GCM凭据。远端main为`a5b8e84509939272e1a42a4dfd9b0bc301143d1d`，对应本地`77f23b4f99e49dd56ac349600c602319778d3140`，完整树SHA均为`3178b4e481f8a8700b3ea4cf3c572f255c9dfbeb`。远端递归树66文件、不截断、逐文件路径/mode/blob SHA全部一致。仅故意公开的旧测试签名为二进制，构建/缓存/本机配置和私密凭证未上传。

首轮CI https://github.com/mu23XR/MuOCR/actions/runs/43077565804 在setup-android阶段失败，job130721302270；日志明确`Failed to find package 'tools'`，Action默认安装`tools platform-tools`，因此尚未执行应用测试/构建。认领本工作项下的CI兼容修复：显式仅安装platform-tools，保留SDK36/build-tools35安装与原测试/lint/build门禁，不通过屏蔽步骤取得绿色状态。

已按Action官方action.yml/README支持的packages输入仅选platform-tools，并关闭冗长许可证原文输出；未改SDK许可证处理行为。YAML解析/断言与git diff --check通过，完整lint门禁保留。仅CI配置和交接文档变化，不重复本地应用构建；下一步以真实云端重跑验证。
