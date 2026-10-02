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

### 5.1 attempt 级沙箱所有权与崩溃清理

真实进程故障测试发现 M0 的 task-only 容器名会让遗留容器阻塞恢复，按 managed 标签全量启动清理还会删除其他活跃 Worker 的沙箱。M1 改为内部 `forgeoj-{taskUUID去横线}-{attemptUUID去横线}` 名称，附带 managed/task-id/attempt-id 标签；attemptId 从 MySQL 已领取记录传入，不增加 MQ 四字段、HTTP 字段或数据库迁移。

创建后保存 Docker 返回的完整 64 位容器 ID；启动、exec、检查和最终删除都使用这个不可变 ID，不按可复用名称控制。删除前验证 ID、名称与三项标签完全对应，防止旧执行的 finally 删除新 attempt。并发清理中已被删除的同一个 ID 视为幂等成功；不能按名称寻找替代容器。

启动及周期清理仅删除本数据库精确匹配的已关闭 attempt（`SUCCEEDED/RETRYABLE_FAILURE/LEASE_EXPIRED/DEAD_LETTERED` 且 finished_at 非空）。状态关闭是单调事实：不能仅因租约时间过期删除仍为 RUNNING 的容器，必须先由领取/耗尽事务关闭并栅栏旧 attempt。其他数据库、缺失记录、legacy 无 attempt 标签、非法标签或名称均保留；Docker/数据库异常不授权删除。默认清理间隔 `forgeoj.worker.sandbox.cleanup-delay-ms=5000`，周期清理与 sandbox/recovery 开关同时开启；数据库/daemon 恢复后再次扫描，失败只输出固定脱敏码。

恢复先依靠独立 attempt 名称继续判题，关闭的旧沙箱由扫描器最终回收，不保证原进程被杀死瞬间即删除。未知或旧格式资源需要运维按完整 ID、所属任务和活跃进程核对后处理，不使用全局 prune，见 L-031。API 不获得 Docker 权限，原非 root、禁网、只读、资源与输出限制不变。Windows 子 JVM 故障证据见 `M1-FAULT-RECOVERY-VALIDATION.md`；固定 Linux 子 JVM 复验与独立真实链路分别见 `M1-FIXED-LINUX-VALIDATION.md`、`M1-E2E-VALIDATION.md`，OPS_ADMIN 与最终门禁审计仍待完成。

### 5.2 逐用例资源隔离加固

2026-10-01 的真实恶意程序测试复现了临时文件权限、残留子进程与额外共享内存写入问题。容器启用 Docker `--init` 回收孤儿/僵尸进程，使用 `--ipc none` 不挂载 `/dev/shm`；不新增 capabilities 或宿主挂载。用户程序仍为 UID 65532，构建与 init 为 UID 65534，根只读、禁网和原资源限制保持。

每例结束后用 UID 65532 对同 UID 执行 SIGKILL，再由不同的受控 UID 65534 执行 pgrep 核对；最多五轮，残留或检查失败均中止判题并走外层精确容器清理，不能继续下一例。仅在用户进程已清空后，以所有者身份恢复 `/tmp` 下 UID 65532 的真实目录权限，并移除其所有顶层文件/目录/链接；不只清理 `java.io.tmpdir`。固定 find 参数不跟随符号链接，递归 chmod 不修改遇到的嵌套链接目标，rm 使用 `--`。这避免 root 在 cap-drop ALL 下无法删除 mode 700 文件，也避免用新增权限掩盖所有权错误。

测试覆盖跨用例文件/进程、嵌套恶意权限/符号链接、堆和 cgroup OOM、PID、tmpfs、非 root/提权、禁网、只读、跨提交源码、期望输出/未来输入与真实 Worker 环境凭据。范围和剩余差距见 `M1-SANDBOX-SECURITY-VALIDATION.md`、L-032；这不证明内核逃逸防护或任意递归 fork 风暴的完备性。随后第 5.3 节补充可信资源事件判定，不能依据不可信 stderr 或单独 exit 137 猜测 verdict。

### 5.3 基于可信内核事件的结果分类

每例启动 JVM 前，通过固定 `/usr/bin/cat`、受控 UID 65534 和完整容器 ID 读取 cgroup v2 的 memory.events 与 pids.events。执行结束、清空用户进程后再读取，两份计数按键解析并比较增量。文件位于只读内核挂载，用户程序不能修改；不读用户 stdout/stderr 的 OOM 文本，也不信任自选退出码。必需键缺失、重复、负数、溢出、计数回退或控制读取失败均为平台故障，不默认 AC/RE。

规则：pids.events:max 增加为 `SECURITY_VIOLATION`；memory.events:oom 增加为 `MLE`（包括子 JVM 被 OOM 杀死后父程序仍输出正确答案）。oom_kill 单独增加但 oom 没增加可能来自宿主全局 OOM，按平台故障处理。memory.events:max/high 的回收压力不单独构成 MLE。同例多个信号的固定优先级为：平台控制/证据失败 → PID 安全违规 → cgroup OOM → OLE → TLE → RE → 输出比较。任何非 AC 停止后续例；资源 verdict 与其他用户结果一样一次终态写回、不重试，ACK 仍在持久化之后。

