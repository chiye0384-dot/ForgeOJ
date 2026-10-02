# M1 所有者通知与轮询恢复：局部验证记录

> 后续状态：累计实现已提交为 `ab61c9a`，2026-10-02 固定 Linux 自动化/进程故障复验通过；见 [Linux 记录](M1-FIXED-LINUX-VALIDATION.md)。下文保留通知阶段当时的 Git 状态和验证范围。

> 日期：2026-09-30 至 2026-10-01（Asia/Shanghai）
> 分支：`feat/m1-reliable-judging`；实现起点 `ceee82c4904fd8a64be1eda9558ed96482cfdc81`
> 当前实现位于该分支工作树，本步尚未提交/推送；当前 HEAD 仍为上述起点。M1 整体仍为 `IN_PROGRESS`，不是固定 Linux 验收、PR 合并或 Release。

后续同日关联日志阶段已完成本地实现与 API 46 + Worker 67 全量回归，见 [可观测性记录](M1-OBSERVABILITY-VALIDATION.md)。下文保留通知阶段原始结果与当时剩余工作，不拿后续结果改写历史。

## 1. 用户确认的合约与实现入口

- D-039 已由用户确认：原生 WebSocket `/api/v1/submissions/{submissionId}/events`，现有登录会话、仅本人、严格同源。
- 通知精确三个字段：`submissionId/processingStatus/statusVersion`；不含源码、verdict、诊断、凭据或隐藏测试。实际结果由原所有者 GET 读取。
- `SubmissionHandshakeInterceptor`：匿名 401；他人/缺失/非法 UUID 统一 404；缺失、null、跨源或非法 Origin 403。不提供跨源白名单或 URL 会话令牌。
- `SubmissionMapper.findNoticeByOwner`：三字段 SQL 投影，在查询中同时限制所有者与 ACTIVE 账号，不加载源码或诊断。
- `SubmissionWatch`：只保留服务端会话引用，每次通知复核原会话中的用户身份；失效/登出/禁用停止通知。
- `SubmissionNotificationHandler/SubmissionWebSocketConfig`：有界活动订阅、首次已提交快照、版本去重、终态/错误/关机释放。默认全局 20、每用户 2 连接；超额关闭 1013；客户端消息关闭 1008；终态正常关闭 1000。
- `submissionMonitor.ts` 与 `JudgeWorkspaceView.vue`：推送只触发 GET；重复/旧/他人/额外字段通知不渲染；迟到的较低版本 GET 不覆盖页面；轮询、单请求在途、有限重连与清理。
- `vite.config.ts`：`/api` 代理开启 `ws`，保留 Host，不重写 Origin。未全局信任任意转发头，也未添加前端 socket 包。
- 本轮只增加 API 的 Boot WebSocket Starter；未改 Worker、数据库 migration、消息状态机或 API 的 Docker 权限。依赖版本、许可证与归属见 U-008、直接依赖清单和 OWNERSHIP。

## 2. 可复现命令与结果

环境：Windows PowerShell、Java 21.0.12.1、Maven Wrapper 固定 Maven 3.9.14、Node 24.14.1、npm 11.11.0、Docker Desktop Engine 29.8.0（Linux x86_64）。测试只使用 disposable Testcontainers，未修改真实数据库。

固定 MySQL、RabbitMQ 与 Temurin 镜像引用沿用 `M1-QUOTA-CANCELLATION-VALIDATION.md` / 直接依赖清单；MySQL 为 8.4.12，RabbitMQ 为 4.3.6，使用既有固定 digest，不退回浮动版本。

后端根目录：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

2026-09-30 22:46:12 首轮全量通过：API 37、Worker 57，Failures/Errors/Skipped 都为 0。2026-10-01 补充跨用户全局连接上限单元测试后，在 Docker 就绪后重跑，14:21:47 完成 `BUILD SUCCESS`：API **38**、Worker **57**，Failures/Errors/Skipped 都为 **0**。对本轮重新生成的 8 个 API、15 个 Worker XML 报告逐项求和核验，两个 JAR 均重新构建，不使用上一轮保留报告冒充本次证据。

本地原始报告在两模块 `target/surefire-reports/TEST-*.xml` 与 `.txt`，不提交构建产物，后续 clean 会覆盖。构建产物 SHA-256：

- API：`83391e49d7ae0a9dd3b8b2cb66c0dface0e85ecec2e044a8a515c065388f469a`
- Worker：`0b7286e539d02c421348e87e67ac715fce91fec2b40a1bfa9fd07d9d23e10375`

全量测试后只读检查 `docker ps -a --filter label=com.forgeoj.managed=true`，结果为空，无 ForgeOJ 管理判题容器残留。没有执行 prune、删除现有业务数据或清理其他项目资源。

前端目录：

```powershell
npm run verify
```

2026-10-01 14:11 开始的最终前端检查成功：类型检查、OxcLint、ESLint、Prettier、Vitest 和 Vite 生产构建全部通过；2 个测试文件、8 项测试，无跳过。包含原 M0 登录/读题/提交/AC 页面测试与 7 项新通知监视器测试，不是实际浏览器连接到运行中的 Worker 的 E2E。

