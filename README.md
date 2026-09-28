# ForgeOJ

> 当前状态：`M-1：项目准备` 进行中；最小工程骨架已生成并通过本机构建，业务实现尚未开始。
>
> 需求基线：2026-09-24；工程基线：2026-09-28

ForgeOJ 是一个面向 Java 学习者和小型教学班级的在线判题平台。它以“安全、可靠、可解释的异步判题”为核心，而不是以堆叠微服务或复制现有 OJ 页面为目标。

仓库已包含可构建的 M-1 工程起点和需求文档，但还没有 Controller、数据表、消息链路、判题器或任何已实现的业务功能。任何规划中的功能，在进入正式 release 并完成测试前，都不能写成已实现能力。

## 当前工程基线

- JDK 21 + Spring Boot 4.1.1 + Maven Wrapper 3.3.4 / Maven 3.9.14；
- Maven 根聚合工程，包含独立的 `forgeoj-api` 和 `forgeoj-judge-worker` 可执行模块；
- 持久层使用官方 `mybatis-spring-boot-starter:4.1.0`，不使用 MyBatis-Plus；
- PageHelper 暂不引入，到 M2 出现真实列表查询和分页语义时再评估；
- 前端为 create-vue 3.22.3 生成的 Vue 3 + TypeScript + Router + Vitest + ESLint + Prettier 最小骨架；
- H2 仅在测试作用域内用于空上下文启动检查，不代替以后的 MySQL 集成验证。

## 项目结构

- `forgeoj-api/`：对外 HTTP API 进程，不得获得 Docker 控制权限；
- `forgeoj-judge-worker/`：未来的独立判题 Worker，当前只有启动类；
- `frontend/`：前端工程骨架；
- `deploy/`：后续存放 Compose 和 Linux 部署资产，当前没有可用部署栈；
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
- [已知限制](docs/KNOWN_LIMITATIONS.md)
- [简历证据矩阵](docs/Resume-Evidence-Matrix.md)
- [性能测试计划](docs/Performance-Test-Plan.md)
- [上游与许可证策略](docs/UPSTREAM-AND-LICENSE.md)
- [代码归属与复用边界](docs/OWNERSHIP.md)
- [M-1 脚手架生成与验证记录](docs/M1-SCAFFOLD-VALIDATION.md)
- [M-1 直接依赖许可证清单](docs/DIRECT-DEPENDENCY-LICENSES.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)
- [面试问答骨架](docs/ForgeOJ-Interview-QA.md)

## 许可证

除文件或目录另有说明外，池也拥有版权并有权许可的 ForgeOJ 自有源代码采用 [Apache License 2.0](LICENSE)，版权声明见 [NOTICE](NOTICE)。生成文件、保留的上游代码和第三方依赖继续遵循各自许可证与归属要求，详见 [第三方声明](THIRD_PARTY_NOTICES.md)；项目根许可证不会把它们重新许可为 Apache-2.0。题目、题解、测试数据和用户提交内容也不因代码许可证而自动获得同一授权。

## 下一步门禁

用户已经完成人工结构审阅并确认 Apache-2.0 根许可证；后端和前端也已在只读源码、全新依赖缓存的 Linux 容器中复现通过。当前只剩：

1. 首次推送远程仓库后确认真实 GitHub Actions 的后端、前端任务通过。
2. CI 通过后将 M-1 标为 `VERIFIED`；进入 M0 仍需单独开始，不把工程骨架当成业务实现。

在上述门禁完成前，不开始大规模业务编码。
