# M0 真实纵向链路验证记录

> Windows 真实浏览器验证：2026-09-29，提交 `c25a7a2`
> 固定 Linux/amd64 闭环验证：2026-09-30，提交 `39e91145ffa32d6364d849c95edd6c72d6429e42`
> 结果：M0 的 20 项门禁全部获得可重复证据，M0 于 2026-09-30 从 `IN_PROGRESS` 升级为 `VERIFIED`。

## 1. 验证目标

本记录先在 Windows 开发环境中验证真实浏览器 AC，再在固定 Linux/amd64 容器进程环境中重放同一条纵向链路并覆盖全部六种 M0 verdict：

```text
Vue 页面
  -> Spring Security Session / CSRF
  -> Submission + JudgeTask + Outbox 事务
  -> RabbitMQ
  -> 独立 Judge Worker
  -> 受限 Docker Java 21 容器
  -> MySQL 终态
  -> 所有者结果查询
  -> Vue 轮询显示结果
```

第 2—4 节保留 2026-09-29 Windows 阶段的历史证据；第 5—6 节记录最终固定 Linux 重放和 20 项门禁审计。M0 通过不代表 M1 的崩溃恢复、有限重试和死信闭环已实现，也不替代 M5 的固定 Linux 主机安全与性能验收。

## 2. 隔离环境

- 宿主：Windows x64；
- 后端运行时：Eclipse Temurin JDK 21.0.12.1；
- Docker Engine：Linux 29.8.0；
- Docker Compose：v5.5.1；
- Compose project：`forgeoj-m0-e2e`；
- MySQL：仓库固定的 8.4.12 tag + digest，临时映射到 `127.0.0.1:33306`；
- RabbitMQ：仓库固定的 4.3.6-management tag + digest，临时映射到 `127.0.0.1:35672`；
- API：独立 Java 进程，端口 `8080`，启用 `dev` profile；
- Worker：独立 Java 进程，显式开启 consumer 与 sandbox；
- 前端：Vite 开发服务器，端口 `5173`，通过 `/api` 同源代理访问 API；
- 所有数据库、队列、密码和端口配置仅用于此次 disposable 栈；验证结束后容器、网络、卷和临时密码文件全部删除。

## 3. 实际过程与结果

1. 从空数据卷启动 MySQL 和 RabbitMQ，两个服务最终均为 `healthy`。
2. API 启动时由 Flyway 依次执行 V1、V2 和 `dev` repeatable seed，日志显示 3 个 migration 成功。
3. Worker 连接 `/forgeoj` vhost，并在启动时通过 Docker Linux Engine 可用性检查。
4. 先通过真实 HTTP 客户端验证一次 AC，再在真实浏览器打开 Vue 页面完成登录、读题和提交。
5. 浏览器页面最终显示：

```text
submissionId = f72fa75b-a853-48b5-aa24-ec5a886e4ef2
processingStatus = FINISHED
verdict = AC
statusVersion = 2
```

6. 对同一条浏览器提交做只读数据库联表核对：

```text
submission.processing_status = FINISHED
submission.verdict = AC
submission.status_version = 2
judge_task.task_status = FINISHED
judge_task.status_version = 2
outbox_event.published_at IS NOT NULL = 1
```

7. RabbitMQ 队列 `forgeoj.judge.submission.v1` 的 `messages_ready=0`、`messages_unacknowledged=0`。
8. `docker ps -a --filter label=com.forgeoj.managed=true` 没有返回容器，证明本次终态后无 ForgeOJ 沙箱容器残留。
9. API 和 Worker 收到中断后均执行正常关闭；随后删除 `forgeoj-m0-e2e` 的容器、网络和两个数据卷。

## 4. Windows 阶段结论

2026-09-29 的验证把 M0 从“后端局部组合测试 + 模拟 API 的前端测试”推进到“Windows 开发环境真实浏览器到真实判题终态闭环”。当时 M0 仍保持 `IN_PROGRESS`，缺口是固定 Linux 重放、六种 verdict 的进程级完整链路和最终 20 项门禁审计。这些缺口已由以下两节关闭。

## 5. 2026-09-30 固定 Linux/amd64 重放

### 5.1 不可变输入

