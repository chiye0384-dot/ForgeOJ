# M0 最小判题纵向切片设计

> 状态：`IMPLEMENTATION_BASELINE`
> 定稿日期：2026-09-28
> 适用里程碑：M0
> 注意：本文是实现约束，不代表相应功能已经通过验收。

## 1. 目标与成功标准

M0 只实现并验证一条最短闭环：

```text
预置普通用户登录
  -> 查看一道人为原创的内置题
  -> 提交 Java 21 单文件 Main.java
  -> API 同一事务创建 Submission、JudgeTask、Outbox
  -> RabbitMQ 至少一次投递
  -> Worker 幂等领取任务
  -> 一次性 Docker 容器编译并执行
  -> MySQL 条件式写回结果
  -> 前端轮询并显示终态
```

M0 必须以自动化证据覆盖 `AC`、`WA`、`CE`、`RE`、`TLE`，证明重复消息不会重复执行同一任务，并证明 API 进程没有 Docker 控制权限、超时后容器与临时目录被清理。

## 2. 明确不在 M0 完成的内容

- 不实现注册、邮箱验证、找回密码、JWT、刷新令牌和多会话管理；
- 不实现完整题库、题目编辑审核、提交历史列表、代码草稿和 PageHelper；
- 不引入 Redis、Elasticsearch、Spring Cloud、Kubernetes 或完整管理后台；
- 不实现 M1 的 attempt/租约、Worker 崩溃恢复、完整重试退避、死信运维、自测队列、WebSocket 和并发配额；
- 不宣称普通 Docker 容器能够安全承载互联网中的任意恶意代码；
- 不把 H2 测试结果当作 MySQL 事务、锁、权限或 Flyway 迁移证据。

## 3. 进程与权限边界

| 进程/角色 | 允许 | 禁止 |
|---|---|---|
| Browser | 通过同源 API 登录、读公开题面、提交、轮询自己的结果 | 读取隐藏测试、他人提交或基础设施信息 |
| `forgeoj-api` | 公共题面、用户认证、创建提交、Outbox 发布、查询本人结果、执行 Flyway | Docker Socket/CLI 控制、读取隐藏测试内容 |
| `forgeoj-judge-worker` | 消费四字段任务消息、读取判题快照和隐藏测试、条件更新任务、控制 Docker | 读取用户密码、提供公网 HTTP 接口、成为数据库迁移所有者 |
| RabbitMQ | 传递任务标识 | 携带源码、隐藏测试、用户信息或资源配置 |
| MySQL | 保存全部权威业务状态 | 依赖缓存或消息状态推断最终业务事实 |

目标部署至少使用三个数据库账号：

- `forgeoj_migrator`：只供 API 启动阶段的 Flyway 使用；
- `forgeoj_api`：访问用户、公开题目、提交、任务和 Outbox，但不能读取 `problem_test_case`；
- `forgeoj_worker`：读取判题快照和隐藏测试、条件更新任务及结果，但不能读取 `user_account.password_hash`。

开发环境可以由管理员账号完成账号和授权初始化，但集成测试必须证明 API 账号直接查询隐藏测试表会被 MySQL 拒绝。

## 4. M0 直接依赖边界

所有未单独锁定的版本由 Spring Boot 4.1.1 BOM 管理。

### API 运行依赖

- Spring MVC；
- Spring Security；
- Spring AMQP；
- MyBatis Spring Boot Starter 4.1.0；
- Flyway Core 与 Flyway MySQL；
- MySQL Connector/J。

### Worker 运行依赖

- Spring AMQP；
- MyBatis Spring Boot Starter 4.1.0；
- MySQL Connector/J；
- JDK 自带 `ProcessBuilder`，通过外部 Docker CLI 控制容器。

### 测试依赖

- Spring Boot/Security Test；
- Testcontainers JUnit Jupiter、MySQL、RabbitMQ 模块。

M0 不引入 `docker-java`。Docker 控制隐藏在 ForgeOJ 自有的 `SandboxRuntime` 与 `DockerCommandExecutor` 接口之后；若以后切换客户端，只替换基础设施适配器并新增独立决策记录。

