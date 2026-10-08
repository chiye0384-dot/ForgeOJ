# ForgeOJ 新对话启动说明：M4 第4步运维查询与人工重试

交接日期：2026-10-08，Asia/Shanghai。本文件是本次新对话的统一启动入口。

## 1. 给新对话的明确任务

你接手的是已有实现和证据的 ForgeOJ，不是从零开始。实际仓库是 `D:\Java项目\ForgeOJ`，保存的 Codex 项目根目录是父目录 `D:\Java项目`。先进入实际仓库再运行 Git、Maven、npm。

**首次接手只阅读、只读核对并报告准备情况；等用户在新对话说“继续”后，完成 M4 第4步一个单元。** 本次旧对话只做交接和文档同步，不实施第4步。用户继续后，先列出该单元的具体执行步骤，再开始第一步，持续推进到该单元实际验收、证据、提交和推送完成，然后停止。不得顺便启动第5～7步或 M5。

不要反复询问已经确认的选择，也不要只给计划而不落实。常规工程选择可自行处理；尚未决定且会改变产品行为、持久数据、安全边界、费用或外部状态的选择，读完现有文档后用易懂的例子问用户。对提交时间/判题时间等容易误解的概念，说明时间线和用户实际看到的结果。

## 2. 当前事实和交付边界

| 范围 | 当前状态 | 下一步关系 |
|---|---|---|
| M-1、M0、M1 | VERIFIED | 已有基础工程、正式判题及可靠执行链路，不重做 |
| M2 | VERIFIED，5/5 总门禁 | 普通账号、内容、学习与自测已完成 |
| M3 | VERIFIED，4/4 总门禁 | 班级、私有题、作业与教师查询已完成 |
| M4 第1步 | 设计已完成 | 管理身份和角色设计已确认 |
| M4 第2步 | VERIFIED | 独立管理员身份、账号维护、审计和 CLI 已完成 |
| M4 第3步 | VERIFIED | 公共题审核治理和原作者修订已完成 |
| M4 第4步 | 未开始 | 新对话在用户继续后仅实施这一单元 |
| M4 第5～7步 | 未开始 | Redis、ES、监控与完整 M4 总门禁分别后续开展 |
| 完整 M4 | IN_PROGRESS | 不能因第2/3步通过就宣称整个 M4 完成 |
| M5、V1.1、Release、RESUME_READY | 未完成 | 不提前实施、发布或写简历准入结论 |

当前分支 `feat/m2-accounts`，远端 `https://github.com/chiye0384-dot/ForgeOJ.git`。本次交接开始时已核对：本地与同名远端分支均到第3步交付提交 `c60b75d60bfe210944a9cefde17cade92bb8a71f`。本交接文档随后可另有纯文档提交，所以新对话的 `HEAD` 不必恰好等于该业务提交；应检查该提交仍是祖先，以及之后提交是否仅为交接文档。

| 交付 | 提交 |
|---|---|
| M4 第3步 | `c60b75d60bfe210944a9cefde17cade92bb8a71f` |
| M4 第2步 | `0f983988aa27841c8d110e9f821adbe6494c90f0` |
| M3 最终总门禁 | `0e213e1adc3ae04de7d9160622ea8442940dac28` |

用户已允许把完成且验收的单元提交、推送至上述账号的既有功能分支，不必重复请求推送许可。未授权 PR、合并 main、tag、Release、生产部署、真实管理员初始化或真实数据库/SMTP改动。Github 登录状态需要时再核对，不能把旧登录结论当永久事实；不得索取或输出凭据。

## 3. 必读顺序和文档权威

