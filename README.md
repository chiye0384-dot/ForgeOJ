# ForgeOJ

> 当前状态：`M-1：项目准备` 已验证通过；`M0：最小判题纵向切片` 为 `IN_PROGRESS`。数据/基础设施基线、开发账号会话登录、公开题目读取和提交事务已通过局部自动化验证，完整判题闭环尚未实现。
>
> 需求基线：2026-09-24；工程基线：2026-09-28

ForgeOJ 是一个面向 Java 学习者和小型教学班级的在线判题平台。它以“安全、可靠、可解释的异步判题”为核心，而不是以堆叠微服务或复制现有 OJ 页面为目标。

仓库已包含可构建的工程起点、M0 的 7 张核心业务表、开发/测试种子、最小会话认证、公开题目详情接口，以及 Submission/JudgeTask/Outbox 的原子创建和并发幂等处理。RabbitMQ 发布、Worker、Docker 判题和结果轮询仍未实现；任何规划中的完整能力，在进入正式 release 并完成相应门禁前，都不能写成已完成能力。

## 当前工程基线

- JDK 21 + Spring Boot 4.1.1 + Maven Wrapper 3.3.4 / Maven 3.9.14；
- Maven 根聚合工程，包含独立的 `forgeoj-api` 和 `forgeoj-judge-worker` 可执行模块；
- 持久层使用官方 `mybatis-spring-boot-starter:4.1.0`，不使用 MyBatis-Plus；
- M0 已声明 Spring Security、Spring AMQP 与 Flyway 运行依赖，以及仅用于测试的 Testcontainers；最小会话认证和 Flyway migration 已有局部实现与测试，异步判题能力仍在实现中；
- PageHelper 暂不引入，到 M2 出现真实列表查询和分页语义时再评估；
- 前端为 create-vue 3.22.3 生成的 Vue 3 + TypeScript + Router + Vitest + ESLint + Prettier 最小骨架；
- H2 仅在测试作用域内用于空上下文启动检查；M0 的 migration、数据库权限、认证和题目读取使用固定 digest 的真实 MySQL 8.4.12 Testcontainer 验证。

## 项目结构

- `forgeoj-api/`：对外 HTTP API 进程，不得获得 Docker 控制权限；
- `forgeoj-judge-worker/`：未来的独立判题 Worker，当前只有启动类；
- `frontend/`：前端工程骨架；
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

从 M0 起，后端 `clean verify` 会通过 Testcontainers 启动固定 digest 的 MySQL 8.4.12，验证真实 Flyway migration 和数据库读取边界，因此需要可用的 Linux Docker Engine。开发用 MySQL/RabbitMQ Compose 的启动和重置方法见 [deploy/README.md](deploy/README.md)。

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

M-1 的证据只证明工程起点、许可证边界和构建链路可复现。M0 已在短期功能分支完成“预置账号登录 → 查看内置题 → 原子创建 Submission/JudgeTask/Outbox”的局部验证，下一步是可靠发布 Outbox 到 RabbitMQ 并在 publisher confirm 后标记已发布；Worker/Docker 判题和结果轮询仍在后续。在 M0 门禁全部通过前不能声称在线判题能力已完成。
