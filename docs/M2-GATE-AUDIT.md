# M2 最终门禁审计

> 2026-10-05，Asia/Shanghai；分支 `feat/m2-accounts`；基线 `184ba300e8886ec63d216ad5aad1189977ea2ee1`。5/5 门禁 PASS，完整 M2 为 `VERIFIED`。本轮补齐两项历史版本直接回归，固定 Linux API182+Worker133=315，零失败/错误/跳过。

## 1. 范围与证据口径

对照 [路线图第 6 节](ForgeOJ-Roadmap.md#6-m2题库账号与学习主流程) 的五项门禁逐项验收。账号、题库、学习记录、私有作者草稿、双程序验证、不可变送审/撤回、输出预览/确认、官方题解访问和独立自测分别已有验收记录；本审计核对这些证据与当前源码，并补齐历史判题依据的直接检查。

公共审核的不可变候选版本属于 M2；CONTENT_REVIEWER 独立认证、批准/驳回和受控发布按路线图第 8 节及 [既定实施顺序](M2-REMAINING-IMPLEMENTATION.md#1-顺序与交付边界) 留在 M4。Redis/ES 和分布式限流同属 M4。开发 fixture 与合成官方题解快照只是可丢弃验收数据，不是已审核发布的真实题库；这些 V1 要求没有被删除。

本轮只新增测试和审计工具/文档，没有新增生产代码、迁移、权限、依赖或 HTTP/MQ 字段。已接受的浏览器、Worker 中断和 SMTP 记录保留原验收时间；本轮没有重新进行浏览器回放或再次发送邮件。

## 2. 五项门禁

| 门禁 | 直接证据与当前回归 | 结果 |
|---|---|---|
| 注册到 AC 的端到端测试通过 | [账号真实浏览器记录](evidence/m2-accounts/browser.json) 的新验证邮箱账号实际注册→激活→登录→QUEUED→FINISHED/AC，以及账号阶段 [完整审计](evidence/m2-accounts/audit.json)。当前全量回归再次覆盖 AccountFlow/HTTP/BusinessHTTP；最新 [自测阶段回放审计](evidence/m2-self-test/audit.json) 验证现有普通/回退页面 AC；[QQ SMTP 证据](evidence/m2-self-test/smtp-delivery.json) 记录真实接收、点击后 ACTIVE/emailVerified。三类记录来自各自实际运行，不能合称本轮同一条 SMTP 注册到 AC 回放。 | PASS |
| 题目版本变化不会篡改历史提交 | 新增 `SubmissionCreationIntegrationTests.advancingCurrentVersionPreservesOldSubmissionAndIdempotentReplay`：实际 HTTP 创建后，在一次性 MySQL 插入新版本并切换 current 指针，逐列比较旧 Submission/Task/version，旧 UUID 重试仍返回旧记录；新 UUID 使用新版本及不同资源限制，只有两个 Outbox。新增 `JudgeTaskSnapshotLoaderIntegrationTests.currentVersionAdvanceDoesNotRedirectExistingTaskSnapshotOrHiddenTests`：新 current 版本使用不同限制和数据摘要且没有隐藏测试，既有运行任务仍加载旧版本原三组测试、源码、摘要和资源限制，递归逐字段/字节比较完全一致。Learning 原有测试验证旧 AC 不计入新版本进度、旧 AC 留存，新版本 AC 才恢复进度。fixture 指针切换不代表已实现管理员发布入口。 | PASS |
| 普通用户无法读取隐藏测试和私有参考程序 | `MySqlMigrationIntegrationTests.migratesCurrentTablesAndEnforcesDatabaseReadBoundaries` 实际受限 SQL；公共题目/列表、提交结果及学习历史字段白名单；`SubmissionCreationIntegrationTests.readsResourceVerdictsWithoutLeakingDiagnosticsOrHiddenFields`。本人作者草稿可以读取自己提供的测试/参考程序；公共题和他人草稿不可借此读取。最新回放保存 29 项真实 SQL 权限拒绝、API 无 Docker、Worker 无账号与作者可变表权限，见 [自测验收](M2-SELF-TEST-VALIDATION.md)。 | PASS |
| 个人题单只对本人可见 | `LearningIntegrationTests.privateOwnershipEveryRouteAndIdenticalMissingResponses` 验证详情、修改、删除、加/删题、排序六类他人/不存在/非法 ID 均 404；伪造 userId 不泄露题单，伪造 ownerId 拒绝，响应 no-store。[真实学习验收](evidence/m2-learning/learning.json) 与最新回放的 ownerDenied 交叉核对。 | PASS |
| 账号停用会使全部旧会话失效 | `AccountHttpIntegrationTests.disablingAccountRejectsEveryOldJwtAndRefreshCookie` 创建两个独立 sid，内部停用后两个未过期 JWT、refresh 和重新登录全部拒绝；Learning/Content/Solution/SelfTest 的真实 HTTP 过滤链及事务内复核保持。停用入口为已实现的内部服务；普通用户没有停用他人的接口，管理员操作界面仍属 M4。 | PASS |

## 3. 构建、输入关联与复现

本轮后端运行目录：`target/forgeoj-linux-20261005-192744-ecabee05`。实际 BUILD SUCCESS，API182（36 suites）/Worker133（23 suites），零失败/错误/跳过；[核对结果](evidence/m2-gate/verification.json) 保存真实 XML 汇总、必需用例名、新旧 JAR SHA-256、300 个本轮冻结执行输入、165 个既有运行时生产输入与 56 个前端输入，均匹配当前字节。新后端 API/Worker SHA-256 为 `a3a26a537b81c0b8fcb55314db720f2cb2517515df961b06e7f3ccdc8d1217e6` / `5fdb2bb558b310d047e8672ad31056afbd9a2be16367fa302012b3bac67d86e2`。

前端最后一次全门禁为 `target/forgeoj-linux-20261005-182241-40614bbd`：69 项、类型/lint/格式/生产构建通过。本轮未修改前端；审计工具重新核对该构建中的全部 frontend 输入。原运行时后端为 `target/forgeoj-linux-20261005-174012-b069321c`，真实回放为 `target/forgeoj-e2e-20261005-182028-1c3fcd89`。审计工具核对已接受运行时的全部生产源码、模块/父 POM、合约与当前文件一致。本轮两项测试不影响生产实现，不能将新的 JAR 称作已进行新一次浏览器/SIGKILL 回放。

原自测阶段的 300 输入清单中，Replay 空 SQL 导出器曾在构建后修正且已在最终 Audit 实际执行；本轮又修改两个测试文件。原“299/300”只保留为当时事实，不覆盖本轮变化。本轮后端的新清单与当前后端/合约/已有验证工具逐一核对；新增证据汇总工具自身不在早于它生成的构建清单中，它不参与产品运行。

在仓库根目录运行（Docker Desktop 已启动；全部业务测试使用一次性数据）：

```powershell
.\tools\validation\Verify-FixedLinux.ps1 -Scope Backend -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
# 使用上一步实际输出的目录，不照抄历史目录。
node tools/validation/Verify-M2GateEvidence.mjs target/forgeoj-linux-YYYYMMDD-HHMMSS-ID
```

审计汇总工具读取真实 XML、冻结文件清单、原浏览器/SMTP/队列审计；检查失败则不输出通过。产物和日志保存在忽略的 target；可提交证据仅保存计数、哈希和既有脱敏证据路径，不保存密码、私人邮箱、令牌或原始业务日志。缺少历史 target 产物时先重新运行相应固定 Linux/Replay 流程；已清理的临时数据库不应重建成所谓原运行事实。

本轮仍观察到既有 Surefire fork 30 秒退出警告；BUILD SUCCESS、全部测试零失败/错误/跳过，不宣称所有日志无警告。[实时清理核查](evidence/m2-gate/cleanup.json) 确认 Docker 可用、managed sandbox/Testcontainers/本次 builder 均零；没有全局 prune，也未操作真实业务库。

## 4. 保留的限制与下一步

真实 QQ 验收仅覆盖授权收件箱的激活邮件接收/点击和账号已激活；没有新增真实找回/补邮箱邮件、生产域名、HTTPS 或可靠投递 SLA 的结论。默认邮件 disabled、无持久邮件 Outbox、进程内限流与 MySQL 会话等边界继续参见 L-033～L-035；独立自测保留永久最小生命周期/attempt 证据，私有源码/输入/输出到期清理见 L-038。

普通 Docker、单机、日志尽力交付、通知采样、资源分类和调度的既有 L-006/L-027/L-029～L-032 限制继续保留。测试零失败不表示日志绝对无警告，也不代表生产主机、容量、高可用、M4 治理或 M5 上线验收。

五门禁现已全部通过，完整 M2 提升为 VERIFIED；随后进入 M3 班级/成员/作业的设计和实施。未创建 PR、合并 main、打 tag、Release 或新增 RESUME_READY。
