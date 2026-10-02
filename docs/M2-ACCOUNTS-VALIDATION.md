# M2 普通账号单元验收记录

> 记录日期：2026-10-02。普通账号单元为 `VERIFIED`；完整 M2 里程碑仍为 `IN_PROGRESS`。
> 用户已批准 D-041 / `M2-ACCOUNTS-DESIGN.md` 的全部推荐方案。验收基于 M1 `d38e2c94704a28e0521a4bb0d19c2131ecd17a06` HEAD 的工作树快照执行；交付身份以 `feat/m2-accounts` 的最新提交为准。没有 PR、main merge、tag 或 Release。
> 修复后的 Windows 33 项、第四轮固定 Linux API 100 + Worker 111 / 前端 13 项与全部检查通过；相同哈希产物的新 Replay 在真实浏览器、权限、完整运行时审计及精确清理上全部通过，188 个后端/前端/验证工具输入仍与构建清单一致。脱敏可提交事实见 [证据目录](evidence/m2-accounts/README.md)。历史失败与此前局部结果保留，不与末轮重复累计，也不等同全部 M2 或生产邮件验收。

## 1. 本次交付范围

本单元覆盖普通用户注册、邮箱激活、用户名/邮箱登录、短 JWT 与独立 MySQL session、refresh 轮换和重用撤销、当前/全部退出、改密、邮箱找回，以及旧 ACTIVE 账号补邮箱。保留 M1 的配额/取消、三字段 owner-only 同源通知、GET 与轮询兜底、四字段 MQ、独立受限 Worker 和 API 无 Docker 权限。

生产版本化迁移只追加 V6：保留原 ID、用户名、密码摘要、状态、quota 与提交/任务/attempt/Outbox 历史，新增邮箱字段及三张认证表。新注册的用户、quota lock、激活摘要同事务建立；Worker 不获得用户或认证表权限。旧账号没有伪造邮箱和验证时间，旧进程会话升级后需重新登录。

本单元不包含其余 M2 题库/标签筛选、题目草稿和参考程序、审核版本、官方题解/题单、个人私有题单、服务端代码草稿、自测和完整提交历史，也不包含 M4 独立管理员、Redis/ES、分布式限流或 M5 生产主机/SMTP/上线验收。账号单元验收通过也不等于整个 M2 五门禁或 V1 Release 完成。

## 2. 验证环境与数据边界

- 开发机：Windows / PowerShell，JDK 21；Maven Wrapper 3.3.4 固定 Maven 3.9.14，Spring Boot 4.1.1 / Security 7.1.1，原生 MyBatis Starter 4.1.0。
- 前端：Node.js 24.14.1 / npm 11.11.0，使用锁文件中的 Vite/Vitest/TypeScript 工具；浏览器验收与模拟 API 单元测试分别记录。
- 集成数据：Testcontainers 固定 MySQL 8.4.12、RabbitMQ 4.3.6 和已登记 digest；只使用一次性数据库、公开测试密码/密钥和原创开发题。不连接真实 SMTP、不改变真实开发/生产数据。
- 固定 Linux：`tools/validation/Verify-FixedLinux.ps1` 以 linux/amd64 镜像、只读源码挂载、新构建副本和全新 Maven/npm 缓存执行，排除宿主 `.git`、构建产物、缓存和本地秘密配置。固定镜像与构建方式沿用 `M1-FIXED-LINUX-VALIDATION.md`，不能把 M1 160/8 的结果作为新账号代码证据。
- 本地邮件：`LocalAccountMail` 仅 dev/test、loopback、独立端口、最多 100 条内存消息；测试日志只允许固定投递失败码，不输出邮件、token 或异常对象。投递扩展点为 `AccountMailDelivery`，没有实际 SMTP adapter。
- 沙箱测试不能与另一轮 managed sandbox 测试并发；按本轮完整资源身份清理，不全局 prune Docker 或删除其他会话的容器。

## 3. 已执行检查

