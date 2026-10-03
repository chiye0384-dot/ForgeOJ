# ForgeOJ 简历证据矩阵

2026-10-04 作者双程序正式验证单元 VERIFIED：V11 独立不可变快照、owner/CAS/幂等、共享额度、双结果、租约隔离与私有历史，固定 Linux API163 + Worker120 =283、前端50及全检查通过。同一产物真实参考AC/题解WA、双AC、编辑失效/归档、实际Worker SIGKILL自然恢复、六队列/20实际数据库拒绝/日志链审计和精确清理见 [验收](M2-CONTENT-VALIDATION-JOBS-VALIDATION.md)与[安全事实](evidence/m2-content-validation/README.md)。完整内容与M2保持IN_PROGRESS，尚未受控审核发布、未新增RESUME_READY或性能结论；这些证据不替代发布和用户自己的项目讲解能力。下面阶段记录保留历史。

> 当前状态：E-01/E-02 为 `IMPLEMENTED`，已有 M1 范围的实现与验证证据；完整 V1 候选证据未闭环。其他项仍为 `PLANNED`；没有任何 `RESUME_READY`，不能写入简历。
> 本文不是简历文案，而是决定一条文案是否有资格进入简历的证据清单。

2026-10-02 M2 普通账号单元已在 `feat/m2-accounts` 验证通过，设计见 D-041 / `M2-ACCOUNTS-DESIGN.md`，实际命令、源码/JAR 哈希、固定 Linux 后端 211 / 前端 13 项、相同产物的真实注册到 AC/旧账号轮询兜底、独立审计和精确清理见 [账号验收](M2-ACCOUNTS-VALIDATION.md)和[脱敏事实](evidence/m2-accounts/README.md)。这不改变下面候选项的准入状态，没有新增 `RESUME_READY`；完整 M2 仍 `IN_PROGRESS`，真实 SMTP、Redis、多节点、云上试运行、性能与发布尚未完成。M1 的 160/8 仍只描述其历史阶段，不充当新账号证据。

## 1. 准入规则

2026-10-03 官方题解访问单元 VERIFIED，最终固定 Linux 269/45、真实取消不留记录/用户隔离/AC 解锁、降级与审计清理见 [验收](M2-SOLUTION-ACCESS-VALIDATION.md)。正式内容验证/审核发布仍未完成；不新增 RESUME_READY，不提升完整 M2。

作者内容草稿阶段固定 Linux 261/39、实际页面/ZIP/冲突/归档及审计清理已通过，见 [记录](M2-CONTENT-VALIDATION.md)。正式内容验证/送审/发布仍未完成，不新增 RESUME_READY，不提升完整 M2。

2026-10-03 个人学习记录单元 VERIFIED：固定 Linux 后端 241 / 前端 35、真实两页草稿冲突、正常/兜底 AC、题单进度与本人历史、提交快照独立性及审计清理见 [验收](M2-LEARNING-RECORDS-VALIDATION.md)。没有性能、发布或真实 SMTP 投递证据，完整 M2 与简历准入状态保持原边界。

2026-10-03 新增公开题库与 SMTP 适配单元 VERIFIED：固定 Linux 后端 229 / 前端 24、实际选题与轮询到 AC、独立审计和精确清理见 [验收](M2-LIBRARY-SMTP-VALIDATION.md)。SMTP 仅本地 TLS 协议通过，真实投递未验收；完整 M2 与下面简历准入状态均不因此提升。

一条能力只有同时满足以下条件，状态才能从 `PLANNED` 更新为 `RESUME_READY`：

1. 已进入明确的 Git tag/release；
2. 正常路径和关键异常路径均有验证；
3. 代码、配置和数据库迁移入口可定位；
4. 测试或演练命令可由他人复现；
5. 性能数字保留环境、脚本和原始结果；
6. 对应限制和失败边界已经公开；
7. 项目所有者能脱离稿子解释、定位，并完成一次小修改后重新验证。