- 被验证提交：`39e91145ffa32d6364d849c95edd6c72d6429e42`；
- 后端构建镜像：`maven:3.9.14-eclipse-temurin-21@sha256:98819eb3745bd2007c3f1a19b59085c1fa3929aecb7dbfa431dfcf5a4f18ce3c`，Linux/amd64 manifest 为 `sha256:5c73793a3919815ff0e3921c53f8070d65a35090c0472763fa40ac22c0cfa0f1`；
- 前端构建与进程镜像：`node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c`，Linux/amd64 manifest 为 `sha256:e484ae3f1e3c378021c967fd42254f343c302a9263e412280eac32bf5bca7008`；
- API/Worker 运行时与用户代码镜像：`eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438`；
- 验证用 Docker CLI：`docker:29.8.0-cli@sha256:f5b8bb0333cfaa027640106e5f02e48b0a8e0c00f7165015f581d58783c76fcf`，CLI 文件 SHA-256 为 `e966e0e6ea215b74fdbee17fbe7ed8bff15b65930bfc8e131652ceae4ed0210e`；
- MySQL 使用 `container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be`；
- RabbitMQ 使用 `rabbitmq:4.3.6-management@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95`；
- Docker Engine/CLI 均为 29.8.0，引擎为 Linux/amd64，容器内核心构建环境报告 `Linux 6.18.33.2-microsoft-standard-WSL2 x86_64`。

源码只读挂载并复制到一次性容器文件系统；不使用宿主 Maven/npm 缓存、`target`、`node_modules` 或 `dist`。用于真实进程级重放的 Linux 构建产物为：

- `api.jar`：`3dd5382679ee1fc46c1fb6fb44e2d4b5f7b4c03c389885ed93eae8b71ea7e666`；
- `worker.jar`：`ec7c9e61f06b4e252481abe2db30d90288496d87b6d76ae90dbabd5772cbcf2c`。

### 5.2 全量自动化

后端在固定 Maven Linux 镜像中执行 `mvn --batch-mode --no-transfer-progress clean verify`，API 14 项、Worker 35 项，共 49 项测试全部通过，根 reactor、API 和 Worker 全部 `SUCCESS`。测试期间真实启动固定 digest 的 MySQL、RabbitMQ 和 Java 判题容器。

首次 Linux 尝试暴露了一个跨环境竞态：OLE 超出输出上限时会主动终止 `docker exec`，Linux 将预期的输出流关闭报告为 `Stream closed`，旧实现误当成基础设施故障。提交 `39e91145` 显式区分主动终止与意外 I/O 失败；两个原失败集成测试定向复验为 3/3 通过，随后上述 49 项全量复验通过。

前端在固定 Node Linux 镜像中执行 `npm ci` 与 `npm run verify`：安装 292 个包、审计 293 个包、0 vulnerabilities；类型检查、OxcLint、ESLint、Prettier、1 项 Vitest 和 Vite 8.3.1 生产构建全部通过。第一次前端容器命令误先复制宿主 `node_modules`，已终止并删除遗留容器；改为复制时排除 `node_modules/dist` 后通过，该失败不涉及源码修改。

### 5.3 真实进程级纵向链路

从空 MySQL/RabbitMQ 数据卷启动以下 Linux 进程：

```text
宿主 HTTP 客户端
  -> Linux Node/Vite /api 代理
  -> Linux API（只读 JAR，无 Docker Socket）
  -> MySQL + Outbox + RabbitMQ
  -> Linux Worker（唯一挂载 Docker CLI/Socket）
  -> 受限 Java 21 判题容器
  -> MySQL 终态
  -> 所有者结果查询
```

API 容器的唯一挂载目标为 `/artifacts`，环境中无 `DOCKER_*` 或 `FORGEOJ_WORKER_*`；Worker 才挂载 `/var/run/docker.sock` 和固定 Docker CLI。请求经过真实 Vite 代理完成会话登录、读题、提交和轮询。六个原创 Java fixture 的最终结果为：

