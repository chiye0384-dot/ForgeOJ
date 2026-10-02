# M1 固定 Linux 独立进程纵向链路验证

> 日期：2026-10-02，Asia/Shanghai。应用实现 `ab61c9a`，本轮基线 HEAD `8f18174`；分支 `feat/m1-reliable-judging`。本轮完成独立链路验证工具及初始化修复，不升级 M1、E-01/E-02，不代表 M5 云主机验收或发布。

## 1. 本轮完成的单元

`tools/validation/Replay-FixedLinux.ps1` 从唯一一次性 Compose 项目和空 MySQL/RabbitMQ 卷启动：

1. 既有 dev Flyway 入口执行 V1–V5 和开发种子，然后停止、删除持有 migrator 凭据的 bootstrap 进程。
2. 启动独立 Linux API JAR、Outbox publisher、RabbitMQ、独立 Worker JAR 和 Vite。只有 Worker 获得 Docker CLI/socket；API 为 UID 65534、只读根，仅挂载只读 API JAR，无公开端口、Docker/Worker/migrator 配置。
3. 原创 HTTP/WebSocket 探针通过真实 Vite 代理提交八种程序，等待真实终态通知后才 GET 数据库结果；不拿 SQL 直接制造判题终态或模拟 socket 代替链路。
4. 真实浏览器操作两条页面流程：正常代理与仅 `/events` 不可用的故障代理。第二条保留真实 HTTP/API，不伪造判题响应。
5. 审计双表状态、attempt、Outbox 字段/发布、队列、权限、日志链路和残留；最后只清理本次项目资源，保留本地报告。

新增第二个测试用户仅用于他人所有权校验；账号复制既有公开 dev 种子的 bcrypt 值。所有密码均明确标为 PUBLIC TEST，仅用于本次隔离项目，不接触 `.env` 或真实业务数据库，不新增 M2 注册功能。

## 2. 固定输入与运行环境

API/Worker 使用 [固定 Linux 构建记录](M1-FIXED-LINUX-VALIDATION.md) 中已经 fresh-cache 验证的 Linux 产物，启动前再次核验 SHA-256：

| 输入 | SHA-256 |
| --- | --- |
| API JAR | `236e2352d2d67d1885dbf8905b0e6adb82479c5732c44b64efd326d32b4de650` |
| Worker JAR | `60d9f0aa42d143e95a3036984b48096967548d61bb52b0e391ccfa3d3fe46d5b` |

本轮未修改 Java/Vue 业务源码、依赖、HTTP/MQ 合约、迁移或数据库 grants。唯一部署源码修复是 MySQL 初始化脚本子 shell 隔离，见第 6 节。Vite 从当前只读源码复制，重新 `npm ci`（292 包、293 审计、0 vulnerabilities），不复用宿主 node_modules。复用固定镜像，无升级：

| 服务/工具 | 固定镜像 |
| --- | --- |
| MySQL | `container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be` |
| RabbitMQ | `rabbitmq:4.3.6-management@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95` |
| API/Worker/用户程序 | `eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438` |
| Vite | `node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c` |
| Worker CLI | `docker:29.8.0-cli@sha256:f5b8bb0333cfaa027640106e5f02e48b0a8e0c00f7165015f581d58783c76fcf` |

实际为 Docker Desktop 4.92.0、Engine/CLI 29.8.0、Linux/amd64、cgroup v2，Temurin 21.0.12_8、Node 24.14.1/npm 11.11.0。不是独立 ECS Linux 主机。前端仅随机绑定 `127.0.0.1` 两个端口；MySQL/RabbitMQ/API 均无 host 发布端口。

## 3. 实际结果

最终隔离项目：`forgeoj-e2e-20261002-093916-3d8ef203`，09:39:16 创建，09:50:44 审计通过，之后精确清理成功。报告目录：`target/forgeoj-e2e-20261002-093916-3d8ef203/`（忽略，不提交会话或原始运行日志）。

| 程序 | Submission ID | 持久结果 |
| --- | --- | --- |
| AC | `5988726b-193f-496e-b81c-dc3bab02fce1` | FINISHED / AC / version 2 |
| WA | `ed0e007a-8974-463b-acbd-f73f4b03c04e` | FINISHED / WA / version 2 |
| CE | `36f9f52d-dfa5-4525-8a4f-bdf9a3909361` | FINISHED / CE / version 2 |
| RE | `b2e8530b-c5ab-4bc1-9ffe-3f59e6e6b027` | FINISHED / RE / version 2 |
| TLE | `6fc99c68-bebf-4219-a7f6-9f36c1be344b` | FINISHED / TLE / version 2 |
| OLE | `56963a1a-619e-4eae-a0b2-9a8d1419340f` | FINISHED / OLE / version 2 |
| MLE | `c0e56ae2-c946-4758-a466-7782327fb0c9` | FINISHED / MLE / version 2 |
| SECURITY_VIOLATION | `306ea626-bde1-4cf2-997f-099fc840818e` | FINISHED / SECURITY_VIOLATION / version 2 |