| 检查 | 实际结果 | 证据与边界 |
|---|---|---|
| Windows 账号/权限/提交/通知聚焦回归 | 48 项，0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`；14:51:24 +08:00 完成 | `target/m2-account-verified.log`。当时 AccountFlow 为 10 项；此后新增的外层事务、HTTP/JWT 等检查须另外记录，不能声称这一轮包含最终全部源码。 |
| Windows 新增 HTTP / V5→V6 升级 / JWT 初轮 | HTTP 11 项、升级 1 项通过；JWT 15 项中 2 个 fixture 构造/读取错误；整体 27 项、2 errors，`BUILD FAILURE` | `target/m2-new-regressions.log`，15:15:17 +08:00 完成。不是 27 项全通过；具体 fixture 修正见第 5 节。 |
| Windows JWT 独立修正复验 | 15 项，0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`；15:18:55 +08:00 完成 | `target/m2-jwt-final.log`。验证签名/algorithm/issuer/audience、强制字段、时间/身份格式及生产密钥要求。 |
| Windows 最终前端检查 | 4 个测试文件、13 项通过；类型、OxcLint、ESLint、Prettier 和 Vite 构建全部通过 | `target/m2-frontend-final.log`，Vitest 15:16:41 开始。API/socket/跨标签行为使用模拟，不是浏览器或独立 Worker 的注册到 AC 证据。 |
| 首轮固定 Linux `clean verify` | API 95 项：93 通过、0 failures、2 errors、0 skipped；整体 `BUILD FAILURE`，Worker 未执行，后续 Linux 前端未执行 | `target/forgeoj-linux-20261002-151958-4841ef71/backend.log` 与其 API Surefire XML；07:25:50Z 完成。两项错误来自旧 Outbox direct-service fixture 没有登录 sid，见第 5 节。 |
| Windows HTTP / Outbox fixture 修正复验 | MockMvc 真实过滤链 14 项、真实 MySQL/RabbitMQ Outbox 2 项，共 16 项；0 failures / 0 errors / 0 skipped，`BUILD SUCCESS` | `target/m2-http-outbox-final.log`，15:35:19 +08:00 完成。补齐 generic/白名单、数据库不可用及真实 sid fixture；随后发现的 Servlet ErrorDispatch 差异不能由本轮 MockMvc 通过关闭。 |
| 真实 Tomcat 匿名错误响应负向检查 | 新增单个用例执行，1 failure / 0 errors / 0 skipped，`BUILD FAILURE`：无效激活令牌应为 400，网络响应实际为 401 | `target/m2-real-http-errors.log`，15:38:15 +08:00 完成；该次只执行新用例，不能写成通知 7 项全跑。随后 scoped handler 修正后的真实 HTTP/WS 结果见下一行。 |
| Windows MockMvc / 真实 Tomcat HTTP/WebSocket 修正复验 | MockMvc 14 项、真实 Tomcat HTTP/WebSocket 7 项，共 21 项；0 failures / 0 errors / 0 skipped，`BUILD SUCCESS` | `target/m2-http-final.log`，15:41:15 +08:00 完成；证明 scoped handler 保留预期匿名错误状态及既有通知边界。不是独立 Worker/浏览器注册到 AC。 |
| 第二轮固定 Linux `clean verify` | API 99 项：98 通过、1 failure、0 errors、0 skipped；整体 `BUILD FAILURE`，Worker 未执行，后续 Linux 前端未执行 | `target/forgeoj-linux-20261002-154326-14fb482b/backend.log`；07:49:45Z 完成。旧 AuthAndProblem 的 reason matcher 与直接返回状态的修正冲突，401 本身正确；见第 5.6 节。 |
| 第三轮固定 Linux 全量回归（后续 API 错误处理修复前的快照） | **API 99 + Worker 111 = 210 项，0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`；Linux 前端 4 文件/13 项及全部检查通过** | 报告 `target/forgeoj-linux-20261002-155428-415d3812`，汇总输出 `target/m2-linux-verified.log`。后端 08:05:28Z 完成，前端 Vitest 08:05:50Z 开始；JAR/源码清单哈希见下表。其 Replay 随后揭示第 5.7 节问题，不能作为修复后代码的最终验收。 |
| 第三轮产物的真实浏览器与判题 Replay | 新账号注册→本地邮件→激活→清 fragment→邮箱登录→AC、旧 learner 轮询兜底→AC 和八种 verdict 通过；**Permissions 失败，完整运行时审计未通过** | 旧报告 `target/forgeoj-e2e-20261002-160719-7500526b`；`target/m2-replay-permissions.log` 记录 GET 他人提交预期 404、实际 401。正向事实与后续失败分开保留，不推算整个账号单元通过。 |
| 旧 Replay 精确清理 | 该一次性栈的容器、专属网络/数据库及 MQ volume、唯一 Worker image 已移除，报告保留 | `target/m2-replay-incomplete-cleanup.log`。该记录仅关闭旧轮资源；最终构建/新 Replay 的清理使用其独立证据。 |
| Windows 业务错误状态修正复验 | AuthAndProblem 3 + SubmissionCreation 22 + 真实 Tomcat HTTP/WebSocket 8 = **33 项，0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`** | `target/m2-business-http-final.log`，16:23:19 +08:00 完成。包含真实 Tomcat 的成功 200、他人提交 404、非法绑定/JSON 400；新增 `ApiErrorHandler` 后的当前源码冻结，见第 5.7 节。 |
| 第四轮固定 Linux 全量回归 | **API 100 + Worker 111 = 211 项，0 failures / 0 errors / 0 skipped，`BUILD SUCCESS`；Linux 前端 4 文件/13 项及全部检查通过** | 报告 `target/forgeoj-linux-20261002-162353-b85f8da4`，汇总输出 `target/m2-linux-closed.log`；后端 08:33:55Z 完成，前端 Vitest 08:34:13Z 开始。本轮 XML 已重新合计，源码/API/Worker 哈希按实际文件核实，见下表。 |
| 修复后新产物的浏览器、权限与运行时审计 | **全部通过**：11 提交 / 10 FINISHED / 1 CANCELLED / 11 已发布 Outbox / 4 空队列；08:46:16.682Z 完成审计 | 新报告 `target/forgeoj-e2e-20261002-163550-2eb0aae2`，`target/m2-replay-verified-{start,permissions,matrix,audit}.log`。显式使用第四轮目录和 JAR 哈希，真实注册/邮箱/AC、旧账号轮询兜底、八 verdict 与权限重新独立执行；脱敏事实见下节。 |
| 新 Replay、测试及构建资源关闭 | **精确清理完成**，本轮容器/卷/网络/Worker image 为 0；managed sandbox / Testcontainers / 当前构建 container / 唯一构建 image 均为 0 | `target/m2-replay-verified-cleanup.log` 和最终资源核查；没有全局 prune，没有删除其他会话资源。完整日志保留在忽略的 `target/`。 |
| 验收源码输入一致性 | **188 文件，`changedInputs: []`** | 第四轮报告的 `input-check.json`；[可提交输入核查](evidence/m2-accounts/input-check.json)。只核对后端、前端和验证工具输入，不把随后文档收尾误算成运行时代码变化。 |