1. 仓库根 `AGENTS.md`：当前交接、桌面连续性、保留文件、工程和证据规则。其历史 checkpoint 只解释过往，不能当作当前待完成命令。
2. 本文件及 [M4 当前交接](M4-NEXT-CHAT-HANDOFF.md)：状态、范围和启动动作。
3. [需求](ForgeOJ-Requirements.md)，重点 4.2、12、13、14；[路线图](ForgeOJ-Roadmap.md) M4；[决策记录](ForgeOJ-Decision-Log.md)，重点 D-016、D-022、D-023、D-043、D-044及重试/不可变快照/班级作业相关决策。
4. [M4 七步实施计划](M4-IMPLEMENTATION-PLAN.md)：下一单元和停止边界。
5. [管理身份设计](M4-ADMIN-IDENTITY-DESIGN.md)、[管理身份验收](M4-ADMIN-IDENTITY-VALIDATION.md)、[本机维护 CLI](M4-ADMIN-CLI.md)：沿用角色、实时撤权、事务授权、审计和恢复规则。
6. [公共题审核设计](M4-PUBLIC-REVIEW-DESIGN.md)、[公共题审核验收](M4-PUBLIC-REVIEW-VALIDATION.md)、[最终机器证据](evidence/m4-public-review/verification.json)：理解不可变验证、作废/归档和后续重试影响。
7. 按第4步涉及的执行类型查阅 M1 可靠判题、M2 内容验证/输出预览/自测设计和验收，以及 [M2 总门禁](M2-GATE-AUDIT.md)、[M3 总门禁](M3-GATE-AUDIT.md)和作业/教师权限文档，不需要一开始通读所有历史日志。
8. [限制](KNOWN_LIMITATIONS.md)、[简历证据矩阵](Resume-Evidence-Matrix.md)、[来源与许可](UPSTREAM-AND-LICENSE.md)。应用 `forgeoj-development` skill，先读其 SKILL.md，再按引用读取 delivery-workflow。

状态冲突时先按用户最新明确指令和当前 Git/数据库/源码/最终证据核对。不能用旧内存的“M3 进行中”覆盖当前仓库的 M3 总门禁。旧 `M3-NEXT-CHAT-HANDOFF.md`、`NEXT-CHAT-HANDOFF.md` 和用户未跟踪的 `M2-NEXT-CHAT-HANDOFF.md` 都不是当前执行入口。旧日期段落中的“下一步”和 exec/session ID 已过时，不要恢复那些测试进程。

## 4. 已完成的业务能力

### 基础与可靠正式判题（M-1～M1）

- 分离可执行 API/Worker，JDK21、单 Main.java 标准输入输出；清晰的官方脚手架和 Apache-2.0 来源记录。
- Submission/JudgeTask/Outbox 原子创建，RabbitMQ 至少一次投递，严格小消息合约；Worker 在状态持久化后手动 ACK，重复/并发消息只允许一个合法 claim。
- 冻结源程序、题目版本、测试和资源策略；AC/WA/CE/RE/TLE/MLE/OLE/SECURITY_VIOLATION 与平台 SYSTEM_ERROR 分开。
- attempt、租约、fencing、心跳、自然过期恢复、有限平台重试/退避、死信、排队/运行配额及本人排队取消。
- 单调 WebSocket 通知及 GET 轮询兜底，owner/origin/撤销控制，隐藏数据隔离及关联日志；实际 Worker 故障和受限沙箱证据已有记录。

### 普通账号、学习和出题（M2）

- 注册、邮件激活、登录/找回/改密及会话撤销，公开题库与官方题单、个人草稿 CAS、进度和本人提交历史。
- 作者私有题面、测试、参考程序和独立题解；不可变快照的真实双程序验证；冻结送审、撤回、历史保留。
- OUTPUT_PREVIEW 只生成参考输出，作者明确确认后才写回；不自动覆盖、不伪造双 PASSED。
- 独立自测共享受控执行能力，但不产生正式 AC、学习完成或题解解锁；终态私有 payload 24 小时清理，最小生命周期/attempt 事实保留。
- 题解默认 AC 后开放；允许的自由练习上下文中，未 AC 也可经提醒和再次明确确认查看，保留 EARLY_VIEW 记录。作业单独策略不能被此规则绕过。
- QQ SMTP 真实收件/点击激活曾由用户验收，属于原日期历史证据；本次未重新发邮件，也不能声称生产邮件已部署。