八项均由真实 WebSocket 得到 `QUEUED/0 → RUNNING/1 → FINISHED/2`，每条仅 submissionId、processingStatus、statusVersion 三字段，终态 close 1000；随后 GET 五字段白名单与预期 verdict 一致，除 CE 外诊断为 null。公开题目仍为八字段白名单。十个执行任务均只有一个 `SUCCEEDED` attempt，双表版本相等且 lease 清空。

权限探针通过：匿名升级 401；他人和不存在 ID 均 404；跨 Origin 403；他人 GET 404；owner 订阅有效，logout 204 后 socket close 1008，后续 GET 401。重登录取消 `d398faf1-7550-4e7a-b632-6a489467a66b`，双表 CANCELLED/version 1、零 attempt；恢复 Worker 后已发布消息被 ACK 吸收，不执行取消任务。

Vite 会对拒绝的升级请求直接关闭流，不能依赖它转发 HTTP 401/403/404。负向状态码因此针对真实 API 测试，并保留代理 Host/Origin；正常 owner 订阅仍穿过真实 Vite。不是宣称浏览器能读取被拒 WebSocket 的 HTTP 状态码。

实际浏览器（Codex in-app Chromium）：

- 正常页面：`54546141-2cb2-4636-9e5d-2c00681dca22`，先停 Worker 观察 QUEUED/version 0，再恢复 Worker，页面自动到 FINISHED/AC/version 2；代理记录一次 owner WebSocket 转发及权威 GET。
- 故障页面：`ce035a44-5745-493f-afee-5e5514c6c1f8`，仅 events 路由指向不可达的容器本地端口；观察 QUEUED 后恢复 Worker，页面靠真实 GET 到 FINISHED/AC/version 2。代理记录被阻断的有限重连、无成功 events 转发，以及多次 HTTP GET。没有修改生产 Vue 代码。
- 页面截图 `browser-normal.jpg`、`browser-fallback.jpg` 保存在本地报告目录；`browser.json` 是人工真实观察的记录，不是自动化 UI 测试。程序审计仅交叉核对 ID、数据库与代理事件，不能把人工记录本身称为自动 UI 证明。

## 4. 数据、日志与清理审计

`E2E_AUDIT_VERIFIED`：11 submissions、10 finished、1 cancelled、11 publishedOutbox、4 emptyQueues。

- Outbox 每项只含 taskId、submissionId、taskType、contractVersion，无源码/隐藏测试；初始 sequence 0，全部已发布、无 failed 标记。
- 正式、重试、自测、死信四个队列的 ready/unacknowledged 均为 0。本次不注入死信，不据此关闭 OPS_ADMIN/DLQ 处置门禁。
- 每个创建请求 requestId 对应 202，与 submissionId/judgeTaskId/outboxEventId 关联；每个执行 attempt 的 claimed/finished/ack_sent 持有相同 ID，终态日志在 ACK 写出前。`ack_sent` 仍不证明 broker 收到 ACK。
- API/Worker 日志没有原创源码/编译错误/运行异常哨兵、公开测试密码、会话 Cookie、CSRF header、隐藏 gzip 列名。没有扩大为“所有第三方日志完全脱敏”的保证。
- 实际数据库拒绝 API 读取 problem_test_case、judge_task_attempt，拒绝 Worker 读取 user_account（ERROR 1142）；API 无 Docker socket/配置，bootstrap 已不存在。
- 清理前 managed 沙箱数 0；`Stop` 明确启用 worker/bootstrap profiles，对本项目每个容器先核对 Compose label，再删除本项目容器、两个一次性数据卷、网络和唯一验证 Worker 镜像；结束检查所有这些项目资源均为 0。没有全局 prune，原始报告和截图仍保留。

| 本地报告 | SHA-256 |
| --- | --- |
| matrix.json | `c6203c25c841563c59f1ac261cadc42689ff0f9ff229c3a9dda0aa1264105532` |
| permissions.json | `19982abcd1bae98d9509659cdb78c9c7ee79dd410d2ecb444e9ed25d3b5a2078` |
| audit.json | `c1484b15772b1ad0331360d34558e7001b1777d32ce05cbd0c38f0b73e65ec63` |
| browser-normal.jpg | `2dca7811d05f01b34acddd6ca3d3535230f776aeb4a852f3a4af08648ebb81b9` |
| browser-fallback.jpg | `bdf6826b884ed6af3d2da32ca4407647bed661eff898dbacce107c5fb9955cfa` |

## 5. 复现步骤

从仓库根、PowerShell 7 执行，Docker 必须为 Linux/cgroup v2；不要与其他 ForgeOJ managed 沙箱测试并发。没有默认产物时先运行 `Verify-FixedLinux.ps1 -Scope All`，检查其完整结果和 JAR 哈希，再显式传入新的 BuildDirectory/ApiSha256/WorkerSha256，不能用更新哈希掩盖未验证构建。