首轮 Linux 源码清单文件 `target/forgeoj-linux-20261002-151958-4841ef71/source-files.sha256` 的 SHA-256 为 `F55020D91E1398DFAD95E7374247DC64F4A5B6631198C9C94E2D4EC0311D3561`。该清单只标识失败轮的工作树快照，不能作为最终通过或可发布产物身份。

第三轮通过的实际 API/Worker XML 独立合计：API 17 suites / 99 tests，Worker 21 suites / 111 tests，全部零失败/错误/跳过；`backend.log` 的 `BUILD SUCCESS` 与计数一致。Linux 前端 `frontend.log` 记录类型、OxcLint、ESLint、Prettier、13 项 Vitest 和 Vite 构建全过。这一轮使用新缓存和源码副本，不复用前两轮失败报告或 M1 旧 JAR。

第三轮通过的修复前快照产物位于 `target/forgeoj-linux-20261002-155428-415d3812`，已按实际文件核实 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `source-files.sha256`（清单文件自身） | `97a5d8489d6ff0ea1b6b078ade5a7d30c228d5d9140c673dfb55258f7bb89543` |
| `forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar` | `19a64782471ba82d345ab1c1e35ad917c40fcf50c6951bae0dd3da434ec22aa9` |
| `forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar` | `f966c483261c191eb1f5ee5423e227a5e96dc691a4fe5bf0da484a3f86ae0b33` |

这些哈希仅标识第三轮快照，不能标识随后新增 `ApiErrorHandler` 的当前源码，也不能替代权限/日志/队列审计。该轮真实浏览器中，新邮箱账号 AC submission 为 `a3c9ceae-4ba5-4277-8c08-97c468b87c7a`，旧 learner 轮询兜底 AC 为 `d5f5f1f2-b0db-4d69-bd88-07e11f777351`；`browser.json` 和相应截图保留 QUEUED→FINISHED/AC 事实。八种 verdict 为 AC、WA、CE、RE、TLE、OLE、MLE、SECURITY_VIOLATION，`matrix.json` 各项与预期一致，并保存三字段通知 QUEUED/0、RUNNING/1、FINISHED/2。GET 他人提交权限检查实际失败，WS 他人订阅 404 通过；完整审计仍未通过，旧栈精确清理后不能继续充当修复后验收栈。

