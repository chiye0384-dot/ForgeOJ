# ForgeOJ 版本路线图

2026-10-08 最终状态：完整M3 **VERIFIED，4/4总门禁通过**，见 [审计](M3-GATE-AUDIT.md)。本轮新增2项权限/历史直接回归，最新固定LinuxAPI223/41项M3均通过；未改Worker133/前端97按输入相等核对，运行构件和原真实判题/多账号证据关联通过。M-1至M3均VERIFIED；下一步M4最小管理员认证与角色设计，M4实施尚未开始。本轮到M3为止，不创建PR/main合并/Release/RESUME_READY；下方日期段落保留历史。

2026-10-08 最新：M3成员、私有题、作业和 [教学记录单元](M3-TEACHER-RECORDS-VALIDATION.md) 均VERIFIED，完整M3仍IN_PROGRESS。教学记录151HTTP、真实Worker及三账号/撤权/归档历史、Linux API221/Worker133/前端97和输入/构件关联通过。下一步完整M3四项总门禁汇总，本轮停止于教学记录；下方日期记录为历史。

2026-10-08 最新：M3成员、班级私有题及 [作业单元](M3-ASSIGNMENTS-VALIDATION.md) 均VERIFIED；完整M3 IN_PROGRESS，教师关联查询及四项总门禁留后续。本轮到作业验收为止，不启动下一单元。完整M2 VERIFIED保持，以下日期记录为历史。

2026-10-06 最新：班级成员首单元 [VERIFIED](M3-CLASSROOM-VALIDATION.md)，完整M3 IN_PROGRESS。下一单元班级私有题，其后作业和教师关联；完整四门禁尚未通过。完整M2 VERIFIED保持，以下日期记录为历史。

2026-10-05 最终门禁：完整 M2 已 VERIFIED，5/5 门禁通过，见 [最终审计](M2-GATE-AUDIT.md)。本轮补齐历史版本两项直接回归，固定 Linux API182+Worker133=315 零失败/错误/跳过；原前端69及其全部输入保持一致，已接受运行时的全部生产源码与 JAR 重新核对一致。真实浏览器/Worker故障/QQ邮件沿用各自原验收记录，本轮未重新回放或发送邮件。受控审核发布和 Redis/ES 仍属 M4；下一步 M3 班级与作业，尚未正式发布或新增 RESUME_READY。下方单元段落保留历史，当前完整状态以本段为准。

2026-10-05 最新：独立自测与 QQ SMTP 实际接收/激活单元已 VERIFIED。固定 Linux API181+Worker132=313、最终前端69及全部门禁、真实冻结编辑/取消/历史/Worker SIGKILL自然租约恢复、零AC副作用、七空队列/29实际权限拒绝与精确清理通过，见 [验收](M2-SELF-TEST-VALIDATION.md)。完整 M2 仍 IN_PROGRESS，下一步最终门禁汇总；受控审核发布仍 M4，不新增发布或 RESUME_READY 结论。以下日期段落保留各自历史事实。

2026-10-05 整理：参考输出预览/明确确认单元已 VERIFIED（实际验收2026-10-04）。V13 不可变用途隔离、仅运行参考、整组压缩输出、owner/CAS/幂等确认与版本递增完成；不会自动覆盖答案，不产生双通过或正式成绩。全新固定 Linux 后端 **298**（API174/Worker124）、前端 **61** 及全部检查通过，280 个冻结输入匹配；相同 JAR 真实页面失败→生成→显式确认/保留本地编辑→过期/待审/归档历史，以及新 Worker SIGKILL 自然租约恢复通过。HTTP/输出摘要/Outbox/提交后ACK审计、正常与回退AC、六空队列、45项实际权限拒绝和精确清理均通过；本轮有真实页面截图。见 [验收](M2-OUTPUT-PREVIEW-VALIDATION.md)。完整内容与 M2 保持 IN_PROGRESS；下一单元独立自测，真实 SMTP 仍需服务商和授权收件箱，受控审核与发布仍为 M4。以下旧阶段记录按各自日期保留。

