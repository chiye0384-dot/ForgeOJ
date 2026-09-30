# ForgeOJ

> 当前状态：`M-1：项目准备` 和 `M0：最小判题纵向切片` 均为 `VERIFIED`。M0 已通过固定 Linux/amd64 全量构建、真实进程级六 verdict 闭环和最终 20 项门禁审计；`M1：可靠异步判题` 为 `IN_PROGRESS`，尚未通过完整门禁。
>
> 需求基线：2026-09-24；工程基线：2026-09-28

ForgeOJ 是一个面向 Java 学习者和小型教学班级的在线判题平台。它以“安全、可靠、可解释的异步判题”为核心，而不是以堆叠微服务或复制现有 OJ 页面为目标。

仓库已包含可构建的工程起点、M0 的 7 张核心业务表、开发/测试种子、最小会话认证、公开题目详情接口、Submission/JudgeTask/Outbox 的原子创建和并发幂等处理、基于 publisher confirm 的 RabbitMQ 发布，以及 Worker 对四字段消息的严格校验、手动 ACK、幂等领取、快照完整性校验、受限 Docker 执行和终态事务写回。Worker 会在结果提交到 MySQL 后 ACK；平台故障写 `SYSTEM_ERROR` 且不伪装成用户 verdict。结果查询只允许提交所有者读取，并对不存在、格式错误和他人提交统一返回 404。Vue 前端已实现登录、读取内置题、提交 Java 21 代码及轮询终态；除模拟 API 自动化外，真实页面 AC 闭环也已在 disposable Windows 开发栈通过。

## 当前工程基线

- JDK 21 + Spring Boot 4.1.1 + Maven Wrapper 3.3.4 / Maven 3.9.14；
- Maven 根聚合工程，包含独立的 `forgeoj-api` 和 `forgeoj-judge-worker` 可执行模块；
- 持久层使用官方 `mybatis-spring-boot-starter:4.1.0`，不使用 MyBatis-Plus；
- M0 已声明 Spring Security、Spring AMQP 与 Flyway 运行依赖，以及仅用于测试的 Testcontainers；最小会话认证、Flyway migration 与异步判题纵向链路已实现并通过 M0 门禁；M1 已实现并在 Windows/Testcontainers 中验证 attempt/lease 栅栏、心跳、恢复扫描、有限重试、死信、Outbox 退避和队列边界，但整个 M1 仍未验收；
- PageHelper 暂不引入，到 M2 出现真实列表查询和分页语义时再评估；
- 前端基于 create-vue 3.22.3 的 Vue 3 + TypeScript + Router + Vitest + ESLint + Prettier，实现了 M0 最小判题工作台；
- H2 仅在测试作用域内用于空上下文启动检查；M0 的 migration、数据库权限、认证和题目读取使用固定 digest 的真实 MySQL 8.4.12 Testcontainer 验证。

## 项目结构

- `forgeoj-api/`：对外 HTTP API 进程，不得获得 Docker 控制权限；
- `forgeoj-judge-worker/`：独立判题 Worker；已有消息校验、幂等领取、快照读取、Docker CLI 编译/逐例执行和终态事务写回；默认开关仍关闭，需在具备受控 Docker 环境时显式启用；
- `frontend/`：M0 最小判题工作台，开发服务器将 `/api` 代理到本机 8080 端口；
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

从 M0 起，后端 `clean verify` 会通过 Testcontainers 启动固定 digest 的 MySQL 8.4.12 和 RabbitMQ 4.3.6，并使用已登记的固定 Temurin Java 21 镜像真实编译和执行原创测试程序，因此需要可用的 Linux Docker Engine。缺少判题镜像时测试会按固定 digest 拉取。开发用 MySQL/RabbitMQ Compose 的启动和重置方法见 [deploy/README.md](deploy/README.md)。

## M0 本地开发账号

启用 Spring `dev` profile 时，Flyway 才会额外加载 `db/devdata` 中的开发种子：

- 用户名：`learner`
- 密码：`forgeoj-dev-only`
- 内置题目：`sum-two-integers`

该固定密码只存在于 dev/test 种子中；默认 migration 不创建用户或题目，生产环境不得启用 `dev` profile。当前 Session 存于单个 API 进程内存，只服务 M0 最短链路验证，不是 V1.0 的 JWT、Redis 或多会话方案。

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
- [版本路线图](docs/ForgeOJ-Roadmap.md)
- [M0 最小判题纵向切片设计](docs/M0-VERTICAL-SLICE-DESIGN.md)
- [M0 真实纵向链路验证记录](docs/M0-E2E-VALIDATION.md)
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

M-1 的证据只证明工程起点、许可证边界和构建链路可复现。M0 已闭环“预置账号登录 → 查看内置题 → 原子创建 Submission/JudgeTask/Outbox → RabbitMQ confirm 后标记已发布 → Worker 原子幂等领取 → 校验快照与隐藏测试 → 受限 Docker 执行 → Submission/JudgeTask 同事务写入终态 → ACK → 所有者查询 → 页面轮询”。2026-09-29 的 disposable Windows 开发栈完成真实浏览器 AC；2026-09-30 的提交 `39e91145` 又在固定 Linux/amd64 中通过 49 项后端测试、前端全部校验和 AC/WA/CE/RE/TLE/OLE 真实进程链路，20 项 M0 门禁全部 `PASS`。详细 commit、镜像 digest、产物哈希和清理记录见 [M0 验证记录](docs/M0-E2E-VALIDATION.md)。M1 当前已推进到独立队列边界，下一项是并发用户配额和 `QUEUED` 取消；在通知、可观测性、安全/崩溃矩阵与固定 Linux 验收完成前，不得把 M1 标为 `VERIFIED`。
