# M1 用户配额与排队取消：局部验证记录

> 日期：2026-09-30（Asia/Shanghai）
> 实现提交：`44642f371489727b5db2a40bcad61e7958eba8dc`
> 分支：`feat/m1-reliable-judging`；起点 `33fa9cb`
> 结论：本轮后端能力已实现并通过 Windows/Testcontainers 回归；M1 整体仍为 `IN_PROGRESS`，不是固定 Linux 门禁或 Release。

## 1. 已确认合约与代码入口

- 用户明确确认动作接口 `POST /api/v1/submissions/{submissionId}/cancel`；不删除 Submission，只有 `QUEUED` 可取消。
- 用户明确选择运行中始终允许另有三个排队任务，不预占重试名额。`QUEUED/RETRYING` 合计最多 3；一个运行槽位。满队列平台失败使用内部 `WAITING_RETRY`，对外仍为 `RUNNING`，清除执行 lease、保留槽位，到期有限重试。
- 创建入口：`SubmissionTransactionService.createNew` 获用户 quota lock 后重查幂等键并检查等待配额；429 不新增 Submission/Task/Outbox。
- 取消入口：`SubmissionCancellationController/Service` 与 `SubmissionMapper`；200 只返回三个白名单字段。登录、CSRF、所有者检查、统一 404、非排队 409；两表更新原子，幂等重复不改变版本或完成时间。
- Worker 入口：`JudgeTaskClaimService` 检查其他 `RUNNING`；`DEFERRED` 仅写到期时间并在事务后 ACK，不创建 attempt；`JudgeTaskRecoveryMapper/Scanner` 到期重投递；`JudgeTaskCompletionService` 在同一用户锁下选择 `RETRYING` 或 `WAITING_RETRY`。
- 迁移：新增 `V4__add_user_judge_quota_lock.sql`，回填既有账号、增加用户/状态索引及内部 Task 状态。V1/V2/V3 未改；dev seed 建立预置账号的 lock。
- 权限：API 仅增加取消所需列 UPDATE，仍不能改源码、verdict、lease、访问 attempt/隐藏测试或删除记录；Worker 仍不能读密码或修改账号，quota 表不授 INSERT。
- 本轮没有添加生产依赖或导入外部代码，原许可证与模块隔离保持不变。

## 2. 环境、命令与结果

Windows PowerShell、Java `21.0.12.1`、Maven Wrapper 固定 Maven `3.9.14`、Docker Desktop Engine `29.8.0`。MySQL、RabbitMQ 和失败注入均为 disposable Testcontainers；没有修改真实开发库或用户数据。

固定测试镜像：

- MySQL `container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be`
- RabbitMQ `rabbitmq:4.3.6-management@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95`

从仓库根目录执行：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-api,forgeoj-judge-worker clean verify
```

2026-09-30 21:52:15 完成：`BUILD SUCCESS`，API **26** 项、Worker **57** 项，Failures/Errors/Skipped 均为 **0**；两个可执行 JAR 重新构建。包含原有真实 Docker 沙箱及消息到执行/写回测试，不只是上下文启动。

随后复验并发与消息时序：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-api,forgeoj-judge-worker '-Dtest=SubmissionCreationIntegrationTests,JudgeTaskClaimIntegrationTests' test
```

21:54:05 完成：`BUILD SUCCESS`，API **19** 项、Worker **16** 项，全通过且零跳过。第二次测试只重跑这两类，不将其余保留报告算作第二次全量回归。

本地原始报告在两模块 `target/surefire-reports/TEST-*.xml` 与同名 `.txt`（构建产物不提交 Git，后续 `clean` 会覆盖）。API 请求全文打印关闭，记录不包含源码、会话令牌或真实凭证。

全量构建产物 SHA-256：

- API：`dce64d71f810e84b5d00099237d5f735aa64289ea48e5460eec227e879ed8ef9`
- Worker：`fce32e723529e60debd0ab90ec57b91c34497de3e61b2f47e7a2f91e4a7d14bf`