## 5. 最小 HTTP 契约

所有路径以 `/api/v1` 开头。除登录失败等认证边界外，错误响应不得泄露隐藏测试、SQL、容器参数或服务器路径。

### 5.1 会话

#### `GET /api/v1/auth/session`

匿名和已登录用户都可以调用。返回认证状态；已登录时返回最小用户信息；同时返回 Spring Security CSRF token，前端后续写请求放入 `X-CSRF-TOKEN`。

M0 使用单 API 实例的服务端内存 Session。它只用于验证受保护提交链路，不是 V1.0 最终的多实例认证方案。

#### `POST /api/v1/auth/login`

请求：

```json
{
  "username": "learner",
  "password": "development-only-secret"
}
```

密码只以 BCrypt 哈希写入 dev/test seed；生产迁移不得创建公开默认密码。

#### `POST /api/v1/auth/logout`

使当前 Session 失效。所有写请求均校验 CSRF。

### 5.2 题目

#### `GET /api/v1/problems/{slug}`

仅返回字段白名单：题目 slug、标题、题面、输入/输出说明、公开样例、公开资源限制和当前 judge version。不得返回隐藏用例内容、用例编号、用例数量、正确输出哈希、数据集哈希或参考程序。

### 5.3 提交

#### `POST /api/v1/problems/{slug}/submissions`

- 必须登录；
- Header `Idempotency-Key` 必须是 UUID；
- `language` 在 M0 只接受 `JAVA_21`；
- `sourceCode` 必须是无 `package` 的单文件 `Main.java`，并受固定字节数上限约束；
- 相同用户与相同 key 的网络重发返回第一次创建的 submission；用户主动再次提交必须生成新 key。

成功返回 HTTP `202 Accepted`：

```json
{
  "submissionId": "UUID",
  "processingStatus": "QUEUED",
  "statusVersion": 0
}
```

#### `GET /api/v1/submissions/{submissionId}`

只允许提交所有者读取；其他用户和不存在的 ID 统一返回 `404`。返回处理状态、单调递增的 `statusVersion`、终态 verdict 和经过白名单处理的诊断信息，不返回失败用例编号、隐藏输入/输出、通过数量或逐用例资源数据。

## 6. 数据模型

M0 建立 7 张表；时间统一为 UTC `DATETIME(6)`，数据库字符集为 `utf8mb4`，业务枚举以受约束的短字符串保存。UUID 在 HTTP/MQ 中使用标准文本格式，M0 数据库使用 `CHAR(36)`，避免在第一条纵向链路中额外引入 UUID 二进制 TypeHandler。

### 6.1 `user_account`

- `id BIGINT` 主键；
- `username VARCHAR(64)` 唯一；
- `password_hash VARCHAR(100)`；
- `status VARCHAR(16)`，M0 只接受 `ACTIVE`/`DISABLED`；
- `created_at DATETIME(6)`。

### 6.2 `problem`

- `id BIGINT` 主键；
- `slug VARCHAR(80)` 唯一；
- 标题、题面、输入说明、输出说明与公开样例；
- `status VARCHAR(16)`，M0 只提供一题 `ACTIVE` 内置题；
- `current_judge_version_id BIGINT`；
- `created_at`、`updated_at`。

`ACTIVE` 题必须有当前判题版本。初始 seed 因循环引用可先插入题目和版本，再回填该字段。

### 6.3 `problem_judge_version`

- `id BIGINT` 主键；
- `problem_id BIGINT`、`version_no INT`，组合唯一；
- `time_limit_ms`、`memory_limit_mb`、`output_limit_bytes`；
- `comparison_rule_version`、`sandbox_policy_version`；
- 固定的 Java 21 镜像 digest；
- `test_dataset_sha256 CHAR(64)`；
- `created_at`。

### 6.4 `problem_test_case`