第四轮当前源码的实际 API/Worker XML 独立合计为 API 17 suites / 100 tests、Worker 21 suites / 111 tests，共 211 项，全部零失败/错误/跳过；后端 `BUILD SUCCESS`，2026-10-02T08:33:55Z 完成。Linux 前端类型、OxcLint、ESLint、Prettier、4 个文件/13 项 Vitest 与 Vite 构建全部通过，Vitest 08:34:13Z 开始。当前产物位于 `target/forgeoj-linux-20261002-162353-b85f8da4`，独立核实 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `source-files.sha256`（清单文件自身） | `f4df51fb84b5109bde5073d8b8eedcc93b6e15988a2d6cc45c2ba4a46632c6c4` |
| `forgeoj-api/forgeoj-api-0.0.1-SNAPSHOT.jar` | `98ea9b25d8c252217282788e2f6aab0a9465c04caffb5903a11ea847071414d9` |
| `forgeoj-judge-worker/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar` | `a93c33b6d03a24c9dc8c7757abfcb4451e1dfc18b192294ef77af06cb76e1a46` |

新 Replay 显式使用本轮目录和上述 JAR 哈希，后续完整独立验收已通过。可提交事实优先保存在 `docs/evidence/m2-accounts/`；原始完整日志和运行栈身份保留在忽略的 `target/`，不包含在版本控制中。

### 3.1 最终独立 Replay 与真实浏览器事实

最终运行目录为 `target/forgeoj-e2e-20261002-163550-2eb0aae2`，与第四轮源码清单和 API/Worker JAR 哈希关联。新一次性账号 `m2_browser_final` 实际完成注册→本地邮件→fragment 清地址→激活→邮箱登录→内置两数之和→QUEUED/version 0→FINISHED/version 2/AC。普通端口 5173 的 Vite WebSocket 代理连接计数为 1；旧 ACTIVE `learner` 使用端口 5174 的阻断 WebSocket 模式，GET 轮询至少 2 次，仍显示同样的 QUEUED→FINISHED/AC；完整审计核对代理和提交事实。两条浏览器记录不是自动 UI 测试，也不是 SMTP 验证。

| 浏览器模式 | Submission ID | JudgeTask ID | 可提交证据 |
|---|---|---|---|
| 新注册且邮箱已验证，正常通知 | `d0b0870a-6b78-4e1d-b814-86a029b0c76b` | `8c497c8f-f1e9-435e-9197-0d49fee9eada` | [browser.json](evidence/m2-accounts/browser.json)、[激活截图](evidence/m2-accounts/account-activated.jpg)、[实际 AC](evidence/m2-accounts/account-ac.jpg) |
| 旧 learner，WebSocket 被阻断后的 GET 兜底 | `e0357bb4-c007-48c8-9656-1bb73d85f636` | `5b933264-e6a8-47fb-acf9-446c693e5260` | [browser.json](evidence/m2-accounts/browser.json)、[轮询实际 AC](evidence/m2-accounts/legacy-fallback-ac.jpg) |

[最终审计](evidence/m2-accounts/audit.json) 在 `2026-10-02T08:46:16.682Z` 记录 11 个提交、10 个完成、1 个取消、11 个已发布 Outbox、4 个空队列，并关联 request/submission/task/Outbox/attempt。八种 AC、WA、CE、RE、TLE、OLE、MLE、SECURITY_VIOLATION 都由该运行栈实际执行，三字段通知保持单调版本，终态后关闭，见 [结果矩阵](evidence/m2-accounts/matrix.json)。

[权限记录](evidence/m2-accounts/permissions.json) 核实匿名 401、他人 404、不存在 404、跨 Origin 403、退出订阅关闭 1008；QUEUED 取消为 CANCELLED/version 1。此前他人 GET 被改成 401 的问题已在新产物上实际关闭，没有开放 `/error`。本轮测试激活 token、密码、邮箱和访问 JWT 均未出现在 `api.log`、`worker.log`、`frontend.log` 中，见 [凭据日志核查](evidence/m2-accounts/account-log-check.json)；这只是已执行测试值和三份受控日志的检查，不等同完整第三方日志审计。

