# M1 沙箱恶意代码验证与清理修复

> 后续状态：累计实现已提交为 `ab61c9a`，资源分类已补齐可信 cgroup 范围且固定 Linux 16 项安全程序通过；见 [资源结果](M1-RESOURCE-VERDICT-VALIDATION.md)与[Linux 记录](M1-FIXED-LINUX-VALIDATION.md)。下文为安全阶段初次验证，保留当时缺口。

> 测试日期：2026-10-01；中断后的证据核验与文档收尾：2026-10-02。基于 `feat/m1-reliable-judging`、HEAD `ceee82c` 的未提交工作树。范围为 Windows Worker + Linux Docker Desktop，不是固定 Linux M1 最终验收；M1 仍为 `IN_PROGRESS`。

## 1. 范围与环境

使用生产 `DockerCliSandboxRuntime`、固定 Temurin 21 digest、实际 Docker Engine 29.8.0 Linux/amd64 与 cgroup v2。新增程序均为原创、有界测试，只在受限容器中运行，不接触真实业务数据。真实 Worker 凭据检查复用隔离 MySQL/RabbitMQ fixture 和独立子 JVM，凭据为测试 dummy 值。

本阶段不增加依赖、宿主挂载、容器 capabilities、数据库授权、迁移、HTTP/MQ 字段或镜像变更。API 仍没有 Docker 控制能力。环境和版本基线沿用 `M1-FAULT-RECOVERY-VALIDATION.md`。

## 2. 先失败，再修复

以下时间均为 2026-10-01、Asia/Shanghai：

- 21:15:29：最初两项测试为 1 failure、1 error。UID 65532 创建的 mode 700 临时目录无法由 cap-drop ALL 的 root 清理；后台子进程也能残留。
- 21:16:59：清理改为资源所属用户后，临时文件测试通过，子进程测试仍失败。普通 sleep 作为 PID 1 不回收退出的后代；补启用 Docker init。
- 21:20:09：九项中八项通过，PID 测试因 HotSpot 额外 stdout 警告得到 WA。改为核对实际 Java exit 0、BOUNDED 标记以及内核 `pids.events` 的非零 max 计数；不删除资源断言。
- 21:21:30：全局 `/tmp` 文件跨用例测试失败，复现仅删除 case 目录的遗漏。修为全部用户所有的顶层临时资源清理，并测试 mode 000 目录和符号链接。
- 21:25:25：12 项安全程序与 1 项真实 Worker 凭据检查通过。
- 21:28:51：新增 `/dev/shm` 写入测试失败，确认额外共享内存区域仍可写。随后设置 `--ipc none`，并增加清理后 pgrep 核验，残留时禁止继续下一例。
- 21:31:07：13 项安全程序 + 13 项 runtime 单测 + 1 项真实 inspect 共 27 项通过。再补非协作后代的 TLE 清理测试，纳入下述全量验证。

## 3. 生产修复