- `id BIGINT` 主键；
- `judge_version_id BIGINT`、`ordinal INT`，组合唯一；
- gzip 压缩的输入/正确输出 BLOB；
- 解压后大小和各自 SHA-256；
- `created_at`。

API 数据库账号不得对该表拥有 `SELECT` 权限。

### 6.5 `submission`

- `id CHAR(36)` 主键；
- `user_id`、`problem_id`、`judge_version_id`；
- `client_request_id CHAR(36)`，与 `user_id` 组合唯一；
- `language`、不可变源码快照及 `source_sha256`；
- 时间、内存、输出限制以及比较规则、沙箱策略、测试集哈希、镜像 digest 的提交时快照；
- `processing_status`、可空 `verdict`、`status_version`；
- 经过裁剪和脱敏的 `diagnostic_message`；
- `created_at`、`started_at`、`finished_at`。

### 6.6 `judge_task`

- `id CHAR(36)` 主键；
- `submission_id CHAR(36)` 唯一；
- `task_type`，M0 固定为 `JUDGE_SUBMISSION`；
- `contract_version`，M0 固定为 `1`；
- `task_status`、`status_version`；
- `created_at`、`started_at`、`finished_at`。

### 6.7 `outbox_event`

- `id CHAR(36)` 主键；
- `aggregate_type`、`aggregate_id`、`event_type`、`contract_version`；
- 只包含允许字段的 JSON payload；
- `created_at`、可空 `published_at`；
- `(event_type, aggregate_id)` 唯一；
- `(published_at, created_at)` 索引。

## 7. 状态机与不变量

Submission 处理状态：

```text
QUEUED -> RUNNING -> FINISHED
                  -> SYSTEM_ERROR
```

M0 verdict 只包含 `AC`、`WA`、`CE`、`RE`、`TLE`。

- `FINISHED` 必须有 verdict；
- `SYSTEM_ERROR` 的 verdict 必须为空；
- 用户代码编译/运行错误属于用户 verdict；Docker、Worker、数据库或平台故障属于 `SYSTEM_ERROR`；
- 每次成功状态变化都把 `status_version` 增加 1；旧版本写入不得覆盖新版本；
- 历史提交必须依赖自身保存的完整判题快照，不能在重跑时读取题目的“当前配置”替代。

JudgeTask M0 状态为 `QUEUED`、`RUNNING`、`FINISHED`、`SYSTEM_ERROR`。M0 的比较并交换只能防止并发重复执行，不能恢复 Worker 在领取后崩溃造成的悬挂任务；租约和 attempt 模型属于 M1。

## 8. 创建提交与 Outbox

API 在一个数据库事务内完成：

1. 校验已登录的 ACTIVE 用户、ACTIVE 题目、`JAVA_21` 和源码基本约束；
2. 读取并固化当前 judge version 及全部判题参数；
3. 插入 `submission`；
4. 插入一条唯一 `judge_task`；
5. 插入一条唯一 `outbox_event`；
6. 提交事务。

任何一步失败都不得留下三者之一。相同 `(user_id, client_request_id)` 的请求读取并返回第一次的 submission，不重复创建任务或 Outbox。

Outbox 发布器只在 RabbitMQ publisher confirm 成功后设置 `published_at`。确认结果不确定时允许再次发送；重复发送由 Worker 的领取条件吸收。M0 只做打通链路所需的最小发布循环，完整退避、死信和补偿运维在 M1 完成。

## 9. RabbitMQ 契约

- durable direct exchange：`forgeoj.judge`；
- durable queue：`forgeoj.judge.submission.v1`；
- routing key：`judge.submission.v1`；
- persistent message、manual ACK、prefetch `1`。

消息 JSON 只能包含：

```json
{
  "taskId": "UUID",
  "submissionId": "UUID",
  "taskType": "JUDGE_SUBMISSION",
  "contractVersion": 1
}
```

源码、用户 ID、题目内容、隐藏测试、资源限制、数据库凭证和镜像凭证都不得进入消息。Worker 必须从 MySQL 读取任务并交叉验证 `taskId` 与 `submissionId`，不能仅凭消息访问隐藏数据。