状态取值：

- `PLANNED`：只有设计；
- `IMPLEMENTED`：代码存在；
- `VERIFIED`：证据通过；
- `RELEASED`：进入 release；
- `RESUME_READY`：全部准入条件满足。

“用了某个中间件”本身不是亮点。亮点必须说明它解决了什么问题，以及故障时系统怎样表现。

## 2. 候选亮点总表

| ID | 候选主题 | 当前状态 | 进入简历前必须具备的核心证据 | 主要限制 |
|---|---|---|---|---|
| E-01 | 可靠异步判题链路 | `IMPLEMENTED` | M1 自动恢复/幂等证据已具备；完整候选仍需 M4 管理重试审计、broker 暂停恢复演练与发布 | 至少一次投递；最终一致窗口；单机 MQ |
| E-02 | Docker 代码沙箱 | `IMPLEMENTED` | M1 实际限制/恶意程序/故障清理证据已具备；完整候选仍需 M5 独立 Linux 主机安全验收与发布 | 普通 Docker 非绝对安全边界；受控小范围使用 |
| E-03 | 班级与作业权限模型 | `PLANNED` | OWNER/ASSISTANT/MEMBER 资源级鉴权；成员和作业生命周期测试；越权矩阵 | 不验证真实教师；教师只见本班作业数据 |
| E-04 | Redis/ES 可降级数据架构 | `PLANNED` | MySQL 权威数据、Outbox 同步、版本幂等、缓存和索引重建；Redis/ES 故障演练 | 降级时性能或搜索能力下降 |
| E-05 | 独立管理员与运维闭环 | `PLANNED` | 三类后台角色权限测试；公共题审核；DLQ 幂等重试；审计记录 | 单人项目仍需用测试证明职责隔离 |
| E-06 | Linux 云端交付与恢复 | `PLANNED` | Compose、HTTPS、Flyway、备份恢复、回滚、监控和真实试运行记录 | 单机，不承诺高可用和 SLA |
| E-07 | 可复现性能优化 | `PLANNED` | 相同环境下的基线、瓶颈证据、改动、回归报告及原始数据 | 未压测前没有任何可信 QPS/P95 数字 |

最终简历只选择最强的三到五项，不需要把全部候选都写入。

## 3. 证据卡模板

每个候选亮点完成时复制并填写一张卡。

~~~markdown
### E-XX：主题

- 状态：
- 解决的问题：
- 为什么需要这个设计：
- 被拒绝的替代方案：
- 我的具体改动：
- 代码入口：
- 配置 / 数据库迁移：
- 正常路径测试：
- 异常路径测试：
- 故障演练：
- 复现命令：
- 原始输出 / 报告：
- 性能环境与结果：
- Git commit：
- Release/tag：
- 已知限制：
- 可以现场演示的流程：
- 可以现场完成的小修改：
- 高频追问：
- 我的回答是否已用真实实现核对：
~~~

## 4. 各候选项最低证据

### E-01：可靠异步判题链路

必须证明：

- Submission 和 Outbox 在同一事务；
- 事务提交后发布消息，而不是业务事务中直接依赖 RabbitMQ；
- 同一 judgeTaskId 重复消费不会重复改变最终结果；
- Worker 在写库前后崩溃时可恢复；
- ACK 丢失不会创建第二条用户提交；
- 超过重试上限会进入死信；
- OPS_ADMIN 的重试可审计且幂等；
- RabbitMQ 暂停后恢复，不会丢失数据库已接收的提交。

建议证据文件：集成测试报告、故障注入记录、状态时序图、队列和数据库截图、相关 commit。