### 班级与作业（M3）

- 当前 MySQL OWNER/ASSISTANT/MEMBER 权限、邀请、退出/移除/恢复、双方确认转让、归档和受控删除。
- 班级私有冻结题、共享正式判题与独立自测、题解策略，离班后禁止私人题面/题解及新执行。
- 作业冻结版本/参与者、发布和截止、真实 PRECOMPLETED、本人成绩/attempt；教师只查询当前有权班级和具体作业的成员成绩/正式提交/只读代码。
- “按时 AC”按服务器接受提交时间：截止前被服务器接受，之后排队/判题，最终真的 AC 仍算按时；后续等待中的提交不会抹掉此前有效完成。
- LEFT/REMOVED 保留本人脱敏历史、状态、次数和结果；不能再看班级私有内容或继续提交；恢复有效成员关系后恢复原作业关系。不是彻底隐藏本人历史。
- 私人练习、PRECOMPLETED 或自测本身不自动给教师源码访问权。M3 最终 4/4 门禁已经通过。

### 后台身份（M4 第1/2步）

- 每个管理员账号只有一种角色：CONTENT_REVIEWER、OPS_ADMIN 或 SUPER_ADMIN；兼任使用两个账号。SUPER 具备全后台角色权限，但仍受业务状态约束。
- 独立 JWT/Cookie/CSRF/principal/会话；普通账号与管理员即使数字 ID 相同也不是同一身份。逐请求查当前 MySQL，变更事务内再做授权 fence，撤权立即生效。
- SUPER 创建/禁用/恢复/改角色/重置其他管理员，CAS、审计和最后有效 SUPER 的并发保护。
- 新建、重置、bootstrap 首次登录必须改密。显式本机交互式初始化/恢复已有 SUPER 并留审计；没有默认密码、公开管理员注册或普通邮件找回入口。
- 成功处置和审计同事务，审计写失败则处置回滚；拒绝审计独立持久化。未在真实业务库初始化管理员。

### 公共题治理（M4 第3步）

- V19 迁移；案件列表和冻结上下文阅读，敏感参考程序需明确审计读取；OPS/普通账号不能冒用审核权限。
- 当前同快照的最新验证必须 FINISHED/PASSED，参考和独立题解均合格；案件重验通过真正 VALIDATE/Outbox 执行，新的等待/失败不能沿用旧 PASSED 批准。
- 带理由的批准/驳回、版本/状态 CAS、请求绑定和幂等，避免重复发布；驳回解除作者修改锁但保留旧事实。
- D-044：仅原作者可复制当前公开冻结内容到本人新私有草稿，再真实验证送审。TEXT 修订保持判题基础完全一致；CORRECTION 形成关联新题并归档旧题，旧 AC 不证明新题已完成。复制不继承 PASSED。
- 普通下架/恢复、不可逆严重作废；新正式/自测执行与状态变更有事务互斥，旧提交/attempt 不改写。
- 公开样例/作者/来源/许可和纠错关联，反馈 OPEN 案件合并、本人隐私/幂等/理由结案；不能按举报数自动下架。
- 本人旧结果警告，学生和教师作业 INVALID 保留 attempt、排除有效完成统计；后台和作者最小页面、真实撤权后清空敏感 UI。

## 5. 第3步验收依据：哪些是真实验证、哪些不是

最终固定 Linux All：`target/forgeoj-linux-20261008-201146-a1bd1a95`，API **263/47 suites**、Worker **133/23**，失败/错误/跳过均 0；前端 **118/23**，type-check/lint/format/build 全通过。后端耗时约 28 分 34 秒，不能因运行较久就放弃或改断言。