精确 Stop 已删除该栈容器、专属 volume/network 和本轮唯一 Worker image，报告保留；随后核对 managed sandbox、Testcontainers、当前构建 container 与唯一构建 image 均为 0，见 [清理核查](evidence/m2-accounts/cleanup.json)，没有全局 prune。最终 [input-check.json](evidence/m2-accounts/input-check.json) 对 188 个当前后端/前端/验证工具文件检查 `changedInputs: []`，说明本轮浏览器和审计使用的最终实现输入没有漂移。

## 4. 设计覆盖矩阵

下列 Linux 自动化结果由第四轮当前源码全量回归覆盖，真实浏览器、独立运行时审计和清理已由相同产物的新 Replay 闭环。第 3 节保留此前红绿记录，不重复计数；账号单元为 `VERIFIED`，范围限于第 1 节，不提升完整 M2 或生产部署状态。

| 设计与风险 | 实现/自动化入口 | 实际验证与保留边界 |
|---|---|---|
| 注册原子建立账号、quota、激活摘要；唯一用户名/邮箱竞争 | `AccountRegistrationIntegrationTests`；V6；`AccountService.register` | 第四轮 Linux 3 项通过，包括撤销 quota INSERT 后完整回滚与并发唯一胜者。 |
| 邮件必须在最终事务提交后；投递失败不撤销已提交账号 | `AccountFlowIntegrationTests` 的外层 rollback、外层 commit、独立失败 adapter 测试 | 第四轮 Linux AccountFlow 13 项通过：回滚无邮件、三表零记录；提交后一次邮件；失败仍为 PENDING 且不能登录。 |
| PENDING 不可登录；激活用途/期限/单次消费；密码变化使旧 reset 失效 | `AccountFlowIntegrationTests` | 第四轮 Linux 13 项通过。生产邮箱到达不在此证据内。 |
| refresh 单次轮换、并发竞争、重用只撤销当前 sid | `AccountFlowIntegrationTests`、`AccountHttpIntegrationTests` | 第四轮 Linux 服务层 13 项与 MockMvc 真实过滤链 14 项通过；保留消费前后 access JWT，验证其仍未到期但撤销后不再授权；第二独立会话仍有效。 |
| 当前退出、全部退出、改密、重置、停用使规定会话失效 | `AccountHttpIntegrationTests`；JWT + DB 过滤链；事务内敏感写复核 | 第四轮 Linux MockMvc 14 项通过，包含无 refresh Cookie 的当前退出、仅 refresh 的退出、全部旧 JWT 拒绝及 DB 不可用时 fail closed；真实 Tomcat HTTP/WS 8 项也通过。 |
| 签名/HS256、issuer、精确 audience、强制字段、身份和时间合法性 | `AccountJwtTests`、`AccountJwt` | 第四轮 Linux 15 项通过；未来 iat、缺 audience 等验证器修正见第 5 节。未声称真实签名密钥轮换/生产 HTTPS 已验收。 |
| 补邮箱需密码及 owner 当前会话，未验证前不替换旧邮箱 | `AccountFlowIntegrationTests`、`AccountHttpIntegrationTests` | 第四轮 Linux 覆盖邮箱更新/旧 reset 失效、他人不能消费且失败不消耗、撤销 sid 不能消费而本人另一会话可消费。 |
| Cookie/CSRF/精确 Origin、HttpSession 升级兼容 | `AuthAndProblemIntegrationTests`、`AccountHttpIntegrationTests`、`SubmissionNotificationIntegrationTests` | 第四轮 Linux AuthAndProblem 3/MockMvc 14/真实 Tomcat HTTP/WS 8 项通过；首次写之后 CSRF 仍保持、跨源与缺 CSRF 拒绝。新 Replay 独立复核 owner/匿名/不存在/Origin 状态与退出关闭。 |
| 限流、输入长度/密码 UTF-8 字节边界、模糊提示 | `AccountPolicyTests`、`AccountFlowIntegrationTests`、HTTP 响应检查 | 第四轮 Linux policy 3 项与数据库邮件冷却、generic/白名单通过；匿名及业务错误的真实网络状态经当前 Tomcat 8 项验证。进程计数重启和多实例不共享是 L-034。 |
| V5→V6 保留旧账号和判断事实，既有用户名兼容 | `AccountMigrationIntegrationTests` | 第四轮 Linux 1 项通过：原 ID/密码/状态/quota、FINISHED 和 WAITING_RETRY 事实完整保留；无伪造邮箱/会话；重复 migrate/validate 成功。 |
| API/Worker 最小数据库权限 | `MySqlMigrationIntegrationTests`；V6 grants | 第四轮 Linux 对应测试通过；API 账号 DELETE/username UPDATE、隐藏数据等保持拒绝，Worker 对三张认证表无访问权。 |
| M1 配额/取消/事务回滚/owner 通知与 Worker 不退化 | `SubmissionCreationIntegrationTests`、`SubmissionNotificationIntegrationTests`、Worker 原回归、Outbox 原回归 | 第四轮 Linux 提交 22/通知 8/handler 6/Outbox 2 与 Worker 111 项通过，包含 GET 他人提交 404 与坏绑定/JSON 400 的真实 Tomcat 回归；新 Replay 权限/取消、八 verdict、队列/Outbox 与精确清理全部通过。 |
| 账号 UI、fragment 清地址、受保护账号操作过期刷新 | `AccountView.spec.ts`、`AccountRefresh.spec.ts`、`App.spec.ts`、`submissionMonitor.spec.ts` | Windows及第四轮 Linux 前端 13 项/all checks通过；Web Locks 协调及缺失时要求重新登录有模拟测试。新 Replay 真实注册/本地邮件/fragment/激活/邮箱登录/AC 与旧 learner 轮询兜底均通过，见第 3.1 节。 |

