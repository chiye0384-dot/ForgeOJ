# ForgeOJ 代码归属与复用边界

> 更新日期：2026-10-02
> 当前范围：M-1、M0 与 M1 均为 `VERIFIED`；M1 15 门禁、固定 Linux 后端 160/前端 8 项和相同运行时代码的独立 E2E 证据闭环，见 `M1-GATE-AUDIT.md`。M2 普通账号单元现为 [VERIFIED](M2-ACCOUNTS-VALIDATION.md)，完整 M2 保持 `IN_PROGRESS`；独立管理认证与人工 DLQ 处置属于 M4，并未由本轮实现。下文日期标记保留各历史阶段的证据边界。

## 1. 生成与第三方部分

- `mvnw`、`mvnw.cmd`、`.mvn/wrapper/`：由 Spring Initializr 生成，其中 Maven Wrapper 来自 Apache Maven Wrapper 3.3.4；`mvnw.cmd` 另含 ForgeOJ 记录的一处 Windows 空值保护补丁。
- 两个 Spring Boot 启动类与最初的空上下文测试：来自 2026-09-27 的 Spring Initializr 生成产物。
- `frontend/` 的基础构建、TypeScript、Router、Vitest、ESLint、OxcLint 和 Prettier 配置：由 create-vue 3.22.3 模板生成后修改。
- Spring Boot、Spring Security、Spring AMQP、Flyway、Testcontainers、MyBatis、Vue、Vite 及其他 Maven/npm 包是第三方依赖，不是 ForgeOJ 自行实现的能力。加入依赖不能被表述为已经自行实现认证、消息可靠性、迁移或集成测试。

精确版本、commit、许可证和生成产物哈希见 `docs/UPSTREAM-AND-LICENSE.md` 与 `THIRD_PARTY_NOTICES.md`。

## 2. ForgeOJ 在 M-1 完成的改造

- 新建 Maven 根聚合/父 POM，将 API 与 Judge Worker 组织为两个互不依赖的可执行模块。
- 锁定 JDK 21、Spring Boot 4.1.1、Maven 3.9.14 和官方 MyBatis Starter 4.1.0。
- 将 H2 限定在测试作用域，Worker 明确设为 non-web；未引入任何虚构 Mapper 或业务实体。
- 修补 Maven Wrapper 3.3.4 在 Windows 上对空 `Target[0]` 直接索引而无法启动的问题；补丁依据与退役条件记录在 `docs/UPSTREAM-AND-LICENSE.md`。
- 从前端生成结果移除 Vue DevTools 及演示页文案，增加可重复的 `npm run verify` 检查链。
- 新建项目级 CI、`.env.example`、`deploy/` 边界说明、决策和归属文档。

## 3. ForgeOJ 必须自行设计和验证的业务

以下内容不来自当前脚手架，也不会从现有 OJ 或大型后台项目复制：

- 题目、测试数据、参考程序和判题版本模型；
- Submission、JudgeTask、Outbox 状态与转移；
- RabbitMQ 契约、幂等、重试、死信和故障恢复；
- Judge Worker 与 Docker 沙箱适配器；
- 隐藏测试、私有参考程序、班级作业和管理后台的权限边界；
- Redis/Elasticsearch 的一致性、降级与重建；
- 性能、安全和 Linux 故障演练证据。

M0 当前自行设计和实现的边界包括：预置账号的登录会话与权限规则、Flyway migration、Submission/JudgeTask/Outbox SQL 与状态机、四字段任务消息契约、Worker 幂等领取、不可变快照与隐藏测试完整性校验、Docker CLI 沙箱适配与清理、M0 前端工作台，以及相应验收测试。其中 ForgeOJ 已自行实现并局部验证 V1/V2 migration、dev/test seed、数据库账号权限、服务端 Session/CSRF 登录边界、公开题目字段白名单、Submission/JudgeTask/Outbox 原子写入与请求幂等、publisher confirm 后才标记已发布的 RabbitMQ Outbox 循环、Worker 严格消息校验、双表原子幂等领取、真实 Docker 执行、六种 M0 verdict 映射、双表终态事务写回、写回后 ACK、仅提交所有者可读且字段/诊断受限的结果查询，以及前端登录、读题、提交和终态轮询。2026-09-29 又在 disposable Windows 开发栈真实跑通浏览器 AC 主链路；固定 Linux 完整重放仍只能描述为“正在验证”。

`db/devdata/R__seed_m0_development_data.sql` 中的“两数之和”题面、样例和隐藏测试数据是为 ForgeOJ M0 编写的最小原创开发数据，不来自第三方题库。它只在 `dev` profile 或显式集成测试位置中加载，不属于默认生产 migration。Spring Security、BCrypt、MyBatis 和 Flyway 仍是第三方框架能力；ForgeOJ 自有部分是配置、数据模型、Mapper/DTO、接口边界和相应测试，不能把框架本身表述为自行实现。