V3 CHECK 已允许八种 verdict，但原 VARCHAR(16) 无法存储 18 字符的 SECURITY_VIOLATION。新增 V5 仅将 verdict 扩为 VARCHAR(32)，不修改 V1–V4、历史行、权限或消息字段。部署须先由 migrator 应用 V5，再启动新 Worker；API 既有所有者查询可读取新字符串，诊断仍为空，WebSocket 仍不传 verdict。沙箱限制值、源码及测试数据的快照不变，本次为既有 M1 结果合约的实现补齐，未改变 judge_version 或镜像。

支持范围：有可靠 cgroup 事件的内存超限/PID 违规。纯 Java 堆或 metaspace 的 OutOfMemoryError 若未触发内核 oom，不伪装成可信 MLE；被程序捕获时仍可按输出 AC，未捕获时为 RE。联网/写只读目录/提权被拒不自动推断 SECURITY_VIOLATION。cgroup v2 是本实现的环境要求；控制进程被资源挤占、容器 init 被杀或 daemon 故障时保守走平台故障与有限恢复。完整边界见 L-032 和 `M1-RESOURCE-VERDICT-VALIDATION.md`。

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

### 9.1 已确认的通知接口与恢复方式

用户确认 D-039：`/api/v1/submissions/{submissionId}/events` 为原生 WebSocket，只订阅当前登录用户自己的一个提交，不接受客户端命令，也不提供历史回放。消息严格只有上述三个字段，verdict、诊断与源码均不推送。握手沿用现有 HttpSession，匿名 401；他人/不存在/非法 UUID 统一 404；Origin 必须存在且与请求 scheme/host/port 相同，缺失、null、跨源、多值或带路径/用户信息等非法 Origin 拒绝 403。不加入 STOMP/SockJS、跨源白名单或 URL 会话令牌；已有 POST CSRF 边界不变。

API 使用当前进程有界订阅表，默认全局最多 20 个、每用户 2 个连接；超额升级后立即以 1013 关闭，不保留 watcher。默认每 500ms 只查询这些活动订阅的所有者三字段投影，同时检查账号 ACTIVE 与原登录会话。首次发送当前已提交快照（允许版本 0），后续只发送更大版本；终态发送后正常关闭。登出、会话失效、账号禁用或提交不可读以 1008 关闭；数据库或传输错误关闭连接并释放名额，不无限重试。客户端入站消息被拒绝，单条消息上限 512 字节，发送缓冲上限 8192 字节、Tomcat 同步发送超时 1 秒；应用退出释放所有订阅。这些值是保守资源上限，不是容量或延迟测量结论。

订阅扫描直接读 MySQL 的已提交结果，不改 Worker、Outbox 或任务状态机；它不是可靠消息队列，中间状态可能合并。前端收到更高版本通知才触发所有者 GET，所有显示的 verdict/诊断来自 GET；低于已见通知或已显示版本的迟到 HTTP 响应不覆盖页面。同版本 GET 可补全通知中没有的结果字段。一直保留每 1 秒一次的轮询兜底且同一监视器最多一个 GET 在途；暂时错误仍轮询，401/403/404 停止。连接失败/断开最多另试 3 次（2/4/8 秒），耗尽后仍轮询；终态、替换提交与卸载清理定时器/连接并丢弃迟到回调。

开发 Vite `/api` 代理启用 WebSocket 且保留浏览器 Host，不改写 Origin。反向代理部署必须正确保留公开 Host/scheme，并只信任受控代理；本轮没有开启全局任意 forwarded-header 信任。当前依赖进程内会话，跨节点会话与大规模推送未实现，见 L-029 和 `M1-NOTIFICATION-VALIDATION.md`。

### 9.2 关联脱敏日志实现

API 与 Worker 使用既有 Boot 内置 Logstash JSON 格式器与 SLF4J 字段，不新增编码器依赖、共享模块或日志平台。API 为每次 HTTP 请求生成内部 UUID `requestId`，忽略客户端请求 ID；只记录白名单 method、固定 route 分类、状态码和耗时，不输出 URL、query、请求体、Cookie、CSRF、认证头或账号信息。MDC 在成功和异常路径都恢复，避免线程复用串号。WebSocket 只覆盖 HTTP 握手请求，不伪装为长连接的持续请求追踪。

关联方式为 `requestId → submissionId/judgeTaskId → attemptId`：创建提交的事务提交回调记录请求与业务 ID；Outbox 从已有 Task 联表投影读取 ID，不解析/打印 payload；Worker 只使用已验证消息与已领取 attempt 的显式字段。MQ 契约仍为四字段，没有把 requestId、租约或凭据塞进消息，也不承诺一个 HTTP 请求 ID 贯穿异步全链路。

提交创建/取消、attempt 领取/完成/重试/死信、配额延期与过期租约恢复只在事务 `afterCommit` 输出；外层回滚不宣称成功。恢复扫描的 `recovery.sent` 只表示发送调用返回，不冒充 broker confirm；`delivery.ack_sent` 只表示客户端 ACK 调用返回，不证明 RabbitMQ 已收到 ACK。Outbox `outbox.published` 必须同时满足 confirm、可路由与标记已发布更新成功。

错误日志仅使用固定 failureCode，不记录异常对象、broker 原始 NACK 原因、源码、诊断、隐藏输入/期望输出、源码/数据哈希、leaseToken 或配置 workerId。普通结果只记录平台定义 verdict。心跳失败在心跳线程使用显式 attempt 身份，不复制请求 MDC。日志不是持久审计事件：提交后、输出前仍存在进程崩溃窗口；日志丢失或写入失败不改变数据库事实，见 L-030 与 `M1-OBSERVABILITY-VALIDATION.md`。

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