当前局部证据（不足以把 E-01 提升为 `VERIFIED`）：M0 已验证 Submission/JudgeTask/Outbox 原子创建与回滚、四字段持久消息、publisher confirm、幂等领取、原子终态写回、写回后 ACK、重复投递吸收和 `SYSTEM_ERROR`；固定 Linux/amd64 进程级重放已覆盖六 verdict。M1 截至 `44642f3` 又实现 attempt/lease 栅栏、心跳、被动/主动恢复、有限重试/死信、可恢复 Outbox、独立队列、并发用户配额和排队取消。2026-09-30 Windows 完整回归 API 26 项、Worker 57 项（零跳过），并发/消息聚焦复验通过 19 + 16 项；代码、V4、合约、限制和命令见 [M1 配额与取消记录](M1-QUOTA-CANCELLATION-VALIDATION.md)。尚缺完整 Worker 进程崩溃/ACK 丢失矩阵、固定 Linux M1 重放、OPS_ADMIN 运维闭环与 release，不能生成可靠链路已发布或简历就绪的结论。

2026-10-01 新增局部通知证据：现有 M1 分支工作树实现所有者同源 WebSocket 与版本/轮询恢复；完整 Windows 回归 API 38 + Worker 57（零失败/错误/跳过），前端 8 项测试、类型/静态/格式检查与构建通过。真实 HTTP/WebSocket + MySQL 验证权限与通知，前端使用模拟 API/socket；不是浏览器/独立 Worker 的固定 Linux 重放。详见 [M1 通知验证记录](M1-NOTIFICATION-VALIDATION.md)。E-01 状态仍不变，尚未提交推送，不生成发布或简历就绪声明。

2026-10-01 追加局部关联日志证据：请求 ID 与业务/attempt ID、提交后日志、固定故障码及脱敏/线程复用/外层回滚测试；完整 Windows 回归 API 46 + Worker 67、前端 8 项全过且零跳过，见 [M1 可观测性记录](M1-OBSERVABILITY-VALIDATION.md)。它不是持久审计、统一 tracing 或固定 Linux 故障验收；工作树未提交推送，E-01 状态不提升。

2026-10-01 21:04 追加真实 Worker 子 JVM kill 与租约恢复、终态提交/ACK 前中断、跨队列重复投递，以及孤儿沙箱恢复/活跃 owner 保留；先复现 task-only 名冲突再修复 attempt 身份与安全回收。全量 API 46 + Worker 83、前端 8 项通过且零跳过，见 [故障恢复记录](M1-FAULT-RECOVERY-VALIDATION.md)。仅 Windows 子进程 + Docker Desktop 局部证据，未覆盖任意 socket ACK 丢包或固定 Linux；OPS_ADMIN/release 仍未完成，E-01 状态不提升。

### E-02：Docker 代码沙箱

必须证明：

- API 服务没有 Docker Socket；
- 用户进程非 root、无网络、根文件系统只读；
- CPU、内存、PID、时间和输出限制会实际触发；
- 无限循环、进程爆炸、内存耗尽、写文件、联网和超量输出被控制；
- 用户代码看不到数据库/MQ/Redis 凭证和隐藏答案；
- 所有终止路径都清理容器和临时文件；
- 文档承认普通 Docker 的边界。

建议证据文件：威胁模型、沙箱测试表、容器 inspect 脱敏输出、清理监控、相关 commit。

当前局部证据（不足以把 E-02 提升为 `VERIFIED`）：M0 已在固定 Linux/amd64 中验证 API 容器无 Docker Socket，Worker 独占 Docker 控制；真实 inspect 和执行测试覆盖非 root、禁网、只读根、capabilities 删除、no-new-privileges、CPU/内存/PID/tmpfs 限制、stdin 传入源码/隐藏输入、一次编译、逐例独立 JVM、AC/WA/CE/RE/TLE/OLE 和全分支容器清理。固定 Linux 六 verdict 进程级链路结束后管理沙箱数为 0，日志也未出现隐藏哨兵。更宽的进程爆炸、内存耗尽、写文件、联网尝试、凭证不可见性矩阵和 M5 固定 Linux 主机安全验收仍待完成。

