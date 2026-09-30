# M1 可靠异步判题设计

> 状态：`IMPLEMENTATION_BASELINE`  
> 定稿日期：2026-09-30  
> 适用里程碑：M1  
> 注意：本文约束 M1 的实现顺序和验收边界，不代表相应能力已经通过验收。

## 1. 目标

M1 把 M0 的“正常情况下能够完成判题”提升为“Worker 中断、消息重复、ACK 丢失和平台临时故障下仍能恢复、追踪且不会覆盖正确结果”。

必须保持以下边界：

- MySQL 是任务、attempt、最终结果和重试进度的事实来源；
- RabbitMQ 只运输四字段任务消息，不保存源码、隐藏测试或权威状态；
- API 不获得 Docker 权限；
- 用户代码结果与平台故障继续分离；
- M1 不提前实现 M2 的完整账号、题库、提交历史或草稿功能；
- 普通 Docker 仍只是受控小范围边界，不宣称绝对安全。

## 2. 实施顺序

1. 新增 attempt、租约、重试和 Outbox 发布状态的数据结构；
2. 实现带租约令牌的原子领取、续租和过期恢复；
3. 使用租约令牌保护终态写回，阻止旧 Worker 覆盖新 attempt；
4. 对平台临时故障实施有限退避重试，达到上限后进入死信；
5. 让重试事件与发布失败继续通过可追踪 Outbox 恢复；
6. 建立正式提交、自测、重试和死信队列边界；
7. 实施用户运行中/排队配额、QUEUED 取消、WebSocket 通知和轮询兜底；
8. 完成崩溃、ACK 丢失、乱序和恶意代码矩阵，最后在固定 Linux 环境验收。

每一步先完成聚焦测试，再运行模块测试；M1 只有全部门禁通过后才能从 `IN_PROGRESS` 升级为 `VERIFIED`。

## 3. 数据模型

### 3.1 `judge_task`

在 M0 字段上增加：

- `attempt_count`：已经创建的 attempt 数量；
- `max_attempts`：包含首次执行在内的最大 attempt 数，M1 默认 3；
- `next_attempt_at`：`RETRYING/WAITING_RETRY` 的重试到期时间，或 `QUEUED` 配额延后任务的恢复投递时间；
- `lease_owner`：Worker 实例标识；
- `lease_token`：本次执行的随机 UUID，也是终态写回栅栏；
- `lease_expires_at`：租约截止时间；
- `last_failure_code`、`last_failure_message`：平台失败的受限摘要。

任务状态：

```text
QUEUED -> RUNNING -> FINISHED
                  -> RETRYING -> RUNNING
                  -> WAITING_RETRY -> RUNNING  (排队已满，保留原运行槽位)
                  -> DEAD_LETTER
QUEUED -> CANCELLED
```

`SYSTEM_ERROR` 只用于无需或无法继续重试的平台终态；达到重试上限的任务使用内部 `DEAD_LETTER`，对应 Submission 对普通用户呈现 `SYSTEM_ERROR`，不得伪装成用户 `RE`。

### 3.2 `judge_task_attempt`

同一个 JudgeTask 的每次实际执行都插入一行，不创建新 Submission：

- `id`：attempt UUID；
- `judge_task_id`、`attempt_no`：任务内单调递增且组合唯一；
- `lease_token`：全局唯一的写回栅栏；
- `worker_id`：领取该 attempt 的 Worker；
- `attempt_status`：`RUNNING`、`SUCCEEDED`、`RETRYABLE_FAILURE`、`LEASE_EXPIRED`、`DEAD_LETTERED`；
- `started_at`、`heartbeat_at`、`lease_expires_at`、`finished_at`；
- 受限的 `failure_code`、`failure_message`。

普通用户 API 不读取该表；以后 OPS_ADMIN 只能通过受限运维接口查看脱敏字段。

### 3.3 `outbox_event`

M0 的唯一键只能容纳一次排队事件。M1 增加 `sequence_no`，唯一键变为 `(event_type, aggregate_id, sequence_no)`：

- 首次排队使用序号 0；
- 第一次重试使用序号 1，依此类推；
- payload 仍然只有 `taskId`、`submissionId`、`taskType`、`contractVersion` 四个字段。