| 期望 | 实际 | `processing_status` | Submission/Task `status_version` |
|---|---|---|---|
| AC | AC | FINISHED | 2 / 2 |
| WA | WA | FINISHED | 2 / 2 |
| CE | CE | FINISHED | 2 / 2 |
| RE | RE | FINISHED | 2 / 2 |
| TLE | TLE | FINISHED | 2 / 2 |
| OLE | OLE | FINISHED | 2 / 2 |

另一条 AC 用于首次健康验证，因此最终共有 7 条 Submission/JudgeTask/Outbox。7 条 Outbox 全部 `published_at IS NOT NULL`，JSON 字段数的最小/最大值均为 4，`sourceCode` 与 `testCases` 出现次数均为 0。RabbitMQ 队列的 ready/unacknowledged 均为 0，ForgeOJ 管理的沙箱容器数为 0。API/Worker 日志中对五个源码/隐藏测试/诊断哨兵值的扫描全部为 `False`。

验证结束后，三个应用容器、Compose 容器/网络/两个数据卷、产物卷、Maven 缓存卷和 Docker CLI 工具卷均已删除；复查无剩余验证容器或卷。

## 6. M0 20 项门禁审计

| # | 状态 | 主要证据 |
|---:|---|---|
| 1 | PASS | `MySqlMigrationIntegrationTests` 与空卷 Linux 重放均从空 schema 执行 V1、V2 和 dev seed。 |
| 2 | PASS | `AuthAndProblemIntegrationTests` 覆盖 BCrypt 预置用户成功登录、错误密码拒绝；`SubmissionCreationIntegrationTests` 覆盖未登录提交 401。 |
| 3 | PASS | 题目 DTO 精确白名单和隐藏哨兵断言通过。 |
| 4 | PASS | `forgeoj_api` 直读 `problem_test_case` 在真实 MySQL 被拒绝。 |
| 5 | PASS | 成功提交对 Submission/JudgeTask/Outbox 各只创建一条的断言通过。 |
| 6 | PASS | 撤销 Outbox INSERT 权限时三表计数不变，事务整体回滚。 |
| 7 | PASS | 同 key 顺序重放和 8 路并发重放均只产生一组记录。 |
| 8 | PASS | 不同 key 返回不同 Submission，各自仅有一组记录。 |
| 9 | PASS | Outbox 真实消息与严格 parser 都验证恰好四字段，不含源码或隐藏测试；Linux 实况的 7 条 payload 也均为四字段。 |
| 10 | PASS | `JudgeTaskClaimIntegrationTests` 重复 Rabbit 投递只调用一次执行器且无 unacked 消息。 |
| 11 | PASS | 固定 Linux 真实进程链路通过 HTTP/Outbox/RabbitMQ/Worker/Docker/MySQL 分别得到 AC、WA、CE、RE、TLE、OLE。 |
| 12 | PASS | 沙箱平台故障组合测试持久化 `SYSTEM_ERROR`、verdict 为空，再 ACK。 |
| 13 | PASS | TLE 真实链路结束后管理沙箱数为 0；测试中用例临时目录每次删除，最终容器强制清理。 |
| 14 | PASS | Linux API 容器实际只挂载 `/artifacts`，无 Docker/Worker 控制环境；只有 Worker 挂载 Docker socket 和 CLI。 |
| 15 | PASS | 他人、不存在和非法 Submission ID 使用同一 404 响应。 |
| 16 | PASS | API 响应、MQ payload 的哨兵断言通过；Linux API/Worker 实际日志扫描也无五个已知隐藏哨兵值。 |
| 17 | PASS | 领取/终态事务测试证明 QUEUED 0 -> RUNNING 1 -> 终态 2；Linux 六种 verdict 的双表最终版本均为 2。 |
| 18 | PASS | Vitest 覆盖会话、登录、读题、提交、轮询和停止；Windows 真实浏览器记录补充真实 UI 证据。 |
| 19 | PASS | reactor/POM 和源码依赖扫描证明 API 与 Worker 是并列模块，互不依赖。 |
| 20 | PASS | 本记录保存固定 Linux/amd64 构建、进程级全链路、commit、镜像 digest、产物哈希、结果和清理记录。 |

20 项均为 `PASS`，因此 M0 于 2026-09-30 标记为 `VERIFIED`。下一个可开始里程碑为 M1“可靠异步判题”，当前仍为 `PLANNED`。
