# ForgeOJ

> 当前状态：`M-1：项目准备`、`M0：最小判题纵向切片` 和 `M1：可靠异步判题` 均为 `VERIFIED`。M0 通过 20 项门禁；M1 于 2026-10-02 通过 [15 项最终门禁审计](docs/M1-GATE-AUDIT.md)。M2 [普通账号](docs/M2-ACCOUNTS-VALIDATION.md)、[公开题库/SMTP 适配](docs/M2-LIBRARY-SMTP-VALIDATION.md)及[学习记录](docs/M2-LEARNING-RECORDS-VALIDATION.md)单元 VERIFIED，完整 M2 保持 `IN_PROGRESS`；正式内容验证/送审、自测与真实邮件投递尚待完成，M3～M5 为 `PLANNED`，尚未发布。
>
> 需求基线：2026-09-24；工程基线：2026-09-28

ForgeOJ 是一个面向 Java 学习者和小型教学班级的在线判题平台。它以“安全、可靠、可解释的异步判题”为核心，而不是以堆叠微服务或复制现有 OJ 页面为目标。

仓库已包含可构建的工程起点、M0 的 7 张核心业务表、开发/测试种子、最小会话认证、公开题目详情接口、Submission/JudgeTask/Outbox 的原子创建和并发幂等处理、基于 publisher confirm 的 RabbitMQ 发布，以及 Worker 对四字段消息的严格校验、手动 ACK、幂等领取、快照完整性校验、受限 Docker 执行和终态事务写回。Worker 会在结果提交到 MySQL 后 ACK；平台故障写 `SYSTEM_ERROR` 且不伪装成用户 verdict。结果查询只允许提交所有者读取，并对不存在、格式错误和他人提交统一返回 404。Vue 前端已实现登录、读取内置题、提交 Java 21 代码及轮询终态；除模拟 API 自动化外，真实页面 AC 闭环也已在 disposable Windows 开发栈通过。

`feat/m2-accounts` 已把预置账号认证升级为注册/邮箱激活、短 JWT 与 MySQL 独立会话、刷新轮换/重用撤销、当前/全部退出、改密/找回和旧账号补邮箱。普通账号单元已验证：固定 Linux 后端 **100 + 111 = 211** 项、前端 **13** 项及全部检查通过，相同产物的新账号注册→本地邮件→激活→邮箱登录→实际 AC、旧账号轮询兜底与独立权限/日志/队列审计和精确清理均通过。设计见 [普通账号设计](docs/M2-ACCOUNTS-DESIGN.md)与 D-041，命令、源码/JAR 哈希和范围见 [账号验收记录](docs/M2-ACCOUNTS-VALIDATION.md)，可提交脱敏事实见 [证据目录](docs/evidence/m2-accounts/README.md)。完整 M2 与真实 SMTP 尚未完成。

## 当前工程基线

2026-10-03 已继续验收[个人学习记录](docs/M2-LEARNING-RECORDS-VALIDATION.md)和[作者内容草稿阶段](docs/M2-CONTENT-VALIDATION.md)：`/learning` 支持本人题单/进度/草稿/历史，`/authoring` 支持私有内容/独立参考与题解代码/逐条与 ZIP 测试/归档。作者阶段固定 Linux 后端 261、前端 39 及实际浏览器/审计清理通过。随后[官方题解访问](docs/M2-SOLUTION-ACCESS-VALIDATION.md)单元 VERIFIED：最终固定 Linux 269/45，实际取消不留记录、跨账号隔离、真实 AC 自动解锁和降级审计清理通过；作者保存与合成快照仍不等于正式验证或发布，完整内容生命周期、自测和真实 SMTP 仍待完成。