同时记录 `publish_attempts`、`next_attempt_at`、`last_attempt_at`、`last_error_code` 和 `failed_at`，使发布失败可以退避、审计并在超过上限后停止热循环。

## 4. 领取、租约与栅栏

Worker 在短事务中锁定 JudgeTask 与 Submission，只允许以下任务被领取：

- `QUEUED`；
- 已到 `next_attempt_at` 的 `RETRYING`；
- 已到 `next_attempt_at` 的内部 `WAITING_RETRY`（对应 Submission 为 `RUNNING`）；
- 租约已经过期的 `RUNNING`。

领取事务必须同时：

1. 将上一个过期 attempt 标记为 `LEASE_EXPIRED`；
2. 检查 `attempt_count < max_attempts`；
3. 创建新 attempt；
4. 写入新的 `lease_owner`、`lease_token`、`lease_expires_at`；
5. 将 Task 与 Submission 设置为 `RUNNING` 并递增版本。

只有同时匹配 `taskId + submissionId + leaseToken + RUNNING` 的 Worker 才能续租、写重试或写终态。租约过期后恢复的旧 Worker 必须得到“所有权已丢失”，不能覆盖更新后的结果。

## 5. 心跳与恢复

- 默认租约时长由配置提供，不能短于单次心跳间隔的三倍；
- Worker 在执行期间周期性续租，同时更新 task 与 attempt 的截止时间；
- 心跳失败时停止继续信任执行所有权，并尽力终止沙箱；
- 恢复扫描器重新发布 MySQL 中租约过期、重试到期或配额延后到期的任务；启用 M1 消费时必须同时启用 `forgeoj.worker.recovery.enabled=true`，否则已 ACK 的配额延后任务没有自动恢复来源；
- 重复发布安全，因为原子领取和租约栅栏决定唯一执行权；
- Worker 在终态数据库事务提交之后才 ACK。

## 6. 失败分类与有限重试

不重试的用户结果：`AC`、`WA`、`CE`、`RE`、`TLE`、`MLE`、`OLE`、`SECURITY_VIOLATION`。

可重试的平台失败示例：

- Docker daemon 临时不可达；
- 创建或控制沙箱的临时 I/O 失败；
- 读取判题快照时的临时数据库连接失败；
- 终态事务在有限时间内无法提交。

不可重试的平台失败示例：

- 消息契约或 task/submission 对应关系不一致；
- 提交快照哈希、隐藏测试哈希或镜像配置被篡改；
- 可信配置本身非法。

默认最大 attempt 为 3。退避时间按 attempt 序号确定并带有上限；测试使用可注入时钟和确定性退避，不依赖真实长时间等待。

平台失败的状态变化必须与重试 Outbox 事件在同一 MySQL 事务中提交。达到上限后：

- 当前 attempt 记为 `DEAD_LETTERED`；
- JudgeTask 记为 `DEAD_LETTER`；
- Submission 记为 `SYSTEM_ERROR`、verdict 为空；
- 发送不含敏感信息的死信消息，供后续 OPS_ADMIN 闭环使用。

## 7. 队列边界

M1 最终使用独立队列：

- 正式提交队列；
- 自测队列；
- 延迟/重试队列；
- 死信队列。

正式提交和自测不得互相阻塞到无法服务。M1 先建立队列和调度边界；完整自测记录页面仍属于后续学习主流程，不借此提前铺设 M2 UI。

## 8. 用户配额与取消

- 每位用户最多 1 个 `RUNNING` 任务和 3 个 `QUEUED/RETRYING` 任务；
- 配额检查必须在数据库事务内完成，不能只在前端或 Redis 中判断；
- `QUEUED` 可取消，Task 与 Submission 同事务进入 `CANCELLED`；
- `RUNNING` 在 V1.0 不支持用户强制取消；
- 重复取消必须幂等。

### 8.1 并发配额与满队列重试