## 5. 发现的问题与修复边界

### 5.1 JWT 请求误触发自动会话认证，清除了 CSRF Cookie

实际 HTTP/通知回归中，登录后首次提交虽返回 202，响应却给 CSRF Cookie `Max-Age=0`，后续写请求失去配套 CSRF。当前 Spring Security 7.1.1 的原会话管理配置加入了隐式 `SessionManagementFilter`；JWT 每请求重建身份且使用 NullSecurityContextRepository，导致它把每次请求当作新登录，执行 CSRF 认证策略清 Cookie。

修复明确禁用隐式 sessionManagement，保留 NullSecurityContextRepository、JWT 过滤链、CSRF 和严格 Origin；没有关闭 CSRF 或放开写接口。新增“首次提交后 CSRF 仍可用于下一次操作”断言，实际 HTTP 验证也检查认证 GET 不清 CSRF。身份变化仍显式轮换，refresh 仍保留 CSRF。局部通过范围见第 3 节，最终 Linux/真实浏览器仍单独记录。

### 5.2 JWT 对抗检查及两项测试 fixture 修正

专项构造检查补齐了未来 `iat`、空/missing audience、缺强制字段、非法 sub/sid、过长 lifetime、签名篡改和非 HS256。验证器增加 `iat <= now`、`exp > iat`、最长 300 秒、规范 subject/sid，audience 用常量 List 比较以安全处理空值；异常身份不进入数据库授权。

初轮 JWT 的两项 errors 发生在测试构造/读取阶段：Spring encoder 本身禁止生成 `exp <= iat` 的 Jwt；有效 `iss="forgeoj"` 通过 `getIssuer()` 被错误当 URL 读取而失败。这不是两个生产验签失败。对应测试保留，前者改用既有 Nimbus SignedJWT 签出非法时间 fixture，后者使用字符串 claim；15 项独立复验全过。未来 iat/missing aud 的生产验证器修复与这些 fixture 修正分别说明，不据此声称增加了新的密码学实现。

### 5.3 邮件副作用必须等待调用者外层事务提交

TransactionTemplate 可以加入已有事务；内层 `execute` 返回不表示外层事务最终提交。原先直接发送会留下外层回滚后仍有邮件、但数据库摘要不存在的窗口。`deliver` 现在在存在事务同步时登记 `afterCommit`，无活动外层事务时才直接发送；adapter 失败仅记录固定 `account.mail_delivery_failed`，不回滚已提交账号，也不输出邮箱/token/异常对象。

新增真实 MySQL 外层回滚/提交及独立失败 adapter 回归，全部使用唯一 fixture 用户、不替换共享 bean；首轮 Linux AccountFlow 13 项通过。没有把本地发信可恢复等同持久邮件 Outbox、真实 SMTP 或可靠投递保证。

### 5.4 固定 Linux 首轮揭示旧 Outbox fixture 缺少 sid

两项旧 Outbox 集成测试直接调用 submission service，没有建立数据库登录会话或当前认证 sid。M2 的 `requireCurrentWrite` 按设计返回 401，因此首轮 API 95 项中 2 errors，Worker 未执行。修复方向是给测试调用者建立真实账号 session，并设置匹配 sid 的认证上下文、测试结束清理线程身份；不能删除用例、绕过生产会话检查或忽略错误。