新增局部资源所有权证据：真实 Worker 崩溃遗留沙箱可在恢复领取关闭旧 attempt 后回收；另一个 Worker 启动保留活跃沙箱；旧 finally 只操作原完整容器 ID，不碰新 attempt。未知/legacy 归属保守保留、DB/daemon 失败延后，见 L-031 与 [进程故障记录](M1-FAULT-RECOVERY-VALIDATION.md)。没有据此声称任意恶意代码安全、崩溃瞬时清零或固定 Linux 已验收，E-02 状态不提升。

2026-10-01 安全阶段新增 14 项实际受限容器恶意程序和真实 Worker 凭据检查；先复现清理权限、后代残留、全局 tmp 与共享内存问题再修复。根回归 API 46 + Worker 98，144 项零失败/错误/跳过，见 [安全验证记录](M1-SANDBOX-SECURITY-VALIDATION.md)。这是 Windows + Docker Desktop 局部证据；MLE/SECURITY_VIOLATION 可信分类、任意 fork 风暴/内核逃逸防护、固定 Linux M1/M5 验收不因此完成。E-01/E-02 不提升为简历就绪或已发布状态。

2026-10-02 新增内核计数分类、V5 列宽修复及升级保留、实际 MQ 两类结果/重复投递和所有者字段隔离证据。根回归 API 48 + Worker 110，158 项零失败/错误/跳过，见 [资源结果记录](M1-RESOURCE-VERDICT-VALIDATION.md)。只承诺可证实的局部 cgroup OOM 与 PID 上限，纯 JVM OOM/无审计的拒绝操作按 L-032 保留限制；前端未改未重跑。固定 Linux M1、OPS_ADMIN、发布与 M5 仍待验收，E-01/E-02 状态不提升。

随后实现保存为 `ab61c9a`，同一源码在固定 Linux/amd64、只读挂载、全新缓存中通过 API 48 + Worker 110、前端 8 项和全部检查（零失败/错误/跳过）。Linux 五项真实子 Worker 故障/凭据检查、16 项安全程序、HTTP/WebSocket 与实际 MQ 资源结果均通过，详见 [Linux 记录](M1-FIXED-LINUX-VALIDATION.md)。仍缺本轮 Linux 产物的独立完整纵向链路与 OPS_ADMIN/最终门禁，且没有 Release/tag/个人解释验收，不把 E-01/E-02 改为 VERIFIED 或 RESUME_READY。

后续独立 Linux 重放新增八 verdict、真实浏览器 normal/fallback、owner/Origin/logout/取消、日志链路、Outbox/队列和 API 权限审计证据，见 [M1 E2E](M1-E2E-VALIDATION.md)。11 个提交中 10 FINISHED/1 CANCELLED，11 Outbox 已发布、四队列空、精确资源清理通过。复用已核验 JAR，不新增全量测试次数或性能数字；OPS_ADMIN/最终门禁、发布与个人解释验收仍缺，E-01/E-02 保持原状态。

范围与状态校正（2026-10-02）：上面的日期段落保留当时证据，实时门禁见 [M1 最终审计](M1-GATE-AUDIT.md)。代码已存在且具有多阶段验证，候选总表据实从 `PLANNED` 改为 `IMPLEMENTED`，但不把完整 V1 候选写成 `VERIFIED/RELEASED/RESUME_READY`。OPS_ADMIN 属于 M4：它阻止完整 E-01 证据闭环，不是新增的 M1 管理门禁；E-02 的生产主机验收仍属 M5。没有 Release/tag、性能数字或个人解释/现场修改验收。

最终审计结果：M1 的 15/15 门禁 PASS，补齐后的固定 Linux 后端 160/前端 8 项全过，生产 runtime 条目与真实浏览器/八结果 E2E 完全对应，见审计记录。只升级 M1 里程碑，不跳过完整 E-01/E-02 的上述后续证据，也不生成简历文案。

### E-03：班级与作业权限模型

