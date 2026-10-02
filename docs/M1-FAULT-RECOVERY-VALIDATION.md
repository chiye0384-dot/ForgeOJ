# M1 真实 Worker 进程故障与沙箱恢复验证

> 后续状态：累计实现提交 `ab61c9a` 的五项真实子 Worker 测试已在固定 Linux 中通过；见 [Linux 记录](M1-FIXED-LINUX-VALIDATION.md)。下文保存初次 Windows 验证和当时剩余工作，不把局部复验写成 M1 整体完成。

> 日期：2026-10-01。范围：本地 Windows 子 JVM + Linux Docker Desktop + 隔离 MySQL/RabbitMQ。M1 仍为 `IN_PROGRESS`，不是固定 Linux M1 验收或发布。代码基于 `ceee82c` 工作树；通知、日志及本阶段改动未提交/推送。

## 1. 验证方法与边界

`WorkerProcessFaultIntegrationTests` 不在同一 Spring 测试进程模拟 Worker 崩溃：它启动真实独立 JDK 21 子 JVM，加载生产 Spring Boot 配置、受限 Worker 数据库账号、manual ACK 消费器、心跳、恢复扫描与 Docker CLI 沙箱。仅 test-classes 中的 `FaultWorkerProcess` 插入暂停屏障，父测试使用该 Process 对象强制终止特定子进程；测试控制代码不会进入生产 JAR。

只使用 Testcontainers 创建的临时 MySQL 8.4.12/RabbitMQ 4.3.6、固定 Temurin Java 21 digest、原创 sum 程序与公开的 dummy test credentials。父进程仅播种/查询 fixture，不修改真实业务数据；这四项故障测试没有 SQL 强制过期或手动重发原始未 ACK 消息。测试租约 9 秒、心跳 1 秒、恢复扫描 200ms、沙箱扫描 500ms；生产默认参数不变，仅新增清理默认 5 秒。