fixture 已补真实 session/sid 和上下文清理，Windows 真 MySQL/RabbitMQ 两项 Outbox 与 MockMvc 14 项合计 16 项在 15:35:19 通过；第三轮固定 Linux 又实际通过 API 99/Worker 111/前端 13 全量检查，包括这两项 Outbox。新通过结果见第 3 节，保留首轮失败的源码清单和日志。

### 5.5 MockMvc 不覆盖真实 Servlet ErrorDispatch 的状态改写

新增 `SubmissionNotificationIntegrationTests.anonymousAccountErrorsKeepTheirStatusThroughTheRealServletContainer` 通过 JDK HTTP client 访问真实随机端口 Tomcat；合法 CSRF/Origin 下的匿名无效激活令牌本应返回 400，15:38:15 的单用例执行实际得到 401，断言失败。这个问题此前没有在 MockMvc 真实过滤链 14 项中表现，因为它没有重现容器 `sendError` 引发的 `/error` 分派和再次鉴权。

该轮修复当时只作用于 `AuthController`：`ResponseStatusException` 转为直接的 `ResponseEntity` 状态，坏 JSON 直接返回 400，避免错误响应再次进入受保护 `/error`；不放宽 `/error` 路由、匿名业务接口或安全过滤链。Windows 15:41:15 完成 MockMvc 14 项与真实 HTTP/WebSocket 7 项复验，共 21 项零失败/错误/跳过；第二 Linux 对应 14/7 项也通过，但整体有第 5.6 节的旧 matcher 失败，仍不能写该轮最终通过。后续业务范围修复见第 5.7 节。不能用 MockMvc 通过替代真实网络错误状态检查，也不能把最初只跑一个负向用例写成七个通知用例全部失败。

### 5.6 旧登录测试的 reason matcher 与新 Servlet 错误契约冲突

第二轮固定 Linux 在 07:49:45Z 结束：API 99 项中唯一 failure 来自原 `AuthAndProblemIntegrationTests` 的 `status().reason("Invalid credentials")`。修正后的账号控制器直接返回 401，空响应不再通过 `sendError` 设置错误 reason，因此旧 matcher 失败；HTTP 状态本身没有变成成功，也没有越过认证。

测试只移除这个不再属于新响应契约的 reason 断言，保留原 401 和 empty body 检查，不删除测试、不放宽生产认证或 `/error` 授权。第二轮 Worker 未执行，不能据 API 98 项通过推出后端整体通过。第三轮固定 Linux 在 08:05:28Z 实际完成 API 99 + Worker 111 = 210 项全过，零失败/错误/跳过；随后 Linux 前端 13 项及全部检查通过。原 AuthAndProblem 3 项在该轮通过，源码清单和 JAR 身份见第 3 节。随后独立 Replay 发现下一节的业务错误分派问题，因此该轮通过只描述当时快照，不关闭修复后的最终验收。

### 5.7 业务 API 的隐藏提交 404 仍被真实错误分派改写

使用第三轮 Linux 产物的独立 Replay 中，新账号邮箱闭环、浏览器 AC、旧 learner 轮询兜底 AC 与八种 verdict 均有实际通过记录；但 Permissions 对他人提交 GET 期望 404，真实 HTTP 得到 401，`target/m2-replay-permissions.log` 的 `401 !== 404` 断言失败。WS 他人订阅仍返回 404。只作用于 AuthController 的第 5.5 节修复没有覆盖 Submission 等其他 API，真实容器再次把业务 `ResponseStatusException` 的 `sendError` 转发到受保护 `/error`，随后认证改写状态；完整运行时审计没有通过。

新增包范围为 `com.forgeoj.api` 的 `ApiErrorHandler`：业务 `ResponseStatusException` 直接返回原 HTTP 状态与空体，坏 JSON、缺失请求绑定及参数类型错误直接返回 400，避免二次 ErrorDispatch，也不向客户端输出异常 reason/详情。AuthController 移除两个重叠 handler，保留数据库异常的受控 503；没有开放 `/error` 或放宽生产认证。真实 Tomcat 回归新增成功 GET 200、他人提交 GET 404，以及非法类型/坏 JSON 400 检查，通知/HTTP/WS suite 增至 8 项。