必须证明：

- 角色在班级维度生效，而不是全站角色；
- ASSISTANT 不能转让/归档班级或管理其他助教；
- MEMBER 不能读取其他成员提交；
- OWNER/ASSISTANT 只能读取本班作业关联提交；
- 退出、移除、重新加入、归档后访问立即改变；
- 作业成员快照不会被当前成员列表静默改写；
- PRECOMPLETED 指向真实历史 AC，不伪造提交。

建议证据文件：权限矩阵、接口集成测试、资源归属攻击测试、相关 commit。

### E-04：Redis/ES 可降级数据架构

必须证明：

- Redis 或 ES 数据清空后，核心业务数据不丢失；
- 缓存失效事件与 ES 同步事件来自可靠 Outbox；
- 旧版本消息不能覆盖新索引；
- ES 返回 ID 后，MySQL 再次校验公开状态；
- Redis 故障时认证不会越过封禁或会话失效；
- ES 故障时回退搜索不会无限重试；
- 全量索引可以从 MySQL 重建。

建议证据文件：故障演练、索引重建报告、降级接口测试、监控截图、相关 commit。

### E-05：独立管理员与运维闭环

必须证明：

- 普通用户 Token 无法访问后台接口；
- CONTENT_REVIEWER 不能重试判题；
- OPS_ADMIN 不能审核题目；
- 只有 SUPER_ADMIN 能管理后台账号；
- 首个 SUPER_ADMIN 无硬编码默认密码；
- 管理员不能手工把 WA 改为 AC 或修改用户源码；
- 审核、下架、恢复和重试均留下审计记录。

建议证据文件：权限测试、审计样例、初始化说明、相关 commit。

### E-06：Linux 云端交付与恢复

必须证明：

- 在全新 Linux 主机按文档完成部署；
- 只有 80/443 对公网开放，基础设施不直接暴露；
- HTTPS、健康检查和发布后冒烟测试有效；
- Flyway 能完成空库初始化和旧版本升级；
- 数据库备份能恢复到全新实例；
- 应用镜像回滚演练成功；
- 试运行问题有记录而不是只保留“上线成功”截图。

建议证据文件：部署记录、端口检查、恢复/回滚报告、脱敏监控和 release。

### E-07：可复现性能优化

必须证明：

- 基线和优化后使用相同机器、commit 记录方式、数据集、脚本和测试时长；
- 先由观测数据定位瓶颈，再做改动；
- 同时报告吞吐、P50/P95/P99、错误率和资源占用；
- Web API 的 req/s 与判题的 submissions/min 分开；
- 原始结果保留，不能只截取最好的一次；
- 提升比例可由原始数据重新计算。

建议证据文件：性能计划、原始 CSV/JSON、Grafana 截图、优化前后报告和相关 commit。

## 5. 简历文案生成规则

在证据完成前，不预写具体数字。完成后，文案按以下结构生成：

~~~text
针对【真实问题】，设计并实现【关键机制】，通过【异常测试/故障演练】验证【正确性结果】；
在【固定环境】下以【脚本和数据集】测得【真实指标】，并明确【适用边界】。
~~~

禁止：

- 复制其他 CodeJudge 项目的 398 req/s、P95 191 ms 等数字；
- 把“引入 Redis/RabbitMQ/ES”写成结果；
- 把本地启动写成“稳定上线”；
- 把计划中的 V1.1/V2.0 写成已实现；
- 把 AI 生成但未经理解和验证的代码称为本人掌握。

## 6. 发布前自测

每条最终文案都要完成一次模拟面试：

1. 60 秒说清问题、方案和结果；
2. 现场定位核心代码；
3. 解释至少两个替代方案为何未选；
4. 推演一个故障路径；
5. 完成一个小改动并重新跑测试；
6. 主动说明限制，而不是等面试官揭穿。

任一步无法完成，该条暂不标记为 `RESUME_READY`。