同一构件真实回放：`target/forgeoj-e2e-20261008-204157-6a542650`。包含 102 公共治理 HTTP 检查、6 真实双 PASSED、13 正式任务（12 FINISHED、1 CANCELLED）、1 独立自测、98 审计/request 关联、62 班级检查、14 新增及8既有 SQL 权限拒绝、真实多身份浏览器/原作者复制/撤权、七条队列 ready/unacked 全0。197 API、111 Worker/shared、79 frontend 输入及运行 JAR 完成关联，实际浏览器输入79一致。

- API JAR SHA256：`004391da1f75b8d0751738a3d1f4f87a784e6995cb735b8580fa5d5a5375b43f`
- Worker JAR SHA256：`8653b386c9fb3f010d7398d891ec87a3edfacd1e4c38416ba155407b7cc39182`
- 精确清理完成时间：2026-10-08 20:53:14 +08，任务拥有的容器/卷/网络/镜像/构建器/Testcontainers/沙箱均0。该结论是当时观察，新对话运行前仍应只读核对。
- 浏览器真实读取/复制/反馈/撤权已验收；浏览器没有输入新管理员密码或执行账号创建提交，凭据准备和创建/改密 HTTP 检查另有记录，不能混称全 UI 验证。
- 初次 All 曾有 Solution 数据库 grants 旧断言失败；修正为实际最小授权合约，保留隐藏/身份隔离/非法 FK/回滚等拒绝检查。失败日志保留，最终新 All 通过，不删除失败历史。
- 合约测试人为设置 PASSED 不等于真实 Worker 双通过。旧 focused 测试、历史 SMTP/故障结果与本次运行必须分日期描述。
- `implementation-checkpoint.json` 的 `committed:false/pushed:false` 是采集当时记录，deliveryNote 已解释；最终交付按 Git 实际事实核对，不能因此重做已交付单元。

## 6. 第4步要做什么，按这个顺序推进

1. **现场核对。** 检查 cwd、分支、远端、当前改动、业务提交祖先、保护文件摘要、工具版本和仅任务资源。不要重跑第3步全部验收来证明交接文档。
2. **梳理三类执行与用途。** 阅读正式判题、内容 VALIDATE/OUTPUT_PREVIEW、自测的状态、attempt、lease/fence、有限重试、Outbox、DLQ 和 grants；列出各类查询字段及可重试状态矩阵。不同执行类型不是同一个状态机。
3. **收敛设计。** 制作 OPS/SUPER 与 CONTENT_REVIEWER/普通用户/教师拒绝矩阵、请求/事务/审计/幂等/并发合约和最小页面。建议新增 `docs/M4-OPERATIONS-DESIGN.md`（目前未创建）。先核对既有决策，只有真正未定的重要行为才问用户。
4. **实现最小闭环。** 异常任务/attempt/死信元数据列表和详情、按用途限定的人工幂等重试、理由审计、最小运维页面。若需要新迁移，核对当前 V1～V19 后追加下一编号，不能重写旧迁移。
5. **直接验证关键边界。** 用一次性真实 MySQL 测试当前角色撤销、最小 grants、失败回滚、重复/并发/响应丢失、各类型资格和不变快照、共享配额。既有判题/班级/作业/审核能力做与变更直接相关的回归。
6. **固定 Linux + 相同构件实际运行。** 完成相应全量检查，实际 OPS/SUPER/审核员/普通账号浏览器，真正失败→合法人工重试→真实 Worker 执行；核对消息、提交/attempt、审计、日志、ACK、队列和故障恢复。测试计数以该单元实际结果为准，不能硬套263/133/118。
7. **收尾交付并停止。** 精确清理所拥有资源，保留浏览器标签；新增 `docs/M4-OPERATIONS-VALIDATION.md`、`docs/evidence/m4-operations/`（目前均为计划名称），同步交接/路线图/限制/证据矩阵/来源记录，核对 source/JAR/browser/evidence 同版，提交并推送既有分支。只提升第4步为 VERIFIED，完整 M4 保持 IN_PROGRESS；报告第5步是什么但不启动。

## 7. 第4步必须守住的边界与尚待核对的细节