M1 自有实现目前包括 V3/V4 attempt/lease/retry/quota 模型、租约栅栏、心跳与恢复扫描、有限重试/死信、Outbox 退避、用户配额和所有者排队取消，以及 D-039 的同源所有者状态通知与前端版本/轮询恢复。通知的 WebSocket 传输由 Spring Framework/Tomcat 提供，不是 ForgeOJ 自行实现协议栈。源码入口、命令和验证边界记录在 M1 设计及分阶段验证文档；固定 Linux 构建/子 JVM 故障/真实链路已有证据，最终门禁单独审计，M4 运维能力不算作本轮自有实现。

关联日志的自有部分是内部 requestId 生命周期、业务字段白名单、提交后事件、固定故障码与回归测试；JSON 编码和日志传输仍由既有 Spring Boot/SLF4J/Logback 提供。未引入日志采集平台或修改四字段消息，局部证据见 `M1-OBSERVABILITY-VALIDATION.md`，不等于固定 Linux 或持久审计验收。

真实进程故障阶段的自有部分包括 test-only 子 JVM 屏障/强制中断控制、数据库与队列证据断言、attempt 级容器身份、不可变 Docker ID 操作和基于已关闭 attempt 的保守回收。RabbitMQ 的连接关闭后重投递、Docker daemon、JDK ProcessBuilder 与 Testcontainers fixtures 仍是既有平台能力；没有新依赖、外部代码、迁移或数据库权限变更。记录见 `M1-FAULT-RECOVERY-VALIDATION.md`，不是固定 Linux 或任意网络丢包验收。

2026-10-01 沙箱安全阶段新增的有界恶意程序、真实 Worker 凭据探测和逐例进程/临时文件清理策略为 ForgeOJ 自有测试及适配实现。init 回收、namespace、cgroup、seccomp 和 capabilities 是 Docker/Linux 能力，chmod/find/pkill 为已有运行环境工具，不声称自行实现隔离内核。没有新增直接依赖、复制外部代码或更改镜像 digest；见 `M1-SANDBOX-SECURITY-VALIDATION.md`。

2026-10-02 资源结果的自有部分是可信 cgroup 事件读取/校验与逐例增量分类、平台故障优先级、八类终态映射、V5 列宽修复和真实 MQ/查询/升级保留测试。内核 oom/PID 计数由 Linux 提供，不是自建内存或进程控制器；未增加第三方依赖。边界见 `M1-RESOURCE-VERDICT-VALIDATION.md`。

同日新增的 `tools/validation/` 为 ForgeOJ 原创验证编排：固定镜像、只读源码复制、排除缓存/本地配置、输入哈希、报告收集与本轮精确资源清理。Maven/npm/JDK/Docker/Testcontainers 仍为第三方工具；复用已记录的 M0 镜像，不分发 Docker 二进制、不引入外部脚本或新业务依赖。实现提交 `ab61c9a` 的固定 Linux 后端 158 项、前端 8 项证据见 `M1-FIXED-LINUX-VALIDATION.md`，不声称完成 M5 Linux 主机验收。

随后独立 E2E 的 Compose/PowerShell 编排、Node 内置库的受限 HTTP/WebSocket 探针、故障代理、原创测试程序和脱敏事实审计为 ForgeOJ 自有验证实现；RFC 6455 只用作握手/帧格式参考，不复制协议代码，不把 Spring/Tomcat 的生产 WebSocket 能力算作自建协议栈。真实浏览器操作是人工验收，截图/记录不是自动 UI 测试。MySQL 初始化脚本子 shell 修复为本项目部署适配；镜像 entrypoint、数据库、MQ、浏览器和 Docker 隔离仍是第三方平台能力。没有新依赖或发布物，证据见 `M1-E2E-VALIDATION.md`。

最终审计补齐的同一 Outbox event 恢复成功、真实重新领取后拒绝旧 owner 全部写路径，为 ForgeOJ 在既有测试中的原创回归。只操作一次性 fixture，未复制外部测试、改业务代码或增加依赖/权限；记录偏差的 M1/M4 范围纠正以既有 Roadmap 为依据，不把未实现的管理员闭环计入成果。