2026-10-04 不可变送审/撤回单元已 VERIFIED：V12 精确绑定本人当前双 PASSED 的不可变快照，唯一待审、owner/CAS/幂等、待审禁改、撤回后修改与历史保留完成。全新固定 Linux API169 + Worker120 = **289**、前端 **56** 及全部检查通过；相同构件真实页面两轮送审/撤回/归档、HTTP 六类待审写409、旧撤回不影响新待审、冻结题面不变、正常与轮询回退 AC、六空队列、30 项实际数据库权限拒绝及精确清理通过。见 [验收](M2-CONTENT-REVIEW-VALIDATION.md)。本轮未重复 Worker SIGKILL，117 个运行文件逐字节等同上轮真实故障验收；截图接口不可用，保存真实 DOM 观察，不提供合成截图。完整内容与 M2 仍 IN_PROGRESS；下一步为输出生成预览、独立自测，外部 SMTP 仍需服务商和授权收件箱配置；受控审核身份/批准发布保持 M4。

> 当前阶段：M-1、M0、M1、M2、M3均VERIFIED；M3四项总门禁已PASS。下一步M4最小管理员认证与角色设计，待用户继续；M4/M5仍PLANNED。下方带日期阶段段落保留历史。
> 路线图描述先后顺序，不代表完成状态。

## 1. 状态规则

- `PLANNED`：已确认，尚未实现。
- `IN_PROGRESS`：正在开发。
- `IMPLEMENTED`：代码完成，尚未充分验证。
- `VERIFIED`：对应自动化测试或验收通过。
- `RELEASED`：进入有版本号的正式发布。
- `DEFERRED`：明确推迟到以后。
- `BACKLOG`：只有候选想法，尚未承诺进入版本。

当前 M-1、M0、M1、M2、M3 均为 `VERIFIED`（M3 4/4总门禁通过）；M4 至 V1.1 仍为 `PLANNED`；V2.0 仅为 `BACKLOG` 候选，不是承诺的里程碑。

## 2. 总体顺序

~~~text
文档基线
  → M-1 项目准备
  → M0 工程与判题纵向切片
  → M1 可靠异步判题
  → M2 题库与学习主流程
  → M3 班级与作业
  → M4 管理、搜索与降级
  → M5 Linux 验收与阿里云小范围上线
  → V1.0 Release
  → V1.1 题解社区

V2.0 综合学习内容：BACKLOG，需另行确认是否立项
~~~

每个里程碑通过门禁后再进入下一个，不并行铺开大量未闭环模块。

## 3. M-1：项目准备

目标：建立可追溯的工程起点。

验证结果（`VERIFIED`，2026-09-28）：