```powershell
$replayDocker = 'C:\Users\Lenovo\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
.\tools\validation\Replay-FixedLinux.ps1 -Action Start -DockerCommand $replayDocker
# 从输出获取本次唯一 RunDirectory；不要使用其他项目的 state。
$replayRun = 'target/forgeoj-e2e-YYYYMMDD-HHMMSS-xxxxxxxx'
.\tools\validation\Replay-FixedLinux.ps1 -Action Matrix -RunDirectory $replayRun -DockerCommand $replayDocker
.\tools\validation\Replay-FixedLinux.ps1 -Action WorkerStop -RunDirectory $replayRun -DockerCommand $replayDocker
.\tools\validation\Replay-FixedLinux.ps1 -Action Permissions -RunDirectory $replayRun -DockerCommand $replayDocker
.\tools\validation\Replay-FixedLinux.ps1 -Action Status -RunDirectory $replayRun -DockerCommand $replayDocker
```

保持 Worker 停止，在 Status 的 FrontendUrl 登录公开测试账号 learner / forgeoj-dev-only，使用默认代码提交，记录真实 QUEUED 的 ID，然后执行 WorkerStart 并观察 FINISHED/AC，保存截图。再次 WorkerStop，在 FallbackUrl 提交，记录 QUEUED 后 WorkerStart，观察真实轮询终态并截图。若重启 frontend，Docker 的随机 host port 可能变化，重新 Status，不重用旧 URL。

在本次 RunDirectory 写 `browser.json`，内容为真实观察（不得预先填写虚构成功）：

```json
[
  {"mode":"normal","port":5173,"submissionId":"实际正常提交ID","visibleStates":["QUEUED","FINISHED","AC"],"screenshot":"browser-normal.jpg"},
  {"mode":"fallback","port":5174,"submissionId":"实际故障提交ID","visibleStates":["QUEUED","FINISHED","AC"],"screenshot":"browser-fallback.jpg"}
]
```

```powershell
.\tools\validation\Replay-FixedLinux.ps1 -Action Audit -RunDirectory $replayRun -DockerCommand $replayDocker
.\tools\validation\Replay-FixedLinux.ps1 -Action Stop -RunDirectory $replayRun -DockerCommand $replayDocker
```

完整 Audit 必须有八项 matrix、权限记录和两条浏览器记录，且只允许这 11 个提交，缺证据会失败。失败时报告/state 保留，可检查唯一项目；Permissions 的 SubmissionId 参数仅用于继续已验证属于 owner 且仍 QUEUED 的测试任务，避免一次诊断失败额外造数据。Start 的哈希错误会在创建 Docker 资源前退出；清理发现任何未知 managed 沙箱时保守停止，先核对归属，不能强删。

## 6. 实际红/绿与剩余边界

- 首轮 YAML 中 tmpfs 逗号被解析成第二个挂载项，尚未进入业务测试；改为引号内单个挂载参数。
- 空库 MySQL 启动实际失败：Oracle entrypoint 加载初始化脚本，既有 `set -u` 泄漏，后续读取未定义 MYSQL_ONETIME_PASSWORD 退出。初始化逻辑放进子 shell，不改 SQL/grants；随后两个全新空库均正常初始化并通过 Flyway。
- 单独 Bash 回归（mysql 调用替身，不连接数据库）证明加载脚本后调用者 `-e/-u/pipefail` 状态保持原值，非法 secret 仍被拒绝；该检查不替代上面的真实空库/Flyway 验证。
- MLE 首个测试程序的子 JVM `-Xmx512m` 没有触发可信内核 OOM，实际结果 WA。改用已有 MQ 集成测试验证的有界 `-Xmx768m`/384 MiB 程序，新空栈八项全过；不修改分类器，也不放宽预期。
- 初版 Stop 未启用 worker profile，Worker/网络留下并阻止删除镜像；核对精确归属后修复 profile 清理并清空旧测试项目。最终 Stop 全部检查通过。
- Vite 负向 upgrade 直接 ECONNRESET；调整负向探针到真实 API 并保留 Host，同源 owner 正向仍走 Vite。已有 QUEUED 任务继续验证后取消，不把失败请求制造的任务漏掉。
- 初版审计把 MySQL JSON 布尔当作 0/1；按实际 true/false 严格断言后审计全过，没有修改数据库事实。

本轮不重跑前一阶段 158 后端/8 前端测试，使用其已经核验的 Linux JAR；本轮新增证据是分离进程、真实代理/浏览器、八 verdict 和审计/空库部署。JS syntax、PowerShell parser、Bash syntax 与 diff 检查另行通过。

现在第 15 项固定 Linux 构建/故障/真实链路已有分别可追溯证据，但最终门禁尚未逐条正式审计。下一步只处理既定 OPS_ADMIN/DLQ 运维边界及最后 15 项证据核对，不提前 M2。L-029～L-032 的进程会话、尽力日志、保守最终清理、纯 JVM OOM/无审计拒绝操作和普通 Docker 隔离边界仍保留；没有 PR/main 合并/tag/Release、性能数字或云主机安全承诺。
