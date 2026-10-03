# M2 作者内容正式验证验收

2026-10-04，实施基线 `59afa5a65c7997624291192c43f2cfb479e12af2`，分支 `feat/m2-accounts`，本验证单元 VERIFIED。完整内容与 M2 始终 IN_PROGRESS，未发布。合约见 [设计](M2-CONTENT-VALIDATION-JOBS-DESIGN.md)，可提交的安全事实与实际页面见 [证据目录](evidence/m2-content-validation/README.md)。所有时间明确区分 UTC 与 Asia/Shanghai；这份记录是本轮实际结果，不复用历史 269/45。

## 已实现边界

V11 追加四个独立内容验证表，原 V1–V10 不变，无验证成功种子。API 原子保存 immutable metadata/参考/独立题解/压缩测试/摘要/固定资源与策略、job 和内容 Outbox；敏感写事务复核当前会话，owner/DRAFT/CAS/幂等与共享 1 running+3 pending 生效。Worker 只读取独立快照，正式沙箱分别运行两份程序；两者均 ACCEPTED 才 PASSED，其他用户程序结果为 FAILED，平台错误有限重试并最终 SYSTEM_ERROR。

内容任务不创建 Submission、公共题或官方题解。lease/attempt/token/期限保护 claim、heartbeat、finish/failure，恢复永久关闭旧 attempt 后才可清理其沙箱；WAITING_RETRY 保留运行槽。ACK 在事务提交后，严格四字段消息与独立 durable 验证/死信队列。数据库拒绝无租约的 RUNNING 和无完整结论的 FINISHED。API 无 Docker；Worker 无账号/学习/可变作者权限，双方都不能改不可变快照。已产生验证引用的草稿禁止物理删除，归档保留快照、任务和结果。

`/authoring` 仅显式验证已保存版本，显示两个结果及冻结版本、旧版本/归档提示，支持私有分页历史与刷新；切账号/草稿/卸载忽略迟到响应。网络不确定重试保留 requestId，409 不自动覆盖本地编辑。

## 已完成局部检查

- Windows Worker `./mvnw.cmd --batch-mode -pl forgeoj-judge-worker '-Dtest=ValidationExecutionIntegrationTests' test`：9 项，零失败/错误/跳过，00:40:35+08 完成。真实 MySQL/RabbitMQ/Docker 双通过、独立题解 WA、参考 CE 后题解仍执行、终态重复投递不增加 attempt、恶意四字段/重复 key 拒绝至独立死信、双向共享运行额度、并发 claim 单赢家、过期租约全部旧写拒绝、真实孤儿沙箱清理、有限平台重试与摘要篡改拒绝。人工过期用例是数据库控制，不是 Worker 进程崩溃证明。
- Windows API `ContentValidationIntegrationTests,ContentValidationMigrationIntegrationTests,OutboxPublisherIntegrationTests`：7 项，零失败/错误/跳过，00:41:34+08 完成。真实 HTTP/Tomcat owner/CAS/幂等/共享队列额度、V10→V11 原22表逐列保留和新4表为空、实际 publisher confirm/PERSISTENT/四字段/独立 queued与dead路由，以及原正式 Outbox恢复。
- 最终增强的 API `ContentValidationIntegrationTests`：3 项，零失败/错误/跳过，00:51:44+08 完成；补 no-store/分页/DTO 白名单/跨用户读取与数据库不完整状态拒绝。
- 前端 `npm run verify`：50 项全部通过，type/lint/format/build 均通过。新5项检查双结果、失败不可伪装通过、请求不确定重试/409、身份迟到隔离、归档历史。

## 固定 Linux / 精确构件 / 浏览器 / 审计

执行 `.\tools\validation\Verify-FixedLinux.ps1 -Scope All`，产物 `target/forgeoj-linux-20261004-005209-5f775952`。固定 Linux/amd64、仓库固定 Java 21/Maven 3.9.14/Node 24 镜像，全新构建缓存；根 `clean verify` 于 2026-10-04 01:06:30+08 完成。API **163** 项/30 suites + Worker **120** 项/22 suites = **283**，失败/错误/跳过均0；前端 **50** 项/11 suites，类型、lint、格式及生产构建全部通过。使用已批准 opt-in 测试专用 MySQL bridge 直连，生产 JAR 不包含 DirectMySQLContainer、故障子 Worker 或测试控制类；未改生产连接、未自动重试测试。既有 Surefire 退出超时诊断及停止测试 fixture 时的连接警告仍可见，不能声称日志无 ERROR；实际 BUILD SUCCESS 与测试计数见报告。