- 已确认并登记 Spring Initializr + create-vue 最小官方骨架，不引入大型业务脚手架；
- 已固定 JDK 21、Spring Boot 4.1.1、Maven 3.9.14、MyBatis Starter 4.1.0；PageHelper 为 `DEFERRED`；
- 已生成后端双模块、前端、`deploy`、文档和 CI 目录，并初始化 `main` 分支的 Git 仓库；主干加短期主题分支策略见 D-033；
- 已在当前 Windows 环境通过后端 `clean verify` 与前端类型/静态/格式/测试/构建检查；
- 用户已人工确认当前工程结构；ForgeOJ 自有代码已选择 Apache-2.0，版权人为池也，根许可证和直接依赖许可证记录已落地；
- 已在只读挂载源码、全新 Maven/npm 缓存的固定 Linux/amd64 容器中完成后端和前端复现；
- 首个公开提交已推送至 [ForgeOJ](https://github.com/chiye0384-dot/ForgeOJ)；真实 [GitHub Actions 运行 36386617957](https://github.com/chiye0384-dot/ForgeOJ/actions/runs/36386617957) 的 `backend` 与 `frontend` 两个 job 均为 `success`，M-1 门禁闭环。

交付物：

- 选择许可证清楚的通用脚手架，或决定使用最小自建骨架；
- 记录上游仓库、commit/tag、许可证和复用边界；
- 初始化 Git 仓库和分支策略；
- 建立后端多模块、前端、部署、文档目录；
- 接入格式检查、基础构建和最小 CI；
- 建立 `.env.example`，确认密钥不进入仓库。

门禁：

- 全新环境可以按 README 构建；
- 许可证检查完成；
- 不含未经授权复制的 OJ 代码；
- 项目结构经过一次人工审阅。

## 4. M0：最小判题纵向切片

目标：尽早验证最难的技术风险，不先做完整页面。

验证结果（`VERIFIED`，2026-09-30）：

- 用户已明确确认启动 M0；开发分支为 `feat/m0-vertical-slice`；
- 已完成 M0 直接依赖与许可证登记、纵向切片设计、四字段消息 JSON Schema、7 张业务表的 V1 Flyway migration，以及固定 digest 的 MySQL/RabbitMQ disposable Compose；
- 已在真实 MySQL 8.4.12 Testcontainer 中验证 migration，证明 API 账号不能读取隐藏测试、Worker 能读取隐藏测试但不能读取用户密码；Compose 两个服务的健康检查也已在隔离临时栈通过；
- 已加入仅由 `dev`/集成测试显式加载的原创开发题目和 BCrypt 预置账号；默认生产 migration 不创建公开默认密码；
- 已在真实 MySQL 8.4.12 Testcontainer 中验证服务端 Session 登录/注销、会话固定攻击防护、CSRF 拒绝、错误密码拒绝，以及公开题目 DTO 不暴露隐藏数据集和判题镜像字段；
- 已在真实 MySQL 8.4.12 Testcontainer 中验证 Submission、JudgeTask 与 Outbox 同事务创建；Outbox 无写权限时三表全部回滚，相同 Idempotency-Key 的顺序和 8 路并发重放只产生一组记录，不同 key 产生独立提交；
- 提交接口只接受规范 UUID、`JAVA_21` 和不超过 65,536 UTF-8 字节的无 package 单文件 `public class Main`；判题快照完整固化，四字段 Outbox payload 不含源码或隐藏数据；
- 已在固定 digest 的 RabbitMQ 4.3.6 Testcontainer 中验证 durable exchange/queue/binding、persistent 四字段 JSON 和 publisher confirm；只有已确认且可路由的消息才设置 `published_at`，删除目标队列后的退回消息保持未发布且不泄露 payload 到业务日志；
- 已在真实 MySQL 8.4.12 与 RabbitMQ 4.3.6 Testcontainers 中验证 Worker 严格四字段契约解析、manual ACK/prefetch 1、task/submission 对应校验、`QUEUED -> RUNNING` 双表事务更新、权限失败回滚，以及重复投递/八路并发只有一个执行权；
- 已验证 Worker 仅为对应的 `RUNNING` task/submission 读取提交时快照和对应版本隐藏测试；读取过程有界展开 gzip，并复核源码、单文件和有序数据集 SHA-256，篡改或错配时 fail closed；
- 已以 `ProcessBuilder(List<String>)` 实现不经过宿主 shell 的 Docker CLI 边界；真实 Docker Engine 测试证明未启动容器具有非 root、禁网、只读根、capabilities 删除、no-new-privileges、CPU/内存/PID 与受限 tmpfs 配置，并在测试后删除；
- 已在真实 Docker Engine 中验证源码和隐藏输入只经 stdin 传输、不进入 CLI 参数；每次提交只编译一次，每个测试点以独立非 root JVM 执行，首个失败后停止；
- 已覆盖 AC、WA、CE、RE、TLE、OLE，实施单用例时间、整次用户执行预算与 stdout/stderr 共享字节上限，并验证所有运行分支强制清理容器；
- 已通过 V2 Flyway migration 在不修改 V1 的前提下加入 OLE；JudgeTask 与 Submission 终态以短事务、行锁和版本条件同时写入，任一更新失败时整体回滚；
- 已在真实 RabbitMQ、MySQL 和 Docker 组合中验证“manual ACK → 幂等领取 → 快照读取 → 沙箱执行 → 终态提交 → ACK”；平台执行故障写 `SYSTEM_ERROR` 且 verdict 为空，重复消息不再次改变终态；
- Worker 消费与沙箱开关仍默认关闭，只在具备受控 Docker 环境时显式启用；
- 已实现仅提交所有者可读的 Submission 结果查询；查询以 `(submission_id, user_id)` 同时过滤，他人、不存在和格式错误的 ID 统一返回 404，响应只包含状态版本、处理状态、verdict 和脱敏诊断等白名单字段；
- 已实现 M0 前端工作台和 `/api` 开发代理；Vitest 以模拟 API 响应覆盖匿名会话、CSRF 登录、公开题目、带 Idempotency-Key 的 Java 21 提交、轮询至终态并停止，以及不显示未声明隐藏字段；
- 已在 disposable Windows + Docker Desktop 开发栈真实验证浏览器登录、读题、AC 提交、Outbox 发布、RabbitMQ 消费、Worker 受限 Docker 执行、双表终态写回、所有者查询和页面轮询；联表状态为 `FINISHED/AC/version 2`，队列清空且无沙箱容器残留；
- 提交 `39e91145` 在固定 Linux/amd64 Maven 镜像中通过 API 14 项、Worker 35 项测试，前端在固定 Node 镜像中通过类型、lint、格式、Vitest 和生产构建；
- 真实 Linux 进程级重放证明 Vite 代理、无 Docker 权限 API、MySQL/Outbox/RabbitMQ、独占 Docker 权限 Worker 和判题容器能够闭环，AC、WA、CE、RE、TLE、OLE 全部到达 `FINISHED/version 2`；
- `docs/M0-E2E-VALIDATION.md` 已对照设计第 12 节记录 20/20 门禁 `PASS`，因此 M0 标记为 `VERIFIED`；
- M0 关闭时的下一里程碑是 M1，当时进入 `IN_PROGRESS`；这是历史快照。M1 当前已 `VERIFIED`，实时证据以本文件第 5 节和 `M1-GATE-AUDIT.md` 为准。

最短流程：

1. 使用预置测试账号完成最小登录；
2. 浏览一道人为内置的题；
3. 提交 Java 21 `Main.java`；
4. API 创建 Submission 与 Outbox；
5. RabbitMQ 投递；
6. Worker 创建受限 Docker 容器；
7. 编译、运行测试并写回结果；
8. 页面轮询得到结果。

门禁：

- AC、WA、CE、RE、TLE 至少各有自动化样例；
- 重复投递不会重复产生业务结果；
- API 进程没有 Docker 权限；
- 超时后容器和临时文件会清理；
- 固定 Linux 环境可以复现。

暂不做：精美 UI、ES、完整班级、社区和复杂管理员页面。

M0 的登录只用于打通受保护提交链路，可使用预置账号和最小会话；注册、邮箱验证、找回密码、多会话和完整安全闭环在 M2 实现。

## 5. M1：可靠异步判题与安全边界

目标：把“能跑”提升为“故障下仍可解释和恢复”。

当前状态：`VERIFIED`（2026-10-02）。[最终 15 项门禁审计](M1-GATE-AUDIT.md) 全部 PASS：补齐 Outbox 同事件恢复和真实重新领取后旧 owner 全写路径拒绝，固定 Linux 全新缓存后端 API 49 + Worker 111、前端 8 项及全部检查通过；五项真实子 Worker 故障/凭据和 16 项安全程序无跳过。运行时代码与既有八 verdict/真实浏览器 normal/fallback/权限/日志/队列/空库 E2E 逐条目对应，资源最终清理完成。D-038 保持一个运行槽位加三个排队任务；满队列失败通过内部 `WAITING_RETRY` 保留运行槽位等待有限重试。M1 关闭时解决 L-026、保留 L-027～L-032；L-028 随后由账号单元关闭。仅本里程碑验证，不代表 M4 运维、M5 主机或发布完成；下文日期段落是历史快照。

最新更新（2026-10-02）：累计通知/日志/故障/安全/资源结果已提交为 `ab61c9a`，固定 Linux/amd64 全新缓存通过 API 48 + Worker 110、前端 8 项与全部检查，零失败/错误/跳过，无验证容器残留；复现脚本与完整证据见 [Linux 验证](M1-FIXED-LINUX-VALIDATION.md)。本轮按用户授权提交/推送功能分支，不创建完成 PR 或发布。下文未提交表述为各阶段当时状态；完整独立 Linux 纵向链路、OPS_ADMIN 运维与最终门禁仍缺，M1 状态不提升。

后续独立 Linux 纵向链路已通过并保存为 `2e1af57`：八 verdict、owner 通知/API 负向握手、logout/queued cancel、真实浏览器正常及断线轮询、日志/Outbox/权限/队列和精确清理均有证据；见 [M1 E2E](M1-E2E-VALIDATION.md)。该阶段复用 hash-verified Linux JAR，未再运行全量测试。当前进行最终 15 门禁审计，并补同一 Outbox 事件恢复和旧租约全写路径拒绝的直接回归；L-029～L-032 不因此关闭，M1 仍 `IN_PROGRESS`。

范围校正：独立管理员登录、角色鉴权、人工死信幂等重试和持久管理审计属于本路线图第 8 节 **M4**，不是 M1 第 10 节的 15 项门禁。前面交接和阶段记录把 OPS_ADMIN 提前写入 M1 是记录偏差，不是新增的用户决定；此处恢复原里程碑边界，不删减 V1.0 管理要求。M1 验收有限重试、Task/Submission/attempt/死信 Outbox 原子事实和独立死信路由，不实施管理员操作入口。下文各阶段未提交、未验收和下一步表述保留为当时快照，以本段和最终门禁记录为准。

2026-10-01 已在现有 M1 分支工作树实现 D-039 的所有者同源 WebSocket、三字段通知、单调版本、登出/终态清理与前端轮询/有界重连。通知阶段完整回归 API **38**、Worker **57** 全过且零跳过；前端 **8** 项测试及全部校验/构建通过，见 [通知验证记录](M1-NOTIFICATION-VALIDATION.md)。这是局部 Windows/Testcontainers 与模拟前端证据，不是浏览器/独立 Worker 的固定 Linux M1 重放。

同日追加关联脱敏 JSON 日志：内部 requestId、提交/任务/attempt 白名单、提交后事件、固定错误码与上下文清理，不改变数据库或 MQ 四字段契约。18:33 完整回归 API **46** + Worker **67**、前端 **8** 项全过，零失败/错误/跳过，见 [可观测性验证记录](M1-OBSERVABILITY-VALIDATION.md)。通知及日志均尚未提交推送；日志不是持久审计。更宽安全与崩溃/ACK 丢失矩阵、运维闭环、固定 Linux 验收和最终门禁仍未完成，不升级为 `VERIFIED`。

21:04:37 追加真实子 JVM 故障恢复证据：领取后 kill、终态提交但 ACK 前 kill、已启动 Worker 的孤儿沙箱回收、并行 Worker 启动不删除活跃沙箱，四项均通过。修复 task-only 容器名冲突及全标签启动清理风险，改为 attempt 独立身份、完整 Docker ID、只回收数据库已关闭 attempt。最新根回归 API **46** + Worker **83**（129 项），前端 **8** 项和全部校验通过，零失败/错误/跳过，无管理沙箱/测试子进程残留。见 [进程故障与沙箱恢复记录](M1-FAULT-RECOVERY-VALIDATION.md)。仍未提交推送；更宽安全矩阵、固定 Linux、运维闭环与最终门禁不因本轮通过而关闭。

范围：

- 完整处理状态与判题结果；
- 编译一次、逐测试启动 JVM、首错停止；
- 自测队列与正式提交队列；
- 用户并发和排队限制；
- Outbox 发布重试、手动 ACK、幂等、死信；
- Worker 中断与 ACK 丢失恢复；
- WebSocket 通知和轮询兜底；
- Docker 非 root、禁网、只读根、资源与输出限制；
- 固定镜像 digest 和 judge_version；
- 沙箱恶意测试集。

门禁：

- 消息重复、乱序边界和 Worker 崩溃测试通过；
- SYSTEM_ERROR 不会被记录为用户 RE；
- 隐藏数据和基础设施凭证不可从用户程序读取；
- 每个提交可通过 ID 串联 API、消息、Worker 和结果日志。

2026-10-01 21:39:40 完成当前安全阶段全量验证（10 月 2 日恢复会话核验）：API **46** + Worker **98**，144 项零失败/错误/跳过。14 项恶意程序及真实 Worker 凭据检查通过，修复子进程/临时文件清理及共享内存绕过；见 [安全验证记录](M1-SANDBOX-SECURITY-VALIDATION.md)。前端未改未重跑。下一步先补 MLE/SECURITY_VIOLATION 可信分类及必要合约/迁移评审，再固定 Linux、运维与最终门禁；本地资源限制证据不等于所有 verdict 已实现，M1 状态不提升，未提交推送。

2026-10-02 08:34:50 资源分类阶段根回归 API **48** + Worker **110**，158 项零失败/错误/跳过，无沙箱/测试子 Worker 残留。内核逐例 oom/PID max 增量得到 MLE/SECURITY_VIOLATION；V5 修复原列宽 16 无法存储安全结果，并验证旧数据保留、真实 MQ 重复投递与所有者查询。见 [资源结果记录](M1-RESOURCE-VERDICT-VALIDATION.md)。纯 JVM OOM、拒绝操作审计仍有 L-032 限制；下一步为固定 Linux M1 构建、通知/故障/资源结果重放、OPS_ADMIN 与最终门禁。状态不提升，未提交推送，前端未改未重跑。

## 6. M2：题库、账号与学习主流程

目标：完成普通学习者可用的核心产品。

当前状态：`VERIFIED`（2026-10-05），五项门禁全部通过，见 [最终审计](M2-GATE-AUDIT.md)。M2 交付普通学习流程、作者私有内容与不可变送审版本；M4 交付受控审核身份和批准发布。下方旧单元进展段落为各日期历史快照。

作者内容草稿阶段已 VERIFIED：V9 私有作者表、owner/CAS、逐条/ZIP 导入、独立参考和题解代码及草稿归档，固定 Linux 后端 261 / 前端 39、实际浏览器与独立审计清理见 [验收](M2-CONTENT-VALIDATION.md)。2026-10-04 正式双程序验证单元已 VERIFIED：V11 独立冻结快照、双 ACCEPTED、共享额度、attempt/lease fencing、私有版本历史；固定 Linux **283/50**、真实参考 AC/题解 WA 与双 AC、实际 Worker SIGKILL 后自然恢复、六队列/权限/日志审计和清理见 [验收](M2-CONTENT-VALIDATION-JOBS-VALIDATION.md)。完整内容仍 IN_PROGRESS，下一步是不可变送审/撤回和生成输出预览；自测/真实 SMTP 仍待完成。题解访问已 VERIFIED（最终 269/45、真实取消/跨账号/AC 解锁与审计清理），见 [验收](M2-SOLUTION-ACCESS-VALIDATION.md)；正式审核发布未完成。

2026-10-03 用户要求接续剩余 M2，实施顺序见 [剩余功能计划](M2-REMAINING-IMPLEMENTATION.md)。题库列表/筛选/分页/实际选题与 SMTP 配置适配单元已 VERIFIED，固定 Linux 后端 229 / 前端 24、真实选题 AC 和审计清理见 [验收](M2-LIBRARY-SMTP-VALIDATION.md)。个人学习记录单元也已 VERIFIED，固定 Linux 后端 241 / 前端 35、真实两页草稿冲突和 AC/进度/历史/题单操作、独立审计清理见 [验收](M2-LEARNING-RECORDS-VALIDATION.md)。下一单元为题目内容/审核版本，随后自测与题解。SMTP 本地 TLS 协议不能替代真实服务商投递，完整 M2 继续 IN_PROGRESS。

历史账号阶段（2026-10-02）：当时完整 M2 为 `IN_PROGRESS`。用户已确认首个普通账号单元的全部推荐方案，实施分支为 `feat/m2-accounts`，验收基于 M1 `d38e2c9` HEAD 的工作树快照；详细边界见 D-041 与 [普通账号设计](M2-ACCOUNTS-DESIGN.md)。注册/激活、JWT 与 MySQL 独立会话、刷新/撤销、改密/找回/补邮箱及 M1 通知兼容的账号单元已 `VERIFIED`：固定 Linux 后端 211 / 前端 13 项、相同产物的真实注册到 AC 与旧账号轮询兜底、完整独立审计和精确清理通过，见 [账号验收](M2-ACCOUNTS-VALIDATION.md)。题库、题单、草稿、自测、历史等下列剩余能力尚未完成，整个里程碑不提升为 `VERIFIED`。

范围：

- 注册、邮箱验证、登录、找回密码、多会话与注销会话；
- 公共题库、详情、标签与难度；
- 题目草稿、测试数据、私有参考程序；
- 公共审核版本和题目归档；
- 官方题解及公共查看规则；
- 官方题单、进度条；
- 个人私有题单；
- 服务端代码草稿和冲突提示；
- 自测与正式提交历史。

门禁：

- 注册到 AC 的端到端测试通过；
- 题目版本变化不会篡改历史提交；
- 普通用户无法读取隐藏测试和私有参考程序；
- 个人题单只对本人可见；
- 账号停用会使全部旧会话失效。

账号单元已覆盖真实 MySQL 注册事务回滚与唯一竞争、邮件令牌用途/期限/单次消费、未过期 JWT 的撤销、refresh 重用与多会话隔离、CSRF/Origin、旧账号升级与最小数据库权限，并保留 M1 配额、取消、通知和 Worker 回归；真实浏览器“注册→本地邮件→激活→邮箱登录→实际 AC”、固定 Linux 和独立审计/清理已闭环。SMTP 上线投递、Redis 缓存/分布式限流和完整 M2 五门禁仍须后续独立完成，不能由本单元通过代替。

## 7. M3：班级与作业

状态 `VERIFIED`（2026-10-08），4/4总门禁PASS，见 [最终审计](M3-GATE-AUDIT.md)。成员、私有题、作业、教学记录四单元分别见 [成员](M3-CLASSROOM-VALIDATION.md)、[私有题](M3-PRIVATE-PROBLEMS-VALIDATION.md)、[作业](M3-ASSIGNMENTS-VALIDATION.md)、[教学记录](M3-TEACHER-RECORDS-VALIDATION.md)。当前角色资源授权、永久快照/历史、冻结真实判题、共同题解和严格正式源码作用域完成。下列四门禁已由最新测试、运行输入和分别记录日期的真实证据审计通过；VERIFIED不等于生产Release。下一阶段M4，本轮不启动其实施。

目标：形成区别于纯刷题站的教学闭环。

范围：

- 班级创建、邀请码、OWNER/ASSISTANT/MEMBER；
- 成员退出、移除、恢复、所有权转让和归档；
- 班级私有题；
- 作业草稿、定时开始、截止和迟交策略；
- 成员快照和 PRECOMPLETED；
- 班级题解开放策略；
- 教师仅查看与本班作业相关的提交。

门禁：

- 班级作业端到端测试通过；
- 完整权限矩阵测试通过；
- 后加入、退出、移除和归档等边界有测试；
- 教师不能查看成员无关的私人练习代码。

## 8. M4：管理后台、搜索与降级

目标：补足真实系统所需的治理和运维能力。

范围：

- 独立管理员认证；
- CONTENT_REVIEWER、OPS_ADMIN、SUPER_ADMIN；
- 公共题审核；
- 异常任务与死信幂等重试；
- 管理员账号和审计日志；
- Redis 缓存、会话和限流降级；
- Elasticsearch 公共题全文搜索；
- ES 版本同步、死信、重建和 MySQL 回退；
- 最小 Prometheus/Grafana 面板。

门禁：

- 普通用户访问后台接口返回 403；
- 三个后台角色的越权操作均被拒绝；
- Redis、ES 和 RabbitMQ 故障演练通过；
- 索引删除后可从 MySQL 重建；
- 管理操作可追溯到管理员、对象、时间和原因。

## 9. M5：Linux 验收与小范围上线

目标：证明项目不只在开发机运行。

范围：

- 单机 Docker Compose；
- Nginx 与 HTTPS；
- Flyway 空库安装和版本升级；
- 配置、密钥、健康检查和冒烟测试；
- 阿里云或同类 Linux ECS 小范围试运行；
- MySQL 每日加密备份和异地副本；
- 恢复演练和应用回滚演练；
- 固定环境性能测试；
- 安全边界和运维手册。

门禁：

- 全新 Linux 主机可按文档部署；
- 备份能恢复到全新数据库；
- 应用镜像可以回滚；
- 性能报告记录环境、数据集、commit 和原始结果；
- 至少完成一轮受控真实用户试用与问题记录。

## 10. V1.0 Release：简历核心版

只有 M0 至 M5 全部门禁通过，才创建 V1.0 release。此时从证据矩阵中挑选三到五条已验证亮点写入简历。

V1.0 不因以下事项推迟：

- 用户题解互动；
- 综合论坛；
- 公开个人题单；
- 多语言；
- 微服务或 Kubernetes。

## 11. V1.1：题解社区版

范围：

- 从本人 AC 提交发布题解；
- 只读已验证代码；
- 标题模板兜底，思路和复杂度选填；
- 点赞、收藏；
- 三层评论和题目关联讨论；
- 举报、软删除、恢复和作者通知；
- 自助注销及匿名化。

门禁：

- 修改已验证代码必须重新 AC；
- 删除题解不影响原提交；
- 举报数量不会自动定罪；
- 内容审核访问遵守最小权限；
- 注销后身份信息按规则删除或匿名化。

## 12. V2.0 候选范围

V2.0 不是当前承诺。候选项仅包括：

- 可关联或不关联题目的简单学习帖子；
- 适合长讨论的评论结构；
- 用户主动发布的公开个人题单；
- 公开题单的收藏、复制、举报和下架；
- 公开内容在个人主页中的展示。

个人题单创建时默认私有。若用户主动公开，仍须支持取消公开并恢复私有，且不得通过公开题单泄露班级私有题或其他非公开题。是否进入 V2.0，要根据 V1.1 真实使用反馈另行决定。

## 13. 长期不做或需重新立项

- 多语言和多 JDK 版本；
- Special Judge、交互题和部分分；
- 竞赛与全站排行榜；
- 抄袭检测；
- 在线多文件 IDE；
- 大规模公网任意代码执行；
- AI 自动写题解或自动审核；
- 复杂社交、私信、关注和推荐；
- 多机房、高可用和 Kubernetes。

这些内容不是“做完 V2 自动增加”，而是需要新的需求访谈、威胁评估和资源预算。