未 ACK 消息在连接/通道关闭时会自动重新排队，因此测试先观测 unacked，再强制杀死 Worker、观测 ready，随后启动或恢复另一个消费者。[RabbitMQ 官方确认文档](https://www.rabbitmq.com/docs/confirms)说明该机制与消费者幂等要求。队列只用 HTTP GET 读取 `messages/messages_ready/messages_unacknowledged`，不用会取走消息的管理 API；统计含采样延迟，所以重复消息测试同时等到消费者处理日志再断言队列为空。[RabbitMQ HTTP API](https://www.rabbitmq.com/docs/http-api-reference)提供对应只读队列端点和字段定义。

## 2. 四项真实进程故障证据

| 场景 | 中断位置与接手方式 | 数据库与资源断言 |
|---|---|---|
| 领取后崩溃 | 已提交 attempt 后、Runner 执行前杀 A；B 接手真实过期租约 | attempt 1 `LEASE_EXPIRED`、2 `SUCCEEDED`；同一 Submission `FINISHED/AC`，attempt_count=2，队列排空 |
| 终态提交、ACK 尚未发送 | A 已完成沙箱清理和 MySQL 终态，屏障阻止返回到 basicAck；杀 A 后 broker 自动重投，B 吸收 | 终态字段/版本/完成时间完全不变；仅一行 `SUCCEEDED`；B 无 attempt.claimed/finished；再重复/交错正式与重试消息六次，仍不执行 |
| 已启动 B 回收 A 的孤儿沙箱 | B 启动完毕但暂不消费；A 创建并启动沙箱后被杀；B 开始消费与扫描 | 真实过期后 attempt 2 成功；旧容器最终回收，管理沙箱为空；不能依赖 B 的启动清理 |
| B 启动时 A 仍活跃 | A 持有沙箱并由生产心跳续租；启动 B，随后杀 A 并开始 B 消费 | B 的启动/周期清理保留 A 的原容器及 RUNNING/attempt_count=1；A 崩溃后正确恢复 AC，最终无残留 |

所有子 Worker 日志均断言不含源码哨兵和测试 DB/MQ 密码。新命令行只传配置开关与时间参数；密码通过 test-only 子进程环境传入，不写进命令行或仓库真实凭据。

ACK 场景证明“终态已提交但 broker 未获得 ACK 的重投递安全”，不是 ACK 已经写入 socket 后的任意网络丢包注入。测试 broker/daemon 没有被杀，不代表 MQ 数据盘灾难恢复或 Docker daemon 重启矩阵。

## 3. 先失败，再修复

- 20:40:30 首次三项测试失败：孤儿沙箱测试确实到达 `DEAD_LETTER/SYSTEM_ERROR`。B 已在 A 创建沙箱前启动；A 被杀后留下 task-only 名称，attempt 2/3 创建同名容器均失败，不能用 SQL 租约测试证明 Docker 恢复正常。
- 同次 teardown 的 Go-template 字符串在 Windows ProcessBuilder 中引用失败，留下一个本测试 task 的容器，使后两项测试被无残留前置检查拒绝。这不是三个独立业务缺陷。修为无嵌套引号的 JSON labels 投影；CLI 等待/输出有界，清理前验证完整 ID 和 managed/task 标签。
- 对首次遗留容器核验完整 ID、精确名称与本轮 task 标签后只删除该容器，不 prune、不清理他人资源。后续每个测试只清理自己播种的 task ID。
- 20:49:24 修正测试控制后，领取崩溃与终态无 ACK 两项通过。
- 20:54:04 沙箱所有权与 Runner 聚焦单测 15 项通过。
- 20:57:44 四项进程测试 + 当时 11 项 sandbox 单测 + 4 项 Runner + 2 项清理日志测试共 21 项通过，零失败/错误/跳过；之后另补 inspect 身份错配、并发删除与真实数据库闭合查询检查，由下述全量构建验证。

## 4. 最小实现修复

- `M0JudgeTaskRunner` 从已领取记录将 attemptId 传入内部 SandboxRuntime，不改四字段 MQ、用户接口或快照。
- `DockerCliSandboxRuntime/SandboxContainer` 为每 attempt 设置独立名称/标签，并保存、使用完整不可变 Docker ID；删除前核对名称与标签。旧 attempt 的 finally 不会按任务名删除新 attempt。
- `SandboxAttemptLookup` 只使用 Worker 既有 SELECT 权限读取精确 task/attempt：仅已提交关闭状态和 finished_at 可授权后台回收。RUNNING 即使租约时间已过期也不授权删除；缺失/外库/legacy/非法归属保留。
- `SandboxRecoveryCleaner` 在 sandbox/recovery 同开时周期回收；启动扫描也使用相同保守核验。DB/daemon 故障不扩大删除范围；异常只记录固定 `SANDBOX_CLEANUP_FAILURE`，不输出异常对象/信息。
- Docker 限制、API 无 Docker 权限、MySQL 事实源、配额与有限重试均保持；没有新依赖、外部源码、数据迁移或新增授权。

## 5. 复现命令和最新结果

仓库根：`D:\Java项目\ForgeOJ`。前置：JDK 21、可用 Linux Docker Engine、固定镜像可获取；运行前不要同时在同一 daemon 上运行其他 ForgeOJ 沙箱。测试前置检查会拒绝残留，不自动删除无关资源。

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-judge-worker '-Dtest=WorkerProcessFaultIntegrationTests,DockerCliSandboxRuntimeTests,M0JudgeTaskRunnerTests,SandboxRecoveryCleanerTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

2026-10-01 21:04:37 根 `clean verify` 为 **BUILD SUCCESS**，耗时 6:04；fresh XML 独立求和如下，零失败/错误/跳过，没有复用历史 target 报告：

| 模块 | XML 文件 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| API | 11 | 46 | 0 | 0 | 0 |
| Worker | 19 | 83 | 0 | 0 | 0 |
| 合计 | 30 | 129 | 0 | 0 | 0 |

本阶段新增 16 项：真实进程故障 4、沙箱所有权单测从 4 增至 13（新增 9）、周期清理与脱敏 2、受限数据库归属查询 1。既有 Claim 17 项、Completion 13 项、真实六 verdict Docker 执行及日志/通知回归均通过。

前端 `D:\Java项目\ForgeOJ\frontend` 的 `npm run verify` 同轮通过：vue-tsc、OxcLint、ESLint、Prettier、2 文件/8 项 Vitest 和 Vite 生产构建。前端没有在本阶段新增业务改动；这是既有通知工作树的回归，不是本轮浏览器 E2E。

末尾只读检查：所有 `com.forgeoj.managed=true` 容器为 0，`FaultWorkerProcess` 子 JVM 为 0；Worker 生产 JAR 不含两个 test-only 故障类。临时 fixture 已由 Testcontainers 回收，真实业务数据未修改。实际 JDK 21.0.12.1 / Maven 3.9.14 / Docker Desktop Linux Engine 29.8.0。

本轮产物 SHA-256（只对应当前本地构建，不是 release hash；JAR 重建可因时间元数据而变化）：

| 产物 | SHA-256 |
|---|---|
| `forgeoj-api/target/forgeoj-api-0.0.1-SNAPSHOT.jar` | `d75f32269ff497ef2c12d3be292e3e032c5cadb4a37978fe80d223bacc4e61ef` |
| `forgeoj-judge-worker/target/forgeoj-judge-worker-0.0.1-SNAPSHOT.jar` | `84f9fbeb50e916e9c489ed3d06d8a408ac4cc2cbb80f57ffc37ffa46aff5e3f6` |
| `frontend/dist/index.html` | `6ac38844af2dc8ae6a6cc4da28dfd6dd4d0c3ce67e2271b79a13dc87ad082cb1` |
| `frontend/dist/assets/index-D-QIMhg5.js` | `dc4d7bfb7a3fe996883536428bfbbf8aca042380a94367fda3b6897ffa1763df` |

原始报告路径为两个模块 `target/surefire-reports/TEST-*.xml`，故障类输出包含四个 `FAULT_VERIFIED=` 标记。target 为本地可重建产物，不承诺长期保存原始日志。

## 6. 未关闭的门禁

本阶段增加真实 Worker 进程故障局部证据，不能将 M1 或 E-01/E-02 升级为整体 VERIFIED/RESUME_READY。旧 owner 栅栏、有限重试/死信、回滚等仍由现有数据库集成测试回归，不冒充本轮真实暂停/恢复旧进程演练。

仍缺更宽恶意代码安全矩阵、固定 Linux JDK 构建及通知/进程故障重放、OPS_ADMIN 运维与最终 M1 门禁审计。孤儿资源保守且最终清理，DB/daemon 不可用或未知/legacy 归属需运维核实，见 L-031；不声称崩溃瞬间无资源残留，也没有吞吐、恢复延迟 SLA 或 exactly-once 传输保证。