当前源码冻结后，Windows AuthAndProblem 3 + SubmissionCreation 22 + SubmissionNotification 8 共 33 项在 16:23:19 +08:00 全部通过，零失败/错误/跳过，见 `target/m2-business-http-final.log`。第四轮固定 Linux 随后在 2026-10-02T08:33:55Z 完成 API 100 + Worker 111 = 211 项全过，零失败/错误/跳过；Linux 前端 4 文件/13 项及全部检查也通过，汇总日志为 `target/m2-linux-closed.log`，当前源码/JAR 哈希见第 3 节。使用该目录和显式 JAR 哈希的新 Replay 在 08:46:16.682Z 完成全部审计，GET 他人提交实际 404，浏览器、八 verdict、权限及精确清理均通过，见第 3.1 节。旧 Replay 清理及失败事实仍保留，不把旧浏览器记录或第三轮 210 项计入本次修复后的终验。

## 6. 复验命令

从仓库根执行，Windows 后端使用 JDK 21；需要可用 Linux Docker Engine。以下是可复现入口，执行结果以第 3 节报告为准，不能只凭 shell 命令退出码或 Maven 日志尾部判断测试通过。

```powershell
$env:JAVA_HOME = 'D:\JDK21'
$env:PATH = 'D:\JDK21\bin;' + $env:PATH
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-api '-Dtest=AccountFlowIntegrationTests,AccountRegistrationIntegrationTests,AccountPolicyTests,AuthAndProblemIntegrationTests,SubmissionNotificationIntegrationTests,SubmissionCreationIntegrationTests,MySqlMigrationIntegrationTests' test
```

新增安全/升级检查及完整后端：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-api '-Dtest=AccountHttpIntegrationTests,AccountJwtTests,AccountMigrationIntegrationTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

前端独立执行：

```powershell
Set-Location .\frontend
npm ci
npm run verify
```

固定 Linux 全量与新报告目录：

```powershell
.\tools\validation\Verify-FixedLinux.ps1 -Scope All
```

独立纵向链路使用 `tools/validation/Replay-FixedLinux.ps1`，必须显式传入本次成功 Linux `BuildDirectory` 及已核实 API/Worker SHA-256；脚本原 M1 默认目录/哈希不能替代新账号产物。Start 建立独立 fixture，Status 读取实际分配端口；浏览器在该前端注册、通过开发邮件链接激活、登录并实际提交 AC，然后 Permissions/Audit 导出白名单事实，Stop 精确清理。验收栈的 mailbox bridge / fixture Vite `/dev-mail` 仅用于隔离测试，本地产品模拟器仍绑定 loopback，普通业务 API 不开放邮件内容。

## 7. 最终关闭结论与保留限制

普通账号单元的设计覆盖、迁移/权限、原子注册、邮件提交边界、JWT/refresh/撤销、找回/补邮箱、M1 回归、固定 Linux、相同产物的真实浏览器与完整独立审计、日志测试值核查和资源清理均已实际闭环，状态为 `VERIFIED`。验收执行时为基线 HEAD `d38e2c9` 上的工作树快照，188 个实现/验证输入与最终构建清单匹配；交付提交可在 `feat/m2-accounts` 最新提交中追溯，本文不自引用未知的新 commit 或声称已经 push。

L-028 的新账号/quota lock 原子建立问题已由 V6、真实 MySQL 回滚/唯一竞争、第四轮固定 Linux 和真实注册链路关闭，原记录保留。L-027、L-029～L-032 的既有调度、通知容量、尽力日志、保守沙箱清理和可信资源结果边界继续有效；L-029 只补齐 sid/MySQL 认证迁移证据，未关闭采样、分布式通知、历史回放或容量限制。L-033～L-035 继续约束：默认邮件 disabled、无真实 SMTP/持久投递，进程限流重启/多实例不共享，受保护请求 DB 查询与 fail closed，refresh 响应丢失/重用后可能重新登录，认证摘要尚无运维清理任务，Web Locks 缺失时短 JWT 到期需重新登录。生产 Secure/HTTPS、代理、签名密钥管理、Redis 和云主机验收仍是后续工作。

整个 M2 保持 `IN_PROGRESS`：题库列表/筛选、题目草稿/审核版本、官方题解/题单、个人私有题单、代码草稿、自测及完整提交历史尚未完成，不能把本单元的通过写成全部 M2 五门禁通过。M4 管理/Redis/分布式限流和 M5 生产主机/SMTP/上线验收也未关闭。

本记录没有性能数据，也没有发布或简历就绪结论。