M2 普通账号单元（`VERIFIED`）的自有部分是 V6 账号/会话/摘要模型和最小数据库授权、原子注册/quota、账号状态与邮箱令牌流程、refresh 单次轮换/重用撤销、当前/全部退出及密码变化撤销、Cookie/CSRF/精确 Origin、敏感写事务内会话复核、sid 通知关闭、账号页面、本地投递接口/模拟器、API 原状态空体错误处理与相应权限/并发/真实 HTTP 测试。JWT 编解码由 Spring Security JOSE 7.1.1 / Nimbus 10.9.1 提供，密码摘要由 BCrypt 提供，随机、SHA-256、本地 HTTP 和事务分别使用既有 JDK/框架能力；不把密码学、JWT 或 SMTP 表述为自建。固定 Linux 211/13、相同产物的真实新账号 AC/旧账号轮询兜底、八 verdict、独立权限/日志/队列审计与精确清理见 [账号验收](M2-ACCOUNTS-VALIDATION.md)和[脱敏证据](evidence/m2-accounts/README.md)。尚无真实 SMTP adapter 或生产投递证据，完整 M2 仍 `IN_PROGRESS`；来源/构件记录见 U-009，限制见 L-033～L-035。

2026-10-03 题库单元的自有实现为 V7 nullable 难度/标签模型、显式 SQL 标题转义/筛选/COUNT/分页/页内标签批量读取、公开 DTO 白名单、只读一致事务、题库页面及实际 slug 的工作区生命周期与回归。题库的 SQL/事务执行仍使用 MyBatis/MySQL/Spring，路由/模板由 Vue 提供；没有引入 PageHelper、外部题面或题库数据。隔离 Replay 的“两数较大值”题面、样例和测试由 ForgeOJ 原创，仅用于验收，不声称已审核发布正式题库内容。

同日学习记录单元自有实现为 V8 五表和列授权、题单 owner/CAS/排序事务、当前版本 AC 的 EXISTS/批量统计、安全历史 DTO、草稿 CAS 与前端冲突/代次管理，以及真实 DB/HTTP/迁移/前端测试。继续复用既有 MyBatis/Spring/MySQL/Vue，没有新增依赖或第三方题单内容；API 不获得 Docker，Worker 不获得学习表或账号读取权限。单元 VERIFIED，固定 Linux 241/35、真实浏览器与独立审计清理见 [记录](M2-LEARNING-RECORDS-VALIDATION.md)。

作者草稿阶段的自有部分为 V9 owner/CAS 与归档、独立参考/题解字段、测试压缩和摘要、JDK ZIP 有界解析/CRC/UTF-8/路径限制、请求体限制、作者编辑器及权限/迁移/竞争/回滚测试。复用既有 JDK/Spring/MyBatis/Vue，无新增依赖、外部代码或题库素材；阶段 VERIFIED，固定 Linux 261/39、真实浏览器/审计/清理见 [记录](M2-CONTENT-VALIDATION.md)。不把保存参考程序和题解代码表述为已经正式沙箱验证或发布。

同日 SMTP 适配的自有部分是模式选择、配置/地址/链接/TLS/超时约束与 loopback 协议测试。MIME 和 SMTP/TLS 客户端由 Spring Mail/Jakarta Mail/Angus 提供，证书/密钥在测试临时目录动态生成，不修改生产信任。普通账号阶段的“无 SMTP adapter”是当时的历史事实；本轮适配/题库单元 VERIFIED，固定 Linux 229/24 和实际选题 AC 见 [验收](M2-LIBRARY-SMTP-VALIDATION.md)。真实服务商投递仍未验收，依赖与许可见 U-010/[构件事实](evidence/m2-library/smtp-dependencies.md)，整体 M2 仍 IN_PROGRESS。

## 4. 明确排除

- 没有引入 RuoYi-Vue-Plus、ruoyi-vue-pro 或 JHipster 代码；
- 没有引入 CodeJudge 或其他 OJ 的代码、页面、题面、测试数据或性能数字；
- 没有引入 MyBatis-Plus 或 PageHelper；
- 没有创建泛化 `common` 模块。M0 的稳定跨进程消息已经以根目录中立 JSON Schema 表达；API 与 Worker 各自维护 DTO，不通过共享 Maven 代码模块形成耦合。

## 5. 许可证状态

池也拥有版权并有权许可的 ForgeOJ 自有源代码采用 Apache License 2.0，版权声明为 `Copyright 2026 池也`，见根 `LICENSE` 与 `NOTICE`。

这个根许可证不改变归属边界：Spring Initializr/create-vue 生成文件、Maven Wrapper、本仓库保留的其他上游代码以及 Maven/npm 依赖继续遵循各自许可证；题目、题解、测试数据和用户提交内容也不会自动改用 Apache-2.0。来源、修改点和发布义务继续记录在 `THIRD_PARTY_NOTICES.md`、`licenses/` 与直接依赖清单中。
