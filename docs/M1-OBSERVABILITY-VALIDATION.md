# M1 关联脱敏日志：局部验证记录

> 后续状态：累计实现已提交为 `ab61c9a`，2026-10-02 固定 Linux 复验后端 158 项、前端 8 项通过；见 [Linux 记录](M1-FIXED-LINUX-VALIDATION.md)。下文未提交/未验收表述为日志阶段当时状态。

> 日期：2026-10-01（Asia/Shanghai）
> 分支：`feat/m1-reliable-judging`；HEAD：`ceee82c4904fd8a64be1eda9558ed96482cfdc81`
> 本步与此前通知修改仍在本地工作树，尚未提交或推送。M1 整体仍为 `IN_PROGRESS`，不是固定 Linux 验收、持久审计、PR 合并或 Release。

## 1. 实现与边界

使用既有 Spring Boot 4.1.1 内置 Logstash JSON、SLF4J 字段和 Logback；不新增依赖、共享模块、migration、消息字段、公开响应字段或日志基础设施。[Boot 官方日志说明](https://docs.spring.io/spring-boot/4.1/reference/features/logging.html)提供内置结构化格式及 MDC/字段支持；本轮实际输出也由集成测试解析验证，未把框架编码器表述为自行实现。

API `RequestCorrelationFilter` 在安全过滤链外层生成内部 UUID，忽略客户端 `X-Request-Id`。只输出固定 route 分类、白名单 method、状态码和耗时；不输出原始 URI/query、请求体、账号、认证头、Cookie、CSRF 或异常对象，不返回新增追踪头。成功与异常都恢复旧 MDC。WebSocket 只记录握手 HTTP 请求，不记录帧或把握手 ID 当成长连接 tracing。

API `CommittedJudgingEvents`、Worker `JudgingEvents` 输出白名单业务身份与平台状态。创建/取消、领取/完成/重试/死信、配额延期和租约恢复使用事务提交回调，既不提前宣称成功，也不改变已有业务、ACK 和租约语义。Worker 使用显式 ID，不把整个 `ClaimedJudgeTask`、leaseToken、配置 workerId、源码、结果诊断、测试输入/输出、数据哈希或异常原因转成日志。

| 阶段 | 可关联字段 | 事实含义 |
|---|---|---|
| HTTP 完成 | requestId、固定 route、httpStatus、durationMillis | 本次过滤链返回或抛出；不代表异步判题完成 |
| submission.created / cancelled | requestId（有 HTTP 上下文时）、submissionId、judgeTaskId、statusVersion；创建另含 outboxEventId | 事务已提交；重复请求或重复取消不伪造第二次状态转移 |
| outbox.published / publish_failed | submissionId、judgeTaskId、outboxEventId、sequenceNo、publishAttemptNo、固定 failureCode | confirm/可路由与发布标记更新成功，或失败计数更新成功；CAS 未赢不宣称已持久化 |
| delivery.received / claim_result | 已验证 submissionId、judgeTaskId；CLAIMED 时另含 attemptId/attemptNo | 收到合约有效消息及领取返回结果，不输出 AMQP headers/body/messageId |
| attempt.claimed / finished / retry_scheduled / dead_lettered | submissionId、judgeTaskId、attemptId、attemptNo、平台 outcome / failureCode | 对应领取或完成/失败事务已提交；finished outcome 仅平台定义 verdict |
| task.quota_deferred / exhausted / lease_recovered | submissionId、judgeTaskId；新租约恢复另含新 attemptId/attemptNo | 配额延期、耗尽或恢复状态已提交，不输出 leaseToken |
| recovery.sent / send_failed | submissionId、judgeTaskId、固定 failureCode | 发送调用返回或失败；扫描器未使用 confirm，不能声称 broker 已确认 |
| attempt.heartbeat_failed | submissionId、judgeTaskId、attemptId、attemptNo、LEASE_RENEWAL_FAILURE | 心跳线程明确归属于该 attempt，不继承请求 MDC或输出原始异常 |
| delivery.ack_sent / ack_failed | submissionId、judgeTaskId；有效拥有者另含 attemptId/attemptNo；失败为 ACK_FAILURE | ACK 客户端调用返回或失败，不证明 RabbitMQ 已收到 ACK |

关联路径为 `requestId → submissionId/judgeTaskId → attemptId`，不是强行把 requestId 传播到 MQ。消息仍精确四字段；不同 HTTP 请求有不同 requestId，异步阶段用同一业务 ID 连接。Outbox 仅增加已有 Task 的 LEFT JOIN ID 投影，不解析 payload 作日志，也不改变选取过滤、排序、发布路由或数据库权限。重试 Outbox 的 sequenceNo 是产生事件的失败 attempt 序号，不是下一次领取 attempt 的 ID。

## 2. 复现命令与最新结果

环境：Windows PowerShell、Java 21.0.12.1、固定 Maven Wrapper/Maven 3.9.14、Node 24.14.1/npm 11.11.0、Docker Desktop Engine 29.8.0（Linux x86_64）。沿用既有固定 digest 的 MySQL 8.4.12、RabbitMQ 4.3.6 和 Temurin 21.0.12_8 镜像。所有数据库故障注入仅针对 disposable Testcontainers，无真实业务数据库修改。

后端根目录：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

2026-10-01 **18:33:00** 完成 `BUILD SUCCESS`：API **46**、Worker **67**，合计 **113**，Failures/Errors/Skipped 均为 **0**。逐项求和核对本次 clean 生成的 API 11 个、Worker 17 个 XML 报告；两个可执行 JAR 重新构建。相比通知阶段的 38 + 57 新增 18 项测试，原通知/配额/取消/判题测试仍全部执行，没有删除、跳过或削弱原门禁。

原始报告：两模块 `target/surefire-reports/TEST-*.xml`，不提交构建产物，后续 clean 会覆盖。本次产物 SHA-256：

- API：`5480c09c48039d8d7b901f1ed79796895aea90b5e361d62f59e2fe64b774659a`
- Worker：`792ce0d1643dc80bafa947b5c7ed3cb311692eaea715eb57eeabe465f76f2b75`

前端目录：

```powershell
npm run verify
```

同轮类型检查、OxcLint、ESLint、Prettier、Vitest、Vite 构建全部通过；2 个测试文件、**8** 项测试，无跳过。前端代码没有本轮新增日志改动，此次回归继续覆盖已有通知和轮询逻辑，不是实际浏览器/独立 Worker E2E。

前端 SHA-256：`dist/index.html` 为 `6ac38844af2dc8ae6a6cc4da28dfd6dd4d0c3ce67e2271b79a13dc87ad082cb1`；`dist/assets/index-D-QIMhg5.js` 为 `dc4d7bfb7a3fe996883536428bfbbf8aca042380a94367fda3b6897ffa1763df`。

构建后只读 `docker ps -a --filter label=com.forgeoj.managed=true` 为空。`git diff --check` 通过（现有 autocrlf 提示不是差异错误）；没有 prune、reset、强制回退或删除其他项目资源。

## 3. 行为与故障证据

| 验证 | 观察 / 测试入口 |
|---|---|
| 不信任客户端 ID 与脱敏 | `RequestCorrelationFilterTests`：伪造 ID、认证头、Cookie、query、path、body 哨兵不出现在字段中；内部 UUID 不写响应头 |
| 请求线程复用与异常 | 同一线程两请求 ID 不同；恢复原上下文；抛出含哨兵 IOException 后恢复 MDC，只记 500/固定分类，不记录异常 |
| 提交与请求关联 | `SubmissionCreationIntegrationTests` 解析实际控制台 JSON，两个新提交各有请求/提交/任务 ID；幂等重放不增加 created 事件 |
| 事务提交后输出 | 两模块 helper 测试提交前无日志，提交后有日志；无实际事务不宣称持久化 |
| 实际外层回滚 | API 创建完成后外层 TransactionTemplate 主动回滚：无创建记录且无 created 日志；Worker 完成后外层回滚：三表仍 RUNNING，无 finished 日志 |
| 数据库故障回滚 | 禁止 Outbox INSERT、注入取消 UPDATE 失败、禁止 Worker Submission UPDATE：原有双表/三表回滚断言仍成立，新增成功日志不存在 |
| NACK 原文脱敏 | `OutboxPublisherLoggingTests` 模拟含源码/凭据/隐藏数据哨兵的 broker reason，只记录 BROKER_NACK，无 raw reason / payload / Throwable |
| confirm 与路由 | 实际 RabbitMQ 的正式/重试/死信发送，各条成功日志有相同业务 ID；unroutable 固定错误码与退避/失败计数一致 |
| Worker 线程复用 | 同一监听线程依次领取两个任务，每次日志只有当前 submission/task/attempt，AMQP header/messageId、leaseToken、workerId 哨兵不输出，无新增 MDC 残留 |
| claim / execution / ACK 故障 | 含敏感哨兵的异常只映射 CLAIM_FAILURE/EXECUTION_FAILURE/ACK_FAILURE；保留 claim requeue 与 execution/runtime ACK reject；IOException ACK 故障仍向上抛，不输出 ack_sent |
| 六 verdict 的终态日志 | 实际 MySQL 完成事务分别记录 AC/WA/CE/RE/TLE/OLE 与正确三类业务 ID；CE 诊断、租约和测试账号密码不进入日志 |
| 消费 → 沙箱 → 终态 → ACK | 实际 RabbitMQ/MySQL/Docker OLE：claim/finish/ACK 对齐同一任务与 attempt，finished JSON 先于 ack_sent；重复投递只产生 DUPLICATE，不增加执行结果 |

心跳、主动恢复、满队列重试和死信新增事件也由同一字段白名单输出；已有状态机测试全量继续通过。它们没有被当作独立 Worker 进程崩溃、broker 实际 ACK 丢失或所有恶意输入场景的演练。

## 4. 红绿过程与剩余限制

- 14:58 的新 NACK 测试先在旧实现失败：broker 原始原因中的哨兵进入日志；删除原文、改为持久化后的固定错误码后通过。
- 第一轮实际 JSON 回归在 18:03 发现 MDC 与显式字段重复写 requestId，Boot 格式器报同名字段并拒绝输出；API 1 项失败，Worker 未运行，不计作通过。修正为只通过 MDC 输出请求身份并在提交回调恢复上下文，保留真实 JSON 解析断言。18:13:24 聚焦回归 API 28 + Worker 23 全过；之后新增 runtime ACK 兼容测试并完成上述全量回归。
- Maven 命令首次被 PowerShell 拆分 dotted `-D` 参数，正确加引号后重跑；这是启动失败，不是删测或跳测。Docker Engine 不在运行时用 `docker desktop start --detach` 启动并确认环境，没有删除/重置 Docker 数据。一次文件写入权限检查超时后先核对落盘状态，重试成功，不混入测试结果。
- L-030：afterCommit 只防止回滚误报，不保证“已提交一定有日志”；提交后至输出前崩溃、日志 sink 故障仍可能缺日志。MySQL 是事实源，日志不参与可靠投递/幂等判断。
- 本轮只证明自有日志字段与所测默认 INFO 路径，不保证任意第三方 DEBUG、SQL 参数打印、访问日志或异常转储都已经全局脱敏。不要启用请求体/协议/SQL 参数 DEBUG，不把日志直接提供普通用户；集中采集、保留期、告警与 OPS_ADMIN 运维闭环尚未实施。
- 未完成 M1 固定 Linux 构建与通知/独立进程重放、完整崩溃/ACK 丢失和更宽恶意代码矩阵。没有性能/容量结论，不升级 M1/E-01 为 `VERIFIED/RESUME_READY`，不创建完成 PR、不合并 main、不打 tag/release。

下一步：先补 Worker 进程中断、租约恢复与 ACK 丢失/重复消息故障矩阵，再补恶意代码安全矩阵，最后在固定 Linux 完整重放并逐项审计 M1 门禁。
