# M1 固定 Linux 构建与自动化验证

> 日期：2026-10-02，Asia/Shanghai。实现提交：`ab61c9a097a22642866dd719754e5b01a8f46fe3`，分支 `feat/m1-reliable-judging`。M1 仍为 `IN_PROGRESS`；本记录不是完整浏览器纵向链路、云主机验收或 Release。

## 1. 输入与环境

在提交前验证同一工作树，提交未改变输入源码。后端和前端各自从只读 `/source` 复制到一次性 Linux/amd64 容器；排除 `.git`、宿主 `target/node_modules/dist`、IDE 配置、`.env*`、`*.local` 和本地交接文件。不挂载 Maven/npm 缓存，不复用宿主产物。验证后逐项比较 SHA-256，**198 个输入文件与工作树完全一致**，然后提交上述实现；后续收尾只修改文档。

输入文件清单 `source-files.sha256` 本身的 SHA-256：`33fb661271d137f0a910609d9e794c246e64bd4ae0675f8f4afcb2867f0d4114`。本地原始记录位于 `target/forgeoj-linux-20261002-084610-f76a1ccc/`，不纳入 Git，也不承诺永久保留。

| 用途 | 固定镜像 |
|---|---|
| Maven/JDK 构建 | `maven:3.9.14-eclipse-temurin-21@sha256:98819eb3745bd2007c3f1a19b59085c1fa3929aecb7dbfa431dfcf5a4f18ce3c` |
| Node 构建 | `node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c` |
| 验证 Docker CLI | `docker:29.8.0-cli@sha256:f5b8bb0333cfaa027640106e5f02e48b0a8e0c00f7165015f581d58783c76fcf` |
| 用户 Java 运行时 | `eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438` |
| MySQL fixture | `container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be` |
| RabbitMQ fixture | `rabbitmq:4.3.6-management@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95` |

复用已登记的 M0 镜像，没有升级版本或引入业务依赖。实际构建 JDK 为 **Temurin 21.0.10+7**，与用户代码运行时 **21.0.12_8** 区分；Maven **3.9.14**、Node **24.14.1**、npm **11.11.0**。Docker CLI/Engine **29.8.0**，Linux/amd64，cgroup v2，核心为 `6.18.33.2-microsoft-standard-WSL2`。这是 Docker Desktop 的 Linux 容器验证，不能替代 M5 独立 Linux 主机安全/性能验收。

## 2. 复现入口

从仓库根在 PowerShell 执行：

```powershell
.\tools\validation\Verify-FixedLinux.ps1
# Docker 未在 PATH 时显式指定用户安装的 CLI；本轮实际使用：
.\tools\validation\Verify-FixedLinux.ps1 -Scope All -DockerCommand 'C:\Users\Lenovo\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
```

可用 `-Scope Backend` 或 `-Scope Frontend` 单独复验。后端与其他 ForgeOJ 沙箱测试不得并发；脚本发现已有 managed 沙箱即停止，不删除它。每次新建 `target/forgeoj-linux-*` 报告目录；日志、XML、JAR、前端 dist 与输入清单均留在该忽略目录。

后端使用固定镜像内的 Maven 3.9.14 执行 `mvn --batch-mode --no-transfer-progress clean verify`；它不冒充本轮测试过 Maven Wrapper 下载流程。验证容器需要 Docker socket/CLI 来运行 Testcontainers 与沙箱，**这不是生产 API 部署配置**。前端容器没有 Docker socket，执行 `npm ci` 和 `npm run verify`。没有向真实数据库执行 migration、清理或业务写入。

## 3. 新鲜结果

后端于 **08:54:39** 完成，耗时 **08:12**，根/API/Worker reactor 均 `SUCCESS`。独立求和 fresh XML：

| 模块 | XML | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| API | 11 | 48 | 0 | 0 | 0 |
| Worker | 21 | 110 | 0 | 0 | 0 |
| 总计 | 32 | 158 | 0 | 0 | 0 |

包括真实 MySQL/RabbitMQ/Docker，而非只编译：所有者同源 HTTP/WebSocket 与会话失效；配额/取消/租约栅栏/有限重试/死信；关联日志白名单与回滚不误报；V5 新库/旧宽度升级和历史结果保留；六个 cgroup 证据单测、16 个安全程序、八类用户结果映射，以及实际 MQ 的 MLE/SECURITY_VIOLATION 终态、ACK 与重复投递。

`WorkerProcessFaultIntegrationTests` 的 **5 项 Linux 子 JVM 测试全部通过**，输出包含四个 `FAULT_VERIFIED=` 与 `SECURITY_VERIFIED=REAL_WORKER_CREDENTIALS_NOT_VISIBLE_TO_USER_CODE`：领取后 kill/真实租约到期恢复、终态提交但 ACK 前 kill 后重复及乱序吸收、已启动 Worker 回收孤儿、保留其他活跃 Worker 的沙箱及后续恢复、真实 Worker 凭据不可读。这不证明任意网络 ACK 包丢失。

前端于 **08:55 前**完成：安装 292 包、审计 293 包，`npm ci` 报告 0 vulnerabilities；vue-tsc、OxcLint、ESLint、Prettier、2 文件/**8 项 Vitest**、Vite 8.3.1 生产构建全部通过。这里仍是模拟 API/socket 的前端自动化，不是本轮真实浏览器 E2E。

本轮产物 SHA-256（不是 release hash；JAR 重建时间元数据可能不同）：

- API JAR：`236e2352d2d67d1885dbf8905b0e6adb82479c5732c44b64efd326d32b4de650`
- Worker JAR：`60d9f0aa42d143e95a3036984b48096967548d61bb52b0e391ccfa3d3fe46d5b`
- 前端 `index.html`：`6ac38844af2dc8ae6a6cc4da28dfd6dd4d0c3ce67e2271b79a13dc87ad082cb1`

未发现需要修改业务代码的 Linux 差异。`git diff --check` 通过；生产 Worker JAR 不包含故障测试控制类或事件测试类。结束后 Docker 容器清单为空、无 managed 沙箱/故障 JVM，脚本创建的唯一临时构建镜像已删除；只保留本地报告和产物，没有全局 prune。

## 4. Git 与剩余门禁

用户于本轮明确授权按判断提交/推送；实现保存为 `ab61c9a`，Linux 证据作为后续文档提交。仅推送现有 M1 功能分支，不创建完成 PR、不合并 main、不发布。当前 CI 只响应 main push 或 pull_request；功能分支 push 不会触发该 workflow，**不能声称本轮 GitHub Actions 已通过**。

本轮关闭固定 Linux 的干净构建、自动化通知/日志、进程故障与安全/资源分类复验部分；第 15 门禁的完整独立 Vite/API/Outbox/MQ/Worker 链路仍需用本轮 Linux 产物重放，尤其检查真实页面通知、断线轮询、八类结果、日志 ID 串联和 API 无 Docker 权限。OPS_ADMIN/DLQ 运维闭环、最终 15 项门禁审计与发布条件仍未完成。M1、E-01/E-02 均不提升，L-029～L-032 的会话/日志/最终清理/可信分类限制仍保留。

部署新 Worker 之前须由 migrator 应用 V5；纯 JVM OOM 和没有可信审计的被拒操作，仍按 L-032 的明确边界处理。下一步先完成独立 Linux 纵向链路，不重做已通过的 MLE/PID 分类，也不提前实施 M2。