API 新建与 Worker 领取/重试检查均在事务中锁定 V4 的 `user_judge_quota_lock`，不锁或开放更新账号凭证表。V4 回填既有账号；dev seed 为预置账号补行，M2 新建账号必须在同一事务插入 quota lock，缺失时 fail closed。API 获锁后再次查幂等键，再检查 `QUEUED/RETRYING` 合计是否达到 3；达到时拒绝新请求（429），已有请求重放仍成功。

Worker 先锁 Task/Submission，再锁用户 quota row；发现该用户另有 `RUNNING` Submission 时只写 `next_attempt_at`（默认延后 5 秒），保持当前状态/版本、不创建 attempt，事务提交后 ACK。扫描器到期重新投递，避免热 requeue；不同用户互不消耗配额。

用户确认不为未来重试预留排队名额：1 个 `RUNNING` 可同时拥有 3 个 `QUEUED/RETRYING`。平台失败时若等待队列已满，Task 同事务进入内部 `WAITING_RETRY`，Submission 保持 `RUNNING`，attempt 记为 `RETRYABLE_FAILURE`，清除旧 lease 并写带到期时间的四字段 Outbox。它保留原运行槽位，不占第四个排队名额；退避期间不执行程序，后续同用户任务不能抢占。到期领取新 lease/attempt；旧 Worker 不能续租或写回。若队列未满仍转入 `RETRYING`，最后一次失败仍进入死信/SYSTEM_ERROR。

### 8.2 取消 HTTP 合约

`POST /api/v1/submissions/{submissionId}/cancel` 无请求体，须登录并带 CSRF；200 仅返回 `submissionId/processingStatus/statusVersion` 三字段。Task 与 Submission 都为 `QUEUED` 时同事务条件更新为 `CANCELLED`，写完成时间，各递增一次版本并释放等待名额。都已取消时返回原结果，版本和完成时间不再改变。`RUNNING/RETRYING/FINISHED/SYSTEM_ERROR` 或状态不一致返回 409，不修改结果；他人/不存在/非法 UUID 统一 404，不泄露归属。

取消锁定从 Task 主键开始的联表记录，与 Worker 领取顺序一致；领取先提交则取消 409，取消先提交则 Worker 吸收消息并 ACK，不创建 attempt 或启动沙箱。Outbox 不删除，即使取消前已经发出或取消后才发出旧排队事件，均按 MySQL 终态处理。API 只获得取消所需列的 UPDATE，不获得源码、verdict、lease、attempt 或删除权限。

## 9. 通知与可观测性

WebSocket 只发送：

```json
{
  "submissionId": "UUID",
  "processingStatus": "RUNNING",
  "statusVersion": 1
}
```

前端只接受更大的 `statusVersion`，断线或乱序时继续以所有者查询接口轮询 MySQL 事实。结构化日志至少关联 `requestId`、`submissionId`、`judgeTaskId`、`attemptId`，但不得记录源码、隐藏测试、凭据或完整平台异常堆栈给普通用户。

## 10. M1 门禁

1. 并发重复消息只能产生一个有效 attempt；
2. Worker 在领取后崩溃，租约过期后可由另一 Worker 恢复；
3. 旧租约不能续期、写重试或覆盖新 Worker 的终态；
4. ACK 丢失后的重复消息不会再次执行已完成任务；
5. 平台临时故障按有限次数重试，用户 verdict 不重试；
6. 达到上限后 Task 进入死信，Submission 为 `SYSTEM_ERROR` 且 verdict 为空；
7. Outbox 发布失败有退避、计数和可恢复证据；
8. 消息重复、乱序和恢复扫描不会产生重复业务结果；
9. 用户运行中/排队配额在并发请求下仍成立，QUEUED 取消幂等；
10. WebSocket 乱序不会覆盖更高版本，轮询能够恢复最终状态；
11. 无限循环、内存耗尽、进程爆炸、超量输出、联网、写根目录和提权尝试受到限制；
12. 用户程序读取不到数据库/MQ 凭据、其他提交源码和整套隐藏测试；
13. 每个任务能通过 ID 串联 API、Outbox、MQ、attempt、Worker 和结果日志；
14. API 仍无 Docker 权限，所有终止路径无管理容器残留；
15. 固定 Linux 环境完成构建、故障恢复和真实进程链路重放。
