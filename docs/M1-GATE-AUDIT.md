# M1 最终门禁审计

> 日期：2026-10-02，Asia/Shanghai；分支 `feat/m1-reliable-judging`；审计基线 `2e1af5774fc3a20822ece932619adaf04084a757` 加本轮两项测试补齐。15/15 门禁 `PASS`，M1 标记为 `VERIFIED`；不是 PR 合并、M5 云主机验收或正式发布。

## 1. 验收范围与纠偏

本审计严格对照 [M1 设计第 10 节](M1-RELIABLE-JUDGING-DESIGN.md#10-m1-门禁) 的 15 项，不以测试总数替代门禁，也不移除既有要求。

- Roadmap 第 5 节的 M1 验收自动有限重试、死信状态/Outbox/队列边界、租约恢复、配额、通知和受控沙箱。
- [Roadmap 第 8 节](ForgeOJ-Roadmap.md#8-m4管理后台搜索与降级) 将独立管理员认证、三个角色、人工死信幂等重试和管理审计安排在 M4。阶段交接把 OPS_ADMIN 提前写为 M1 剩余工作是记录偏差，不是新的用户决定；本轮校正记录，不改变 D-016 或 V1.0 管理要求。
- M1 自测只验收独立队列和路由边界；自测记录/页面属于 M2。没有宣称完整自测用户功能已交付。
- `PASS` 仅指下表列明的测试、故障和环境范围；不是任意网络丢包、绝对隔离、瞬时清理、高可用或生产 SLA 的证明。

## 2. 本轮新增直接证据

只修改原有集成测试，不增加业务代码、依赖、schema、grants、HTTP/MQ 字段或管理员入口。

1. `OutboxPublisherIntegrationTests.recoversTheSameEventAfterRoutingReturnsBeforeExhaustion`：删除正式队列后收到 `UNROUTABLE`；事件保持未发布，尝试数为 1，未到期不忙重试。重建真实路由并让隔离事件到期后，同一 eventId 成功发布，计数为 2、错误清空；仍只有一个 Task/Outbox，实际收到持久四字段消息，再扫描不多发。使用测试库推进 `next_attempt_at`，不冒充真实长期等待或整个 broker 停机演练。
2. `JudgeTaskClaimIntegrationTests.recoveredLeaseRejectsEveryOldOwnerWriteWithoutChangingNewAttempt`：在隔离 MySQL 中推进旧租约到期并实际重新领取 attempt 2；旧 token 的续租、安排重试、写死信和写 WA 全部拒绝，Task/双表版本/两条 attempt 内容未变且无额外 Outbox。新 owner 随后可续租并写 `FINISHED/AC`。真实进程 kill 与实际等待租约到期的证据另由五项子 JVM 测试提供，不混为同一次测试。

Windows 聚焦命令（JDK 21，Docker Engine 可用，仓库根）：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-api '-Dtest=OutboxPublisherIntegrationTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-judge-worker '-Dtest=JudgeTaskClaimIntegrationTests#recoveredLeaseRejectsEveryOldOwnerWriteWithoutChangingNewAttempt' test
```

10:31:55 第一条通过 2 项，10:34:47 第二条通过 1 项；均零 failures/errors/skipped。第一次聚焦命令于 10:25:49 因 Docker Desktop Linux 引擎关闭、连接管道不存在而在 fixture 启动前失败（1 error），没有业务断言执行；启动既有 Docker 环境后重跑通过，没有修改系统 Docker 配置或放宽测试。

固定 Linux 本轮复现命令：

```powershell
.\tools\validation\Verify-FixedLinux.ps1 -Scope All -DockerCommand 'C:\Users\Lenovo\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
```

使用 [既有固定镜像](M1-FIXED-LINUX-VALIDATION.md#1-输入与环境)、只读源码复制和全新 Maven/npm 缓存。新报告目录：`target/forgeoj-linux-20261002-103513-8c714c20/`。后端 **10:43:48** 完成，耗时 **08:26**，三个 reactor 均 SUCCESS；独立求和 fresh XML：

| 模块 | XML | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| API | 11 | 49 | 0 | 0 | 0 |
| Worker | 21 | 111 | 0 | 0 | 0 |
| 合计 | 32 | 160 | 0 | 0 | 0 |

两项新增测试的 fresh Linux XML 分别有对应 testcase（0.612s/0.380s），不是沿用旧报告。五项真实子 Worker 故障/凭据、16 项安全程序、六项 cgroup 事件校验、八结果原子映射、真实 MQ/配额/通知权限等全部实际执行，无失败、错误或跳过。

前端 **10:44** 完成：npm ci 安装 292/审计 293 包，0 vulnerabilities；vue-tsc、OxcLint、ESLint、Prettier、2 文件/8 项 Vitest、Vite 8.3.1 生产构建全部通过。不会把 npm 版本提示当成升级授权，本轮不改依赖版本。

### 2.1 输入、产物与既有真实链路的对应关系

后端启动时输入清单为 208 文件，清单 SHA-256 `c016feb3635bb907fb428439d30577b3a338225cd030786b39fcf408a0aabfd2`；验证期间仅继续编写收尾文档。脚本各 Scope 共用清单路径，前端随后写入含审计文档的 209 文件清单，SHA-256 `84d884aa2845299bc8064d99e1a2c85fdb1f00d914c61341a68b565a578c4940`。两阶段间没有业务/测试/构建输入变化；分别核对 **184 个非文档输入与工作树一致**。收尾状态文档在验证之后定稿，不声称整个文档快照也逐字相同。

| 本轮产物 | SHA-256 |
| --- | --- |
| API JAR | `4ced92f5e1dae0b625f02d79a5a48732b4a0c7300169336fe252dca32fa8131f` |
| Worker JAR | `6fc2d2ffe6126f6adb65bf9f56f1353d0a5e37e0d1c035d3ca11374a36056038` |
| 前端 index.html | `6ac38844af2dc8ae6a6cc4da28dfd6dd4d0c3ce67e2271b79a13dc87ad082cb1` |

逐个比较新旧 JAR 的 `BOOT-INF/classes/` 与 `BOOT-INF/lib/` 非目录条目：API **125**、Worker **104** 项名称/字节哈希全部相同，生产 JAR 无 fault/test 控制类。两项新增测试不会打包进业务 JAR；JAR 整体哈希因 ZIP 构建时间元数据不同而变化。

所以本轮没有重复运行已通过的浏览器纵向链路，而以相同运行时代码/资源/依赖关联 09:50 的 [E2E 证据](M1-E2E-VALIDATION.md)：八 verdict、正常通知和仅 WebSocket 不可用时轮询、权限/日志/队列/空库/清理。其 matrix/permissions/audit/两张浏览器截图共 **5** 份文件的既有 SHA-256 再次核对一致。不冒称在新 JAR 整体哈希上又执行了一遍 E2E。

本轮完成后只读检查 Docker 全部容器清单、managed 沙箱、唯一临时构建镜像与故障子 JVM 均为空；脚本正常删除自己的构建镜像，没有全局 prune。报告/截图与产物保留在 Git 忽略目录。`git diff --check` 通过。

## 3. 十五项门禁逐项映射

下列测试均有可定位源码及本轮 160 项 fresh Linux 回归；前端 8 项也新鲜通过。八类结果和真实浏览器证据见 [独立 E2E](M1-E2E-VALIDATION.md)，其与本轮运行时代码的对应关系见上节。

| # | 门禁 | 状态 | 实际证据与范围 |
| ---: | --- | --- | --- |
| 1 | 并发重复消息只有一个有效 attempt | PASS | `JudgeTaskClaimIntegrationTests` 的并发领取/真实 Rabbit 重复消费只调用执行器一次；Task/attempt 唯一事实、队列无 unacked。 |
| 2 | 领取后崩溃，另一 Worker 恢复 | PASS | `WorkerProcessFaultIntegrationTests.killedClaimOwnerIsRecoveredByAnotherJvmAfterRealLeaseExpiry` 在 Linux 杀自己的子 JVM，实际等待 lease 到期，由另一子 JVM 完成，不用 SQL 冒充真实过期。 |
| 3 | 旧租约不能续期、重试或覆盖新结果 | PASS | 既有 stale terminal、heartbeat rollback 和原子版本条件；本轮 recoveredLease 全写路径拒绝测试在 Windows 聚焦和新鲜 Linux 全量均通过，旧 token 不改变新 attempt/状态或增加 Outbox，新 owner 正常完成。 |
| 4 | 终态无 ACK 后重复不再执行 | PASS | `terminalCommitBeforeAckIsRedeliveredWithoutASecondExecution` 在提交后/basicAck 前中断进程，真实 Rabbit 重投并交错重复消息，最终只有一个成功执行。不是任意 socket ACK 包丢失注入。 |
| 5 | 平台有限重试，用户 verdict 不重试 | PASS | Claim 的满队列有限恢复、Completion 的平台重试/八结果原子映射、Backoff 的翻倍有界；实际 MQ MLE/PID 重复不重试，E2E 八结果均单一 SUCCEEDED attempt。 |
| 6 | 耗尽死信，SYSTEM_ERROR/null | PASS | Claim 实际经历三次 attempt 的到期/满队列耗尽；Completion 验证 `DEAD_LETTER/DEAD_LETTERED/SYSTEM_ERROR`、verdict 为空和同事务死信 Outbox；真实 MQ 非法可信配置在 ACK 前进入死信；API publisher 另验证真实独立死信路由。没有管理员人工重试。 |
| 7 | Outbox 失败有退避、计数和恢复 | PASS | 既有真实 unroutable、计数 1→3、退避/耗尽停止和 NACK 脱敏；本轮同 eventId 恢复成功在 Windows 聚焦和新鲜 Linux 全量均通过，真实队列持久消息/不重复创建与再发。 |
| 8 | 重复、乱序、扫描无重复业务结果 | PASS | 真实跨正式/重试队列重复投递与终态后旧消息吸收；Claim 到期 retry/过期 lease/配额延期恢复扫描；不多建 Submission，旧版本和所有权条件不能回退事实。 |
| 9 | 并发用户配额与 QUEUED 幂等取消 | PASS | API 并发不同 key 最大 3 排队、最后名额幂等重放、并发取消版本只加一次/领取竞争；Worker 一用户只有一个运行槽位、跨用户独立、满队列 WAITING_RETRY 仍只 3 排队、耗尽释放；真实页面链路取消零 attempt 并吸收旧消息。 |
| 10 | 通知乱序不覆盖，轮询可恢复 | PASS | 前端 7 项 monitor 回归含旧通知/迟到 GET/有限重连/轮询恢复；API 真实所有者同源会话测试；E2E 实际浏览器正常和仅 events 故障页面均从 QUEUED 到 FINISHED/AC。前端单测的乱序使用 fake socket，不冒称实际网络乱序注入。 |
| 11 | 恶意程序受到资源与权限限制 | PASS | 16 项实际 Docker 安全程序、八 verdict 执行/E2E 与 inspect 覆盖无限循环、局部 OOM、PID、输出、tmpfs、只读、非 root/no-new-privileges、禁网、进程/文件跨例清理。不是任意递归 fork 风暴或内核逃逸证明。 |
| 12 | 凭据、他人源码和整套隐藏数据不可读 | PASS | 真实带凭据子 Worker 程序、另一个活跃提交源码隔离、隐藏答案/未来输入未传入、stdin 单例输入；真实 DB 拒绝 API 隐藏表/attempt 和 Worker 账号表；消息/响应/日志白名单哨兵检查。 |
| 13 | ID 串联各进程与结果日志 | PASS | E2E 审计 10 个执行的 requestId→Submission/Task/Outbox→attempt→完成/ACK；创建/取消/领取/完成/重试事件仅 afterCommit，外层回滚不误报，NACK/异常仅固定码。日志是 L-030 尽力输出，不是持久审计。 |
| 14 | API 无 Docker，终止后清理 | PASS | E2E API 非 root/只读/只挂自己的 JAR，无 socket、CLI、Worker/migrator 配置；正常八结果、控制失败、超时及进程故障路径最终精确 ID 回收，另一个活跃 owner 保留。本数据库可确认归属且 DB/daemon 可用时的最终清理，非崩溃瞬时清零，未知/legacy 保留（L-031）。 |
| 15 | 固定 Linux 构建/故障/真实链路 | PASS | 本轮全新缓存 Linux 后端 160/前端 8 项与全部检查；五项子 JVM 故障/凭据实际执行；独立 Vite/API/MQ/Worker/空库/browser 重放已有同运行时代码证据，184 输入/229 runtime 条目对应关系已核对。不是 M5 独立云主机验收。 |

## 4. 不应被 PASS 掩盖的后续工作

- L-026 在本次 15 项审计通过后关闭，关闭的是 M0 缺少 attempt/lease 崩溃恢复的旧缺口；L-027～L-032 是仍有效的配额锁、满队列等待、进程会话、尽力日志、保守最终清理和可信资源判定限制，不因 M1 收尾而删除。
- M2 完整普通账号、注册与 quota row 同事务、自测/历史、题库/草稿；M4 管理认证/角色、DLQ 人工幂等重试/审计、Redis/ES/监控；M5 独立 Linux 主机、HTTPS/备份恢复/安全性能验收，均未由本轮实现。
- E-01 的完整 V1 证据仍缺 M4 管理重试与 broker 暂停恢复演练；E-02 的生产主机验收仍在 M5。M1 的局部门禁通过不自动升级为 Release 或 `RESUME_READY`。
- 未创建 PR、未合并 main、未打 tag、未发 Release；功能分支 push 不触发现有 CI workflow，不能声称 GitHub Actions 已通过。

## 5. 提交判断

两项新增测试已在固定 Linux 全量中通过，生产输入与 E2E 产物关联核验通过，15 项门禁定稿，状态文档同步，形成独立的“M1 验收收尾”单元：测试补齐有明确对应门禁，范围纠偏可追溯，后续阶段可据此继续。向用户解释该闭环理由后才提交；不是单个 `continue` 指令触发机械提交。仅保存并推送现有功能分支，不隐含 PR/合并/发布权限。

M1 于 2026-10-02 标记为 `VERIFIED`。下一里程碑 M2 仍为 `PLANNED`；先确定账号/题库学习主流程的最小设计与接口，不能直接铺开所有模块。当前 M1 功能分支未创建完成 PR、未合并 main、未发布。