- 仅 OPS_ADMIN/SUPER_ADMIN 有运维能力；后台登录成功不代表该路由权限正确，必须直接验证。CONTENT_REVIEWER 拒绝，普通 owner/teacher 身份不能越界。
- 只返回有界、白名单诊断元数据；不提供任意 SQL、普通用户/私有参考源码、隐藏测试、原始 MQ payload 随意回放或凭据。API 仍不能控制 Docker；Worker 不获得治理表权限。
- 人工重试必须是当前状态重新核对、带理由、请求绑定、CAS/幂等、持久审计、Outbox 的受限业务操作。不能直接改 verdict，不能把 WA 改 AC，不能编辑用户程序，不能给活动 QUEUED/RUNNING/WAITING_RETRY 任务再造并发执行。
- 仅受控的终态平台失败候选可进入重试设计；取消、用户代码失败、成功完成等不能误当平台死信。正式任务 DEAD_LETTER 与 Submission SYSTEM_ERROR/NULL verdict 是不同字段，必须读源码确认。
- 不得重置预算制造无限重试、绕过配额或抢占有效 lease；旧 attempt 和冻结快照必须保留，新增执行继续遵守 fencing 与沙箱 ownership。
- 内容验证与 OUTPUT_PREVIEW 的用途结果不能互相伪装；独立自测永不产生正式 AC/完成/解锁。24小时清理掉私有 payload 的自测能否执行必须核对，不能从不存在的数据伪造恢复。
- 已归档/作废公开题、离班/被移除/作业关闭与人工重试的交互必须明确，后台不能借此绕过现有私有内容边界。
- 同一 Submission 是否能被人工再执行、各用途有限预算怎样计数、恢复后成绩/旧平台失败如何呈现等精细产品合约尚未在本交接中新增确认；先查已批准文档，若仍未定且影响持久历史/成绩，用具体例子询问用户。不能把本文件中的风险核对点当作用户已选方案。
- 四字段消息仍保持相应 taskId/submissionId 或 snapshotId/taskType/contractVersion 合约，消息不放源码/隐藏测试。确认 broker 故障、发布失败、事务回滚、提交后ACK前崩溃不造成丢失/双执行/重复成功审计。

## 8. 定位代码和验证工具

API：`forgeoj-api/src/main/java/com/forgeoj/api/` 下 `admin/`（当前 AdminService/Mapper/Audit/AuthenticationFilter/SecurityConfig、PublicReview/Feedback）、`content/`（PublicRevision/ContentValidation）、`submission/`、`messaging/`、`selftest/`。尚不存在完整运维服务，先 `rg --files` 定位，不能猜成已实现。

Worker：`forgeoj-judge-worker/src/main/java/com/forgeoj/worker/` 下 task、messaging、content、selftest、sandbox；用类名查 Claim/Completion/Lease/Recovery/Heartbeat/Mapper/Runner/Listener/SnapshotLoader/SandboxRecoveryCleaner，以实际包路径为准。迁移和各权限脚本用 `rg --files` 定位。

前端：`frontend/src/views/AdminView.vue`、`AdminContentView.vue`、`services/adminApi.ts`、`router/index.ts`。沿用独立后台会话和角色导航，撤销/403后敏感状态清空。

现有工具在 `tools/validation/`：

- `Verify-FixedLinux.ps1`：Scope All/Api/Backend/Frontend，固定 Linux 检查。
- `Replay-FixedLinux.ps1`：Start 显式使用已接受 build directory/API与Worker SHA；Matrix/Permissions/Library/Learning/Classroom 等按真实需要执行。
- `Verify-M4AdminReplay.ps1`：Setup/ReadyBrowsers；只允许一次性测试栈上的显式交互 bootstrap。
- `Verify-M4PublicReviewReplay.ps1`：Http/RevokeReviewer/CaptureRuntime/Audit/CleanupSnapshot，第3步历史回放参考。
- `Verify-M4PublicReviewEvidence.mjs`：第3步专用最终审计，不直接冒充第4步审计器。