1. 启用 `--init`：由 Docker 提供的 init 回收孤儿/僵尸，不赋予用户额外权限。[Docker 官方说明](https://docs.docker.com/engine/containers/multi-service_container/)
2. 使用 `--ipc none`：不挂载 `/dev/shm`，避免它成为未清理的跨用例存储区；真实 inspect 同时核对 IpcMode 与 Init。[Docker run IPC 文档](https://docs.docker.com/reference/cli/docker/container/run/)
3. 以 UID 65532 执行同 UID SIGKILL；由受控 UID 65534 的 pgrep 验证用户进程清空，最多五轮。控制失败或仍有进程则抛平台错误，由外层按完整容器 ID 删除，不运行下一例。
4. 进程清空后，只对 `/tmp` 下 UID 65532 的真实目录恢复所有者权限，再移除该 UID 所有顶层文件/目录/链接。固定 find 参数不遍历链接，递归 chmod 不跟随遇到的嵌套链接；测试验证源文件权限未被改变。所有命令使用参数数组，无 shell 拼接。

root 身份不等于拥有越过所有文件权限或向其他 UID 发信号的能力，尤其在 capabilities 被清空时；本轮选择匹配资源所有者，而非增加 CAP_KILL/CAP_DAC_OVERRIDE。[Linux capabilities](https://man7.org/linux/man-pages/man7/capabilities.7.html)

## 4. 新增安全矩阵

`DockerSandboxSecurityIntegrationTests` 共 14 项；每项结束检查自己 task 标签下所有容器（含已停止）为空，并检查源码没有进入 Docker 参数。

| 场景 | 实际断言 |
|---|---|
| 用例目录文件 | 第一例创建嵌套文件，第二例看不到上一例目录 |
| 后台后代进程 | 第一例留下 sleep，第二例 `/proc` 不存在 UID 65532 的旧 sleep |
| 根目录与编译产物 | 写 `/`、`/etc`、Main.java/class 和新 class 均被拒绝 |
| 共享内存 | `/dev/shm` 文件写入被拒绝 |
| 提权与进程限制 | 实际 UID 65532、CapEff/CapBnd=0、NoNewPrivs=1、Seccomp=2，su 不能切 root，无 Docker socket |
| 禁网 | 无活动的非 loopback 接口，连接文档测试地址 192.0.2.1 失败 |
| JVM 堆 | 有界分配触发 OutOfMemoryError，而非无限分配宿主内存 |
| PID 限制 | 有界创建子进程失败，`pids.events` 确认 max 计数非零；此程序自行终止已创建子进程 |
| 非协作超时 | 无限循环加八个 sleep 后代得到 TLE，精确 task 的容器最终清零 |
| 全局临时文件 | mode 000 嵌套目录、顶层与嵌套源码符号链接、case 目录外文件均无跨例残留，源码权限不变 |
| 临时磁盘 | 有界 32 MiB 写入在 16 MiB tmpfs 限制下失败且清理成功 |
| cgroup 内存 | memory.max=192 MiB、swap.max=0；超额子 JVM 被杀，memory.events 有 oom_kill 非零证据 |
| 其他提交源码 | 另一活跃容器确实存有私有哨兵文件，本提交中该路径不可见，无宿主/socket 挂载 |
| 隐藏数据 | 工作目录仅源码和 class；首例 WA 后不传第二例输入；捕获全部控制命令 stdin，不含期望答案和未来输入 |

另有 `WorkerProcessFaultIntegrationTests#untrustedProgramCannotReadCredentialsFromItsRealWorkerProcess`：真实子 Worker 环境含测试 DB/MQ 凭据，容器内程序看不到 SPRING_/FORGEOJ_ 环境、不能读 `/proc/1/environ`、无 Worker 配置路径或 Docker socket，原判题数据仍能获得 AC；日志不含凭据。此前四项故障测试一并回归，因此该类现在为五项，不改写上一阶段四项的历史记录。

## 5. 复现与结果

仓库根 `D:\Java项目\ForgeOJ`，运行前保持当前 daemon 无其他 ForgeOJ 沙箱测试并发执行；需要固定镜像、可用 Linux Docker 与 cgroup v2。

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-judge-worker '-Dtest=DockerSandboxSecurityIntegrationTests,DockerCliSandboxRuntimeTests,DockerCliSandboxRuntimeIntegrationTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

根 `clean verify` 于 **2026-10-01 21:39:40** 完成，耗时 7:40，**BUILD SUCCESS**。当时已包含最终 14 项安全程序；10 月 2 日恢复会话后取得原进程 exit 0 并重新对 XML 求和，没有将旧定向报告拼成全量结论。

| 模块 | fresh XML 数 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| API | 11 | 46 | 0 | 0 | 0 |
| Worker | 20 | 98 | 0 | 0 | 0 |
| 合计 | 31 | 144 | 0 | 0 | 0 |

六类已有判题结果、真实 Worker 故障恢复、配额/租约、API 通知与日志均通过。报告在两模块 `target/surefire-reports`。10 月 2 日只读复核管理沙箱和 FaultWorkerProcess 子 JVM 均为 0；生产 Worker JAR 不含故障控制类或安全测试类。

本阶段没有改前端，因此不重复前端构建；最近一次前端 8 项及全部门禁通过的证据仍为上一阶段记录，不称为本阶段新跑结果。

产物 SHA-256：

- API JAR：`53f65fb2ec373a667af33177d756c4a3312825482c0ace80c5462650fcaa6966`
- Worker JAR：`d14cbd1d57e9cb026487dbbfaadf770a6155ce54b3895848a707ef8668cb353a`

## 6. 未关闭的边界与下一步

- **本报告阶段 MLE 和 SECURITY_VIOLATION 尚未贯通**：当时 Worker 生产映射为原六类；更正：V3 CHECK 已允许两类结果，但 verdict 列只有 16 字符，安全结果长度不够。后续 2026-10-02 的可信计数识别与 V5 宽度修复另见 `M1-RESOURCE-VERDICT-VALIDATION.md`，不覆盖本报告的历史测试数和产物。
- 有界 PID 压力和非协作后代清理不等于任意递归 fork 风暴覆盖；不证明内核逃逸安全。普通 Docker 仍是受控小规模边界。
- 本次是 Windows 控制进程 + Docker Desktop；固定 Linux M1 构建、完整通知/故障/进程链路重放、OPS_ADMIN 与最终门禁仍未完成，M5 主机安全/性能验收也不关闭。
- 未提交、推送、创建 PR 或发布；不改变 E-01/E-02 和 M1 状态。限制登记为 L-032，当前设计见 M1 第 5.2 节。