测试后只读执行 `docker ps -a --filter label=com.forgeoj.managed=true`，输出为空：无 ForgeOJ 管理沙箱残留。

## 3. 本轮新增行为证据

| 检查 | 观察结果 |
|---|---|
| 同用户 8 个不同请求并发创建 | 恰好 3 个成功、5 个 429 |
| 最后一个名额被 8 个相同幂等键竞争 | 全部返回同一提交，只创建一组记录 |
| 一个可重试 RUNNING + 等待队列 | 仍可拥有 3 个等待任务；第 4 个拒绝 |
| RETRYING 与用户隔离 | RETRYING 计入等待配额；另一用户仍可提交/领取 |
| 同用户不同 Task 并发领取 | 恰好一个 CLAIMED、一个 DEFERRED，只创建一个 attempt |
| 延后任务到期恢复 | 运行槽位释放后，扫描重投递，真实 Rabbit listener 执行 |
| 满队列平台失败 | WAITING_RETRY/RUNNING，等待任务仍为 3，无活动 lease，旧 Worker 再写回失败 |
| 等待重试到期与耗尽 | 扫描器驱动第二个 attempt；最多三个 attempt 后死信并释放运行槽位 |
| 排队取消及 8 路重复取消 | 两表 CANCELLED，只递增一次版本；完成时间幂等，名额可重新使用 |
| 取消过程第二表更新失败 | disposable trigger 注入故障，已更新 Task 也随事务回滚 |
| 取消权限及返回白名单 | 匿名 401、缺 CSRF 403；他人/不存在/非法 ID 同 404；200 精确三字段 |
| 领取竞争及非 QUEUED 取消 | 受限 Worker SQL 事务先转 RUNNING 后取消 409；RUNNING/RETRYING/FINISHED/SYSTEM_ERROR 均不改库 |
| 已取消 Task 的重复 Rabbit 消息 | 实际消费两条，runner 0 次、attempt 0 行，队列 ready/unacked 都归零 |
| V3 旧账号升级 | V4 回填 ACTIVE/DISABLED 账号 lock，账号字段不变，重复 migrate 无新增、校验通过 |
| 数据库权限回归 | API 源码/verdict/lease/DELETE 与隐藏表访问被拒，Worker 密码读取/账号更新被拒 |

红绿测试：接口缺失时 4 个取消聚焦测试中 3 个失败（200/409 实得 404）；旧重试实现被满队列测试捕捉为 RETRYING（应为 WAITING_RETRY）。补实现后两轮相关验证均通过。MySQL unsigned 数值断言改为 `Number.longValue()`；Rabbit 消费测试先确认实际消费再检查队列，消除了初始空队列误判。

## 4. 使用条件与未验证边界

- 启用 M1 Worker 消费时必须同时开启 `forgeoj.worker.recovery.enabled=true`；默认安全开关仍关闭。配额延后默认 5 秒，配置键 `forgeoj.worker.quota-retry-delay-seconds`。Outbox 发布与重试仍需按已有运行方式启用。
- 满队列重试保留运行槽位：退避期间不执行程序，但对外状态为 RUNNING；同用户后续任务暂不能运行。该取舍是用户确认的 D-038，不宣称无等待或公平性性能指标。
- 新账号必须同时建 quota lock，M1 只处理旧账号回填/预置账号；M2 注册闭环未实现。数据库升级应在停止旧 Worker 领取后协调 API/Worker 版本，不将这里的 disposable 升级测试视为无停机升级证明。
- 竞争验证是 API 请求与受限 Worker SQL 转态事务；取消后消息测试使用数据库取消 fixture。没有将它们写成 HTTP 与独立 Worker 两进程同时竞争的 Linux 重放证据。
- 本轮没有修改前端，也未新增取消按钮或运行浏览器 E2E；WebSocket/版本乱序、观测与运维闭环、更宽崩溃/ACK 丢失/恶意代码矩阵、固定 Linux M1 验收仍待完成。
- 不创建 PR、不合并 `main`、不打 tag/release；M1 与 E-01 不提升到 VERIFIED/RESUME_READY。