| 固定对象 | SHA-256 |
| --- | --- |
| API JAR | `c6796000d5a2a1833ad3c077a7b17a4c59c4fc5fc9e5dd3ea5afdee32f43c9f5` |
| Worker JAR | `fc365d5438054861a8c39e1a9cc3c1832ae00537f4fd2b17992c20a7c3209cdd` |
| 构建输入 manifest | `496d428044f4fbebbc95803857075df0058a3d1f179b988df0b34b9b7f8727f6` |

`check-build-inputs.mjs` 初次检查于 17:08:28.689Z 得到全部261一致。最终 23:23:42.779Z 检查为 **260/261 未变、所有后端/前端/合约输入一致**；唯一变化是下述已直接执行的 Replay audit 工具断言修正。文档/截图/操作型崩溃脚本不属于生产构建输入。不能声称最终261全未变。

精确 JAR 使用 `Replay-FixedLinux.ps1 -Action Start -BuildDirectory target/forgeoj-linux-20261004-005209-5f775952 -ApiSha256 <上表值> -WorkerSha256 <上表值>` 创建独立 Replay `target/forgeoj-e2e-20261004-010844-26feff40`。先 `Library`、`Matrix`，暂停 Worker 后 `Permissions` 真排队取消，再 `Learning`、启动 Worker。实际浏览器端口50706（正常）/50704（阻断 WebSocket 的轮询兜底）；5173/5174 是容器内部端口，不冒充 host 端口。所有 fixture 在可丢弃库中，不是正式审核发布。

### 实际作者页面与权限

浏览器创建一份原创草稿 `f2bf6248-7374-4b09-af32-598b9dd887d8`，完整填写来源/题面/思路、分别保存两份程序并保存一个手工测试，所有验证由页面显式发起，数据库事实只用于核对结果。

| 冻结版本 | job ID | 实际参考结果 | 实际独立题解结果 | 实际验证结论 |
| --- | --- | --- | --- | --- |
| 3 | `c9cc6e71-ea9f-4333-a9a0-764c9dd68737` | ACCEPTED | WRONG_ANSWER | FAILED |
| 5 | `2c92b846-3169-4646-8179-6de7e480f509` | ACCEPTED | ACCEPTED | PASSED |
| 6 | `fd9c0c16-08e5-48ce-8b8d-94425ce93375` | ACCEPTED | ACCEPTED | PASSED，实际中断恢复 |

页面分别展示失败、双通过、修改后版本5旧结果失效，以及归档到版本7后的三条私有历史；归档编辑和新验证禁用。HTTP `Content` 动作消费真实浏览器 requestId/版本核对 receipt，验证 owner 分页/白名单/no-store、另一用户404/匿名401、size51=400、归档后原 requestId 仍202返回原任务、同 requestId 换版本409、新归档验证409、有引用草稿删除409；公共题数量仍2。页面截图为实际捕获，不生成或改图。

### 实际 Worker SIGKILL 与自然恢复

在版本6的第一 attempt 已 RUNNING、租约仍有效且一个实际 managed sandbox 正运行时，执行本轮操作型 `content-crash-replay.ps1`（安全副本在证据目录），核对精确项目/Worker labels 后 SIGKILL 容器 `8e13c2853398…`，实际确认退出且数据库仍 RUNNING。17:15:47.0733015Z kill，17:16:26.0842507Z 完成核对。重新启动**同一实际生产 Worker**，等待自然租约到期与恢复，不手工修改 expires_at，不用测试子进程或伪造终态。

旧 attempt `6e08c532-8a09-48ce-9e82-0810be201e71` 永久 LEASE_EXPIRED，旧沙箱 `ec14958cd18ba5d6aeaf0aab7880462296498851b6cd6c6792ac76fa0ea2f962` 已删除；新 attempt `13a4b233-cbcd-41c7-a906-b435ff942646` SUCCEEDED、两结果 ACCEPTED、job FINISHED/PASSED、租约清空，attemptCount从1变2。恢复的两个内容 Outbox 均真实发布。正式 Submission 数量前后均9，验证没有制造 AC。Replay 使用12秒租约/1秒心跳/500毫秒扫描；默认生产配置30秒/5秒心跳/5秒扫描，此证据不冒充默认时长或任意网络故障测试。

### 现有链路与独立审计