中立的 JSON Schema 和合法 fixture 放在根目录 `contracts/`；API 与 Worker 各自定义 DTO 和契约测试，两个可执行模块不得互相建立 Maven 依赖。

## 10. Worker 幂等领取与 ACK

Worker 收到消息后，先在短事务内执行同时校验 task/submission 的条件更新：

```text
judge_task QUEUED -> RUNNING
submission QUEUED -> RUNNING
```

- 更新成功：当前 Worker 获得执行权；提交事务后才调用沙箱；
- 任务已经 RUNNING 或终态：视为重复消息，不调用沙箱，直接 ACK；
- task/submission 对应关系不一致：拒绝继续读取隐藏数据，记录安全事件并 fail closed；
- Docker 执行完成后，在短事务中条件式写入 JudgeTask 与 Submission 终态；数据库提交成功后才 ACK。

## 11. Docker 执行适配器

M0 使用系统已安装的官方 Docker CLI，由 `ProcessBuilder(List<String>)` 直接调用：

- 不经过 `cmd.exe`、PowerShell 或 Bash；
- 不拼接 shell 命令字符串；
- 用户源码、文件名和题目字段不得成为 Docker CLI 参数；
- Docker 可执行文件路径、镜像 digest 和资源限制来自可信配置；
- 容器名称与标签只由服务端 task UUID 生成；
- Worker 启动时执行 `docker version`，fail fast 验证 daemon 可达且为 Linux Engine。

最小生命周期：

1. 创建带 ForgeOJ 管理标签的容器；
2. 应用非 root、禁网、只读根文件系统、删除 capabilities、`no-new-privileges`、CPU/内存/PID/输出与受限临时空间；
3. attach 启动并并发读取 stdout/stderr，使用有界缓冲；
4. Java 侧实施总超时；超时后执行强制删除容器及匿名卷；
5. 正常退出后 inspect exit code、OOMKilled 和 daemon error；
6. 所有分支在 `finally` 中清理容器和任务临时目录；
7. Worker 启动时按管理标签清理可确认属于 ForgeOJ 的残留容器。

开发机使用 Docker Desktop 作为外部运行环境，不把 Docker Desktop 分发进 ForgeOJ，也不把它当作 Apache-2.0 项目组件。最终判题证据仍需在固定 Linux 环境复现。

## 12. M0 自动化验收

1. Flyway 在全新 disposable MySQL 上完成迁移；
2. 预置 BCrypt 用户登录成功，错误密码失败，未登录提交返回 401；
3. 题目响应 JSON 不含任何隐藏字段；
4. `forgeoj_api` 直接查询隐藏测试表被 MySQL 拒绝；
5. 成功提交同时且各仅创建一条 Submission、JudgeTask、Outbox；
6. 人为让 Outbox 插入失败时三张表均无残留；
7. 相同 Idempotency-Key 的顺序和并发重放只产生一组记录；
8. 不同 Idempotency-Key 产生两次主动提交；
9. MQ 契约测试证明只有四个字段，哨兵源码与隐藏测试不在消息中；
10. 同一消息投递两次，沙箱调用计数仍为 1；
11. 原创 Java fixture 通过完整链路分别得到 AC、WA、CE、RE、TLE；
12. Docker 不可用时写入 `SYSTEM_ERROR` 且 verdict 为空；
13. TLE 后不存在相应容器和任务临时目录；
14. API 运行环境无 Docker Socket/控制配置；
15. 已登录用户读取他人 Submission 得到 404；
16. API 响应、MQ 消息和业务日志中都找不到隐藏测试哨兵值；
17. `status_version` 在 QUEUED、RUNNING、终态严格递增；
18. 前端自动化覆盖登录、读取题目、提交和轮询至终态；
19. 依赖结构检查证明 API 与 Worker 互不依赖；
20. 固定 Linux 环境重放完整链路并保存 commit、镜像 digest 和运行记录。

只有以上门禁全部有可复现证据后，M0 才能从 `IN_PROGRESS` 改为 `VERIFIED`。