- JDK 21 + Spring Boot 4.1.1 + Maven Wrapper 3.3.4 / Maven 3.9.14；
- Maven 根聚合工程，包含独立的 `forgeoj-api` 和 `forgeoj-judge-worker` 可执行模块；
- 持久层使用官方 `mybatis-spring-boot-starter:4.1.0`，不使用 MyBatis-Plus；
- M0 已声明 Spring Security、Spring AMQP 与 Flyway 运行依赖，以及仅用于测试的 Testcontainers；最小会话认证、Flyway migration 与异步判题纵向链路已通过 M0 门禁；M1 又实现 attempt/lease 栅栏、心跳、恢复扫描、有限重试/死信、Outbox 退避、独立队列、用户配额/排队取消，以及所有者同源 WebSocket 与前端版本/轮询恢复，验收状态见 [最终门禁审计](docs/M1-GATE-AUDIT.md)；
- 已验证的 M2 账号单元加入 `spring-security-oauth2-jose:7.1.1` 与其传递的 Nimbus JOSE+JWT 10.9.1；签名/解析是第三方能力，账号会话模型、撤销、权限和流程为 ForgeOJ 实现，来源与许可证见 [U-009](docs/UPSTREAM-AND-LICENSE.md)和[直接依赖清单](docs/DIRECT-DEPENDENCY-LICENSES.md)；
- PageHelper 暂不引入，到 M2 出现真实列表查询和分页语义时再评估；
- 前端基于 create-vue 3.22.3 的 Vue 3 + TypeScript + Router + Vitest + ESLint + Prettier，实现了 M0 最小判题工作台；
- H2 仅在测试作用域内用于空上下文启动检查；M0 的 migration、数据库权限、认证和题目读取使用固定 digest 的真实 MySQL 8.4.12 Testcontainer 验证。

## 项目结构

- `forgeoj-api/`：对外 HTTP API 进程，不得获得 Docker 控制权限；
- `forgeoj-judge-worker/`：独立判题 Worker；已有消息校验、幂等领取、快照读取、Docker CLI 编译/逐例执行和终态事务写回；默认开关仍关闭，需在具备受控 Docker 环境时显式启用；
- `frontend/`：判题工作台与已验证的 `/account` 普通账号页，开发服务器将 `/api` 代理到本机 8080 端口；
- `deploy/`：已包含 M0 的 MySQL/RabbitMQ disposable 开发 Compose；它尚不是生产部署栈；
- `contracts/`：API 与 Worker 之间的中立消息 JSON Schema 和合法样例，不形成 Maven 模块耦合；
- `docs/`：需求、决策、路线、归属和证据文档。

## 构建与验证

前置条件：JDK 21、Node.js 24.14.1、npm 11.11.0。Linux/macOS 还需提供 `bash` 和 `unzip`，供脚本型 Maven Wrapper 下载、校验并解压固定的 Maven 3.9.14 发行包。

Windows 后端：

~~~powershell
.\mvnw.cmd --batch-mode clean verify
~~~

Linux/macOS 后端：

~~~bash
bash ./mvnw --batch-mode clean verify
~~~

前端：

~~~powershell
Set-Location .\frontend
npm ci
npm run verify
~~~

`npm run verify` 会依次执行类型检查、静态检查、格式检查、单元测试和生产构建。

固定 Linux/amd64、只读源码与全新缓存复验入口为 `tools/validation/Verify-FixedLinux.ps1`，可从仓库根执行；前提为可用 Docker、固定镜像与无其他 ForgeOJ 沙箱测试并发。报告保存到忽略的 `target/forgeoj-linux-*`，不改真实数据库。详细参数及验证与生产 API 权限的区别见 [Linux 验证记录](docs/M1-FIXED-LINUX-VALIDATION.md)。

独立 Linux API/Worker/Vite 与空数据库的完整链路重放入口为 `tools/validation/Replay-FixedLinux.ps1`；八类结果、真实通知/断线轮询、权限、日志/队列和精确清理步骤见 [M1 E2E 记录](docs/M1-E2E-VALIDATION.md)，账号模块须显式传入本轮成功构建目录与 JAR 哈希，见 [账号验收记录](docs/M2-ACCOUNTS-VALIDATION.md)。只使用独立一次性数据库和 PUBLIC TEST 账号，不运行在真实开发/生产数据上。

从 M0 起，后端 `clean verify` 会通过 Testcontainers 启动固定 digest 的 MySQL 8.4.12 和 RabbitMQ 4.3.6，并使用已登记的固定 Temurin Java 21 镜像真实编译和执行原创测试程序，因此需要可用的 Linux Docker Engine。缺少判题镜像时测试会按固定 digest 拉取。开发用 MySQL/RabbitMQ Compose 的启动和重置方法见 [deploy/README.md](deploy/README.md)。

## 本地开发账号与邮件

启用 Spring `dev` profile 时，Flyway 才会额外加载 `db/devdata` 中的开发种子：

- 用户名：`learner`
- 密码：`forgeoj-dev-only`
- 内置题目：`sum-two-integers`