正常页面实际观察 `SUBMITTING → FINISHED/AC`（第二道原创 max 题），任务太快，下一帧前已经完成；未声称肉眼观察到 QUEUED。兜底页面在暂停 Worker 时实际观察 `QUEUED → FINISHED/AC`，启动后轮询完成（sum 题）。正常 WebSocket 转发与多次GET、兜底WebSocket阻断与多次GET均由审计确认；兜底 intentional ECONNREFUSED 是测试设置。八 verdict、owner/Origin/撤销、真实取消、题库/学习合约再次通过。

`Replay-FixedLinux.ps1 -Action Audit -RunDirectory <上述 Replay>` 于17:25:19.600Z通过：正式Submission11（FINISHED10/CANCELLED1）、正式已发布Outbox11；内容job3、内容已发布Outbox4；**六个具名队列 ready/unacked均0**。每条正式 claim/terminal/ACK与内容afterCommit创建/Outbox/最后attempt claim/commit/ACK链严格核对，恢复旧attempt关闭/新attempt/seq0、1同时闭合。摘要核对真实冻结代码、测试数1和归档/失效事实。API/Worker日志无源码/测试/邮件凭据等哨兵，日志仅关联白名单，不当作持久审计。

API runtime uid65534、只读根、无直接公共端口、无Docker socket/Worker配置/迁移凭据，bootstrap进程已移除；Worker无账号/学习/可变作者读取。审计实际15条数据库拒绝，另实际5条列/INSERT权限拒绝（Worker不可改owner_id/snapshot_id/client_request_id、API不可改job执行状态、Worker不可插入快照），合计20条均按1142/1143严格核对，WHERE1=0没有业务数据更改。

### 最终清理与输入差异

真实浏览器两张自建IAB页签已关闭。首个 `Stop` 因Docker Desktop引擎未运行、named pipe不存在而失败，此失败不作为零残留证据；执行 `docker desktop start --timeout 60` 后确认Server29.8.0，重跑精确 `Stop`。2026-10-04 **07:28:53+08** 实际查询 owned容器/卷/网络/Worker镜像、Testcontainers、managed沙箱均0；固定构建临时镜像也0。无全局prune，无清理无关资源。真实数据库/外部服务未修改。

唯一 post-build输入变化 `tools/validation/replay-audit.mjs`：旧 `ba7527d59e3f3d3dc8073546078a37a911513baaf2a3d6b32e1a25135448e293` → 新 `c0ce411f9bc122701fc3056c574fa0f84f60f81fd31f21852b802b43aa18af5a`。断言接受首个实际UI状态SUBMITTING或QUEUED并仍要求FINISHED/AC；未降低真实服务端创建、claim/commit/ACK、队列、权限或日志检查。修改后的脚本已在上述实际Replay执行通过，不需要重建未变化的业务构件。安全崩溃操作脚本是构建后新增、已实际执行的外部控制，未放入生产构件。

## 失败与修正

最初 Docker Desktop engine 未启动，开启已安装引擎后再运行；该次失败未到业务断言。API 新路由的最初红测试实际得到401，新增路由后得到202，不将引擎失败充当红测试。

新 Worker fixture 最初 Rabbit 容器 API/镜像兼容声明编译/启动失败；改为既有实际可用的 admin/vhost 与 compatible substitute。随后 MySQL NULL 比较无法映射 primitive boolean，造成 claim 失败与 broker 重交付；leaseValid 改为显式非 NULL布尔表达式。业务结果正确后，队列检查仍因依赖 JSON字段顺序失败；改为解析真实 broker JSON并必须存在具名队列、ready/unacked 均0，不弱化断言。前端断言误匹配静态“验证通过后仍须送审”，改为只检查结果卡片。严格重复键拒绝和数据库终态约束新增相应直接回归。

浏览器准备原创fixture时曾把换行以字面转义保存，随后修正JSON序列化；独立Java代码的正则反斜杠也在验证版本5前纠正。这是验收输入准备修正，实际失败版本3与通过版本5、6分别保留，不把fixture准备错误写成生产代码修复。清理引擎暂停和正常UI状态工具断言修正如上，失败记录不算成功证据。

## 后续边界

正式不可变送审/撤回与后续受控审核发布、参考输出生成预览、自测短期任务、真实服务商 SMTP 投递仍未完成。已有受限 Docker 边界与可信资源分类限制继续适用；不提升整个 M2、不产生发布或简历准入状态。