前端产物 SHA-256：`dist/index.html` 为 `6ac38844af2dc8ae6a6cc4da28dfd6dd4d0c3ce67e2271b79a13dc87ad082cb1`；`dist/assets/index-D-QIMhg5.js` 为 `dc4d7bfb7a3fe996883536428bfbbf8aca042380a94367fda3b6897ffa1763df`。构建目录不提交 Git。

依赖核验：

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-api dependency:tree '-Dincludes=org.springframework.boot:spring-boot-starter-websocket,org.springframework.boot:spring-boot-websocket,org.springframework:spring-websocket,org.springframework:spring-messaging,org.apache.tomcat.embed:tomcat-embed-websocket'
```

2026-10-01 14:15:00 成功：Boot WebSocket Starter/模块 4.1.1、Spring WebSocket/Messaging 7.0.9、Tomcat WebSocket 11.0.24。固定缓存 POM 许可证已复核为 Apache-2.0；不是完整 Release 许可证审计。

## 3. 新增行为证据

| 检查 | 测试方式与观察 |
|---|---|
| 通知白名单和正常终态 | 真实随机端口 Tomcat + JDK HTTP/WebSocket + MySQL：QUEUED v0、RUNNING v1、FINISHED v2，只三字段；CE/诊断 sentinel 不推送，GET 可读实际 CE；终态关闭 1000 |
| 不重复相同快照 | 真实连接在状态未变时不再收帧；单元测试版本 4/4/2/5 只发送 4/5 |
| 匿名、越权及 ID 隐藏 | 真 HTTP 握手匿名 401，他人/不存在/非法 UUID 同 404 |
| 同源 fail closed | 真握手缺 Origin、null、跨源、带路径均 403 |
| 登出与禁用 | 真 CSRF logout 或账号禁用后已建连接关闭 1008，不继续泄露状态 |
| 只读订阅 | 真连接发送命令后关闭 1008，不能切换到他人提交 |
| 连接限额与取消 | 真连接超额关闭 1013；HTTP cancel 后两个已建连接收到 CANCELLED v1 并关闭 |
| 名额与资源清理 | 单元测试覆盖不同用户隔离、独立全局上限、失效会话/关闭释放名额、关机释放连接 |
| 数据库与发送失败 | 单元故障注入关闭 1011、释放 watcher，无错误内容帧或无限扫描重试 |
| 通知乱序与实际结果 | 前端忽略重复/较低版本/其他提交；只从 GET 应用实际 terminal verdict |
| HTTP 与推送交错 | 先见通知 v4 后迟到 GET v1 不显示；后续 GET v4 恢复终态 |
| 断线与暂时故障 | socket 不可用仍轮询到 CANCELLED；暂时 GET 错误继续，401 停止并清理 |
| 不可信通知 | 非 JSON、额外诊断字段、非法版本忽略，不渲染推送诊断 |
| 有界重连与卸载 | 初次 + 最多 3 次重连（2/4/8 秒）；耗尽仍轮询；停止后迟到 HTTP/socket 回调不更新页面、定时器归零 |

后端连接测试的状态变化来自受限 Worker SQL fixture，取消来自实际 HTTP；它验证通知读取已提交 MySQL 状态，不是独立 Worker 进程驱动全部 verdict 的 Linux 重放。

## 4. 失败处理与证据边界

- 红绿基线：接口未实现时，2026-09-30 两项聚焦测试失败（成功握手得 404；应拒绝跨源 403 实得 404）；实现后 6 项真实连接测试通过。
- 前端最初单独测试受沙箱 `.vite-temp` 访问影响，正常授权执行后通过；后续全量门禁发现测试 Array.at 不兼容当前目标库、mock 类型/链式写法与断言风格不符。只改测试代码，不升级目标库、削弱规则或删测试。
- 2026-10-01 首次后端重跑因 Docker Desktop 未启动失败：API 6 个容器类无法启动，Worker 模块未执行；这不是跳过后计成功。检查不存在的 Engine 管道后，使用官方 `docker desktop start --detach` 启动，确认 Engine 29.8.0/Linux，再重跑同一完整命令；未 reset、prune 或删除 Docker 数据。
- 通知是有界活动订阅采样（默认 500ms），可能合并中间状态，没有逐事件必达或历史回放。前端轮询默认 1 秒；这些配置不是延迟、QPS 或容量实测。
- 同步发送 timeout 已按固定 Tomcat 源码核验；未进行慢浏览器负载、多节点会话、代理部署或通知容量压测。
- 未执行 M1 的实际浏览器/独立 Worker/固定 Linux 全链路重放；更宽崩溃/ACK 丢失与恶意代码矩阵、关联脱敏日志、运维闭环和最终 M1 门禁仍待完成。
- M1 与 E-01 不提升到 `VERIFIED/RESUME_READY`，不创建完成 PR、不合并 main、不打 tag/release。