该固定密码只存在于 dev/test 种子中；默认 migration 不创建用户或题目，生产环境不得启用 `dev` profile。M2 V6 保留旧账号 ID、密码、状态与历史，因此 `learner` 仍可用用户名登录；其邮箱为空，必须在 `/account` 用当前密码申请并验证补邮箱后才能找回密码。注册的新密码适用至少 12 字符、最多 72 UTF-8 字节的规则，既有密码不会被迁移覆盖。

在隔离开发数据库启动 API 的 `dev` profile 后，打开 [账号页](http://localhost:5173/account)注册，再到 [本地邮件模拟器](http://127.0.0.1:2525/)打开激活链接并明确确认。前端需独立运行 `npm run dev`，API 数据库/MQ 环境变量与完整步骤见 [开发部署说明](deploy/README.md)。模拟器仅 dev/test、loopback、内存最多 100 条，进程关闭后清空；若前端地址或端口不同，需要用 `FORGEOJ_AUTH_MAIL_APP_URL` 配成实际浏览器地址。

当前实现使用最长 5 分钟的访问 JWT 和 MySQL 中绝对期限 7 天的独立 session，不再以 HttpSession 作为认证事实。每个受保护请求复核数据库，旧会话撤销即使 JWT 未过期也不再放行。访问/refresh Cookie 为 HttpOnly、host-only、SameSite=Strict；所有写仍须精确同源 Origin 与 CSRF。升级后旧进程会话需重新登录。Redis 缓存和分布式限流属于 M4。

默认 `forgeoj.auth.mail.mode=disabled`，不连接真实 SMTP。2026-10-03 已新增显式 `smtp` 适配，要求 STARTTLS/implicit TLS、信任链/主机校验及 socket 超时，配置见 [SMTP 设计](docs/M2-SMTP-DESIGN.md)和 `.env.example`；dev 默认 local，可由邮件模式环境变量覆盖。真实服务商配置与邮箱投递仍待验收，开发协议测试不能代替实际送达。生产必须通过 `FORGEOJ_AUTH_JWT_SECRET` 提供至少 32 字节的私有随机签名密钥并保留 Secure Cookie；只有隔离 HTTP dev/test 可关闭 Secure。缺省 dev 临时签名密钥重启会变化，公开测试密钥不得用于真实部署。相关边界见 [已知限制 L-033～L-035](docs/KNOWN_LIMITATIONS.md)。

## V1.0 已确认目标范围

- 计划仅支持 Java 21、单文件 Main.java 和标准输入输出题。
- 计划将 API 服务与 Judge Worker 作为两个独立进程。
- 计划使用 RabbitMQ、事务 Outbox、幂等消费和死信处理保证异步任务可靠性。
- 计划使用一次性 Docker 沙箱执行不可信代码，并明确其安全边界。
- 计划支持公共题库、官方题解、班级作业、题目审核和独立管理后台。
- Redis 计划用于缓存、会话、限流和短期幂等；MySQL 作为最终事实来源。
- Elasticsearch 计划只索引公共题目，并支持 MySQL 降级查询。
- 最终在固定 Linux 环境验收，优先部署到阿里云 Linux ECS 做小范围试运行。
- 不制造故意缺陷；使用真实范围限制和工程权衡作为面试讨论点。

## 文档索引

- [需求基线](docs/ForgeOJ-Requirements.md)
- [决策日志](docs/ForgeOJ-Decision-Log.md)
- [M2 剩余功能实施顺序](docs/M2-REMAINING-IMPLEMENTATION.md)
- [M2 官方题解访问验收](docs/M2-SOLUTION-ACCESS-VALIDATION.md)
- [M2 题库与 SMTP 适配验收](docs/M2-LIBRARY-SMTP-VALIDATION.md)
- [M2 学习记录验收](docs/M2-LEARNING-RECORDS-VALIDATION.md)
- [M2 个人学习记录设计（尚待实施）](docs/M2-LEARNING-RECORDS-DESIGN.md)
- [版本路线图](docs/ForgeOJ-Roadmap.md)
- [M0 最小判题纵向切片设计](docs/M0-VERTICAL-SLICE-DESIGN.md)
- [M0 真实纵向链路验证记录](docs/M0-E2E-VALIDATION.md)
- [M1 配额与排队取消验证记录](docs/M1-QUOTA-CANCELLATION-VALIDATION.md)
- [M1 所有者通知与轮询恢复验证记录](docs/M1-NOTIFICATION-VALIDATION.md)
- [M1 关联脱敏日志验证记录](docs/M1-OBSERVABILITY-VALIDATION.md)
- [M1 真实 Worker 进程故障与沙箱恢复验证记录](docs/M1-FAULT-RECOVERY-VALIDATION.md)
- [M1 沙箱恶意代码验证与清理修复](docs/M1-SANDBOX-SECURITY-VALIDATION.md)
- [M1 可信资源结果与存储验证](docs/M1-RESOURCE-VERDICT-VALIDATION.md)
- [M1 固定 Linux 构建与自动化验证](docs/M1-FIXED-LINUX-VALIDATION.md)
- [M1 独立 Linux 链路与真实页面验证](docs/M1-E2E-VALIDATION.md)
- [M2 普通账号设计与验收范围](docs/M2-ACCOUNTS-DESIGN.md)
- [M2 普通账号最终验收记录](docs/M2-ACCOUNTS-VALIDATION.md)
- [M2 普通账号脱敏事实与截图](docs/evidence/m2-accounts/README.md)
- [已知限制](docs/KNOWN_LIMITATIONS.md)
- [简历证据矩阵](docs/Resume-Evidence-Matrix.md)
- [性能测试计划](docs/Performance-Test-Plan.md)
- [上游与许可证策略](docs/UPSTREAM-AND-LICENSE.md)
- [代码归属与复用边界](docs/OWNERSHIP.md)
- [M-1 脚手架生成与验证记录](docs/M1-SCAFFOLD-VALIDATION.md)
- [直接依赖许可证清单](docs/DIRECT-DEPENDENCY-LICENSES.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)
- [面试问答骨架](docs/ForgeOJ-Interview-QA.md)

## 许可证

除文件或目录另有说明外，池也拥有版权并有权许可的 ForgeOJ 自有源代码采用 [Apache License 2.0](LICENSE)，版权声明见 [NOTICE](NOTICE)。生成文件、保留的上游代码和第三方依赖继续遵循各自许可证与归属要求，详见 [第三方声明](THIRD_PARTY_NOTICES.md)；项目根许可证不会把它们重新许可为 Apache-2.0。题目、题解、测试数据和用户提交内容也不因代码许可证而自动获得同一授权。

## 当前门禁结论

用户已经完成人工结构审阅并确认 Apache-2.0 根许可证；后端和前端已在只读源码、全新依赖缓存的 Linux 容器中复现通过。首个公开提交的 [GitHub Actions 运行](https://github.com/chiye0384-dot/ForgeOJ/actions/runs/36386617957) 中，`backend` 与 `frontend` 两个 job 也均为 `success`，因此 M-1 标记为 `VERIFIED`。

M-1 的证据只证明工程起点、许可证边界和构建链路可复现。M0 已闭环“预置账号登录 → 查看内置题 → 原子创建 Submission/JudgeTask/Outbox → RabbitMQ confirm 后标记已发布 → Worker 原子幂等领取 → 校验快照与隐藏测试 → 受限 Docker 执行 → Submission/JudgeTask 同事务写入终态 → ACK → 所有者查询 → 页面轮询”。2026-09-29 的 disposable Windows 开发栈完成真实浏览器 AC；2026-09-30 的提交 `39e91145` 又在固定 Linux/amd64 中通过 49 项后端测试、前端全部校验和 AC/WA/CE/RE/TLE/OLE 真实进程链路，20 项 M0 门禁全部 `PASS`。详细 commit、镜像 digest、产物哈希和清理记录见 [M0 验证记录](docs/M0-E2E-VALIDATION.md)。M1 并发配额与所有者 `QUEUED` 取消实现提交为 `44642f3`：运行中允许另有三个排队任务，满队列重试保留原运行槽位；该步 Windows 回归 API 26 + Worker 57，详见 [配额取消记录](docs/M1-QUOTA-CANCELLATION-VALIDATION.md)。2026-10-01 的本地工作树又完成所有者同源通知与关联脱敏 JSON 日志，日志阶段完整回归 API 46 + Worker 67、前端 8 项全过，见 [通知记录](docs/M1-NOTIFICATION-VALIDATION.md)与[日志记录](docs/M1-OBSERVABILITY-VALIDATION.md)。

21:04:37 故障阶段根 `clean verify` 通过 API **46** + Worker **83**（129 项，零失败/错误/跳过），前端 **8** 项及全部检查通过。新增真实 Worker 子 JVM kill/租约恢复、终态提交但 ACK 前中断、重复投递、孤儿沙箱回收与活跃 owner 保留，修复 attempt 容器身份和保守清理，末尾无管理沙箱/测试子进程残留，见 [故障恢复记录](docs/M1-FAULT-RECOVERY-VALIDATION.md)。本地 Windows 进程测试不等于固定 Linux 或任意 ACK 丢包演练。

安全阶段于 **2026-10-01 21:39:40** 完成根 `clean verify`：API **46** + Worker **98**，共 **144** 项，零失败/错误/跳过；10 月 2 日恢复会话后核验结果与零沙箱/测试子进程残留。新增 14 项受限容器恶意程序及真实 Worker 凭据检查，修复临时文件、子进程回收和共享内存隔离问题，见 [安全验证记录](docs/M1-SANDBOX-SECURITY-VALIDATION.md)。

最新资源结果阶段于 **2026-10-02 08:34:50** 完成根 `clean verify`：API **48** + Worker **110**，共 **158** 项，零失败/错误/跳过、无沙箱/测试子 Worker 残留。内核可确认的 cgroup OOM/PID 超限现在写为 MLE/SECURITY_VIOLATION；V5 修复安全结果存不下的列宽，保留旧结果，部署须先迁移再启动新 Worker。真实 MQ 消费与重复投递、所有者查询和升级保存历史均通过，见 [资源结果验证](docs/M1-RESOURCE-VERDICT-VALIDATION.md)。纯 JVM OOM/无审计的拒绝操作保留 L-032 的边界；前端本阶段未改未重跑。固定 Linux、运维闭环与最终门禁仍待完成，M1 为 `IN_PROGRESS`，修改未提交推送。

上述“未提交”是当时记录。实现已保存为 **`ab61c9a`**；2026-10-02 固定 Linux/amd64 全新缓存复验通过后端 **158** 项、前端 **8** 项和全部校验/构建，零失败/错误/跳过、无容器残留，见 [Linux 记录](docs/M1-FIXED-LINUX-VALIDATION.md)。用户授权本轮提交并推送现有 M1 分支，不合并/发布；功能分支 push 不触发现有 CI。M1 仍为 `IN_PROGRESS`，下一步是独立 Linux 纵向链路、OPS_ADMIN 运维与最终门禁审计。

后续独立 Linux 纵向链路已通过并保存为 `2e1af57`，复用上述已核验 JAR；八 verdict 均 FINISHED/version 2，真实浏览器正常通知与 WebSocket 不可用时的轮询均显示 AC。11 个提交（10 完成/1 取消）、11 个已发布四字段 Outbox、4 个空队列、日志 ID 与 API 无 Docker/隐藏测试权限均通过审计，临时栈/数据/镜像已精确清理。另修复 MySQL 空库初始化脚本 strict options 泄漏。见 [E2E 证据](docs/M1-E2E-VALIDATION.md)；该阶段没有又跑 158/8 全量测试。范围校正：独立管理员认证/角色、人工 DLQ 重试与持久管理审计属于 Roadmap M4，不作为 M1 剩余工作；当前进行 [最终 15 项门禁审计](docs/M1-GATE-AUDIT.md)。

M1 最终收尾：补齐同一 Outbox 事件恢复与旧租约全写路径拒绝的直接集成回归后，10:43:48 固定 Linux 全新缓存后端 **49 + 111 = 160** 项、10:44 前端 **8** 项与全部检查通过，零失败/错误/跳过且资源清理完成。新旧 JAR 共 229 个 runtime 类/资源/依赖条目字节一致，既有真实链路证据可关联而不冒称再跑浏览器。**15/15 门禁 PASS，M1 为 VERIFIED**；该阶段关闭 L-026、保留 L-027～L-032。

随后批准并完成的 M2 普通账号单元为 **VERIFIED**：2026-10-02T08:33:55Z 固定 Linux 后端 **100 + 111 = 211** 项零失败/错误/跳过，08:34:13Z 前端 **13** 项和全部检查通过；08:46:16.682Z 新产物的独立 Replay 审计核实 11 个提交（10 完成/1 取消）、11 个已发布 Outbox、4 个空队列、真实新账号 AC/旧账号轮询 AC、八 verdict 与权限，精确清理和 188 个输入无漂移核查完成。关闭 L-028 并保留历史，L-027、L-029～L-035 继续有效。当前仅账号单元验证通过，完整 **M2 仍 IN_PROGRESS**；管理后台、真实 SMTP、云主机上线、性能验收与 Release 未完成。证据见 [账号验收](docs/M2-ACCOUNTS-VALIDATION.md)；以上日期段落保留各阶段当时状态，当前范围以本段及页面顶部为准。