不要调用旧通用 Audit 然后伪造其缺失 browser fixture；它依赖别的历史单元页面与全局数据状态。为新范围编写真实审计，保留有效基础回归。完整 Worker 沙箱测试与独立回放不要同时运行，以免共享 Docker 故障/清理干扰。

## 9. Windows 执行注意事项

- 已使用工具路径：JDK `D:/JDK21`；Maven `D:/Maven/apache-maven-3.9.14/bin/mvn.cmd`；Node/npm `D:/Node.js/node.exe`、`D:/Node.js/npm.cmd`；Docker `C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe`。开始前查路径存在及版本。
- 使用 PowerShell，Maven `-D...` 参数加引号；前端 TMP/TEMP 可用仓库 `target/m4-node-tmp`。原生 sandbox 下 Vite rename 曾 EPERM，不能当业务失败，也不能绕过审批，使用正常授权提升或固定 Linux 检查。
- Docker 启动/回放通常需要正常 sandbox escalation；审批拒绝时说明动作和明确原因，先完成不受影响工作。
- 重定向日志后保存真实 `$LASTEXITCODE` 并正确返回，不得把读取日志成功当构建成功。固定 Linux 长跑通过进度/日志观察，不反复重启。
- 不设置 `core.autocrlf=false` 规避行尾检查；普通 LF/CRLF 提示不是业务失败。source-inputs 核对实际工作树字节与构件。
- 无须为纯交接文档重跑28分钟业务全量测试；文档检查路径、状态、一致性、diff和保护摘要即可。

## 10. 文件保护和桌面崩溃约束

两项内容不得读取/输出/复制/暂存/覆盖/删除。用户未跟踪的历史交接保持原文件；SMTP 配置严格保密，只核对文件摘要：

| 文件 | SHA256 |
|---|---|
| `docs/M2-NEXT-CHAT-HANDOFF.md` | `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054` |
| 忽略的 `.smtp.qq.local` | `6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907` |

不能 `git add .`、reset/clean 或覆盖用户改动。本次交接开始仅有上述未跟踪旧 M2 文档，新对话仍应核对是否出现其他改动。

**用户明确要求：不得关闭、退出、重启、终止或主动更新 Codex/ChatGPT。** 不可避免更新需要重启时先解释，等用户执行或明确授权。禁止 broad process-name kill、WSL shutdown 或影响客户端/runtime 的服务停止。只能核对 ownership 后停止确切任务PID或容器。

本地两次浏览器进程崩溃与关闭最后内置自动化标签强相关（2026-10-07 18:48、2026-10-08 08:02，Asia/Shanghai），尚未证明客户端缺陷修复。**不得 `tab.close()`、UI关标签或复现崩溃。** 复用现有标签，临时页清理到 `about:blank` 并 `markDeliverable()`；仅真实待接续工作可 `markHandoff()`。新对话取得标签后重新标记，旧标记不能当永久保障。第3步 tab5 已 about:blank 保留；旧端口63960的测试服务已清理，不能恢复旧 URL。不能承诺这些约束消除所有 OS/客户端崩溃。

## 11. 新对话首次回复和完成回复要求

首次回复应明确：已读取哪些核心文档、当前 Git 与保护文件核对结果、已有第3步和未开始第4步的边界、接下来第4步执行列表，以及是否发现真正缺失的决策。确认准备好后等待用户“继续”，不用重新问已决定的管理员/题解/时间/离班/修订规则。

第4步完成时说明新增行为、权限与用途边界、当前实际检查和故障验证、跳过/复用的历史证据及原因、限制、资源清理、源码/构件/证据关联、提交和远端一致性。附可定位文件和证据链接。所有重要门禁通过后才说该单元完成；不要把编译成功、启动成功或 mocked 页面当真实端到端验收，也不能为赶进度删断言/造报告。停止在第4步，下一步等用户再次授权。
