# M1 可信资源结果与安全 verdict 存储验证

> 日期：2026-10-02，Asia/Shanghai。范围：Windows API/Worker 与 Linux Docker Desktop/cgroup v2，隔离 MySQL/RabbitMQ。分支 `feat/m1-reliable-judging`、基于 HEAD `ceee82c` 的未提交工作树。M1 保持 `IN_PROGRESS`。

## 1. 问题与最终行为

上一阶段只证明资源约束触发：父程序捕获子 JVM OOM 后返回正常输出，可能得到 AC；PID 上限触发后的 JVM 警告可能使结果变成 WA。现在用内核产生的逐例增量作为判定，不依赖用户 stderr、退出码或自写标记。

每例执行前，受控 UID 65534 通过固定 cat 路径和完整容器 ID 读取 `memory.events`、`pids.events`；用户 JVM 退出并由可信控制清空 UID 65532 进程后再次读取。解析必须键完整、值非负且单调。源码不能写入这些只读内核文件，真实程序测试同时尝试写入并确认失败。

| 证据 | 判定 |
|---|---|
| pids.events:max 比执行前增加 | SECURITY_VIOLATION |
| memory.events:oom 比执行前增加 | MLE |
| 单独 oom_kill 增加，局部 oom 不增加 | 平台故障，无法证明本任务内存限制导致 |
| 只有 memory.events:max/high 压力 | 不据此判 MLE |
| 无新内核事件，程序打印 OOM/安全文本并退出 137 | RE |
| 读取失败、键缺失/重复、负数/溢出、计数回退 | 平台故障 |

Linux 官方文档定义 oom 为到达限制且分配即将失败，oom_kill 则包含任意 OOM killer 的受害进程；PID max 计数表示达到进程数限制。[cgroup v2 内核文档](https://docs.kernel.org/admin-guide/cgroup-v2.html)支持上述区分。先后快照避免把编译期/先前用例的旧计数当成新违规。用户代码可以自选退出码，因此退出 137 不独立证明内存超限。[Java 21 运行器文档](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html)

同例优先级固定为：平台控制/证据失败 → PID 安全违规 → 局部 cgroup OOM → OLE → TLE → RE → 输出比较。资源违规一律停止后续用例，写 FINISHED/SUCCEEDED；不发重试 Outbox，ACK 仍在事务完成后。父程序恢复并打印正确答案不能抵消已经发生的内核违规。

## 2. 数据兼容与部署

更正上一安全报告：V3 CHECK 已允许 MLE 和 SECURITY_VIOLATION，并非约束仍只有六类；真正的存储缺陷是 `verdict VARCHAR(16)` 装不下 18 字符的 SECURITY_VIOLATION。

V5 仅扩宽为 VARCHAR(32)，V1–V4 不修改。新鲜 Flyway schema 与所有手工 Worker fixture 都应用 V5；增加测试重建旧宽度条件，先证明安全结果无法写入，再执行 V5，核对已有 AC/statusVersion/finished_at 完全保留且安全结果可查询。

部署先由 migrator 执行 V5，再运行新 Worker；本轮仅对隔离 fixture 执行，未改真实业务数据库。没有新库、依赖、镜像 digest、权限、消息字段或接口字段。API 现有所有者 GET 读取结果，仍只返回五个字段，两类资源诊断均为空；WebSocket 不包含 verdict。前端按字符串显示已有 GET 结果，本阶段未改前端。

## 3. 先失败再修复

- 08:12:41：首次定向测试在镜像前置检查失败，原因是 Docker Desktop Linux 引擎未启动；启动既有环境后再跑，这不算分类红测试。
- 08:14:42：三项真实程序测试中两项失败：内核 OOM 实际得到 ACCEPTED，PID max 得到 WRONG_ANSWER；伪造资源文本/退出码一项保持 RE。
- 08:16:13：补入可信内核增量分类后，三项程序与五项计数单测共八项通过。随后再补“只有宿主 OOM kill 不是本地 MLE”的单测和实现检查，由最终构建验证。
- 08:21:22：四组共 40 项定向验证出现一 failure、一 error。16 项安全程序通过、真实 MQ MLE 通过；安全 verdict 的事务写回出现 `Data too long for column 'verdict'`，相应消费测试无法等到 FINISHED。新增 V5 扩宽及迁移保存历史结果测试。
- 08:25:03：首轮根构建 API 48 项中只有既有 V3→V4 quota 升级测试失败，它原来断言无目标版本的 migrate 只执行一项；增加 V5 后实际执行两项。将这个专门测试的目标显式固定为 V4，保持原 quota/账号不变断言。两项新查询/宽度升级测试已通过；重新进行全量验证。

## 4. 自动化覆盖

- `SandboxResourceEventsTests`：六项覆盖键校验、递增与旧计数、同时触发优先级、回收事件、宿主 OOM 与回退计数。
- `DockerSandboxSecurityIntegrationTests`：现在 16 项，原十四项保留；PID/OOM 从只验证 containment 改为真实 verdict，新增伪造文本/退出码及写内核事件拒绝、读取证据失败的精确容器清理。
- `JudgeTaskCompletionIntegrationTests`：八类用户 verdict 原子写入三表，资源结果无诊断、日志只记录受控 verdict。
- `JudgeTaskExecutionIntegrationTests`：真实 RabbitMQ/MySQL/Docker 两类资源结果、一次 attempt、SUCCEEDED、ACK/队列排空、重复投递不改终态且不插重试事件。
- `SubmissionCreationIntegrationTests`：所有者 GET 两类结果的精确字段白名单与诊断隔离；旧宽度失败、V5 保存既有结果后启用安全 verdict。
- `MySqlMigrationIntegrationTests`：新鲜 Flyway schema 的 CHECK、列长度与原数据库角色边界。

## 5. 复现及全量证据

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -pl forgeoj-judge-worker '-Dtest=SandboxResourceEventsTests,DockerSandboxSecurityIntegrationTests,JudgeTaskCompletionIntegrationTests,JudgeTaskExecutionIntegrationTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

最终根 `clean verify` 于 **2026-10-02 08:34:50** 完成，耗时 **8:32**，**BUILD SUCCESS**。fresh XML 独立求和：

| 模块 | XML 文件 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| API | 11 | 48 | 0 | 0 | 0 |
| Worker | 21 | 110 | 0 | 0 | 0 |
| 总计 | 32 | 158 | 0 | 0 | 0 |

报告位于两模块 `target/surefire-reports`；原六 verdict、五项子 Worker 测试、16 项安全程序、六项事件校验与八类结果原子写回均回归。结束后只读检查管理沙箱与 FaultWorkerProcess 子 JVM 都为 0；生产 Worker JAR 不含这些测试控制类或事件测试类。`git diff --check` 通过。

产物 SHA-256：

- API JAR：`dc95a74443a1454abd7b45da2fb25c74a3e805b4c8f178ca8d372157673f42df`
- Worker JAR：`053083d5b0a0ecd31245fcd407e1438a6f77802a887f99fcb27f95b9e4da4450`

本阶段前端未改、未重跑；沿用前阶段的 8 项记录，不称为本阶段新增验证。这里只记录 Windows/Docker Desktop 结果，固定 Linux 与完整真实浏览器重放尚待进行。

## 6. 判定的实际范围

MLE 仅承诺有可信局部 cgroup oom 证据的超限；纯 JVM 堆或 metaspace 的 OutOfMemoryError 没有这类事件时不靠异常文本猜测：捕获后可按输出 AC，未捕获为 RE。PID max 是当前可审计的安全违规；拒绝联网、写只读路径、提权不自动产生 SECURITY_VIOLATION。

cgroup v2 是环境要求。用户子进程挤满 PID、init 被 OOM 杀死、daemon 不可用或证据无法取回时，控制失败可导致平台重试/最终 SYSTEM_ERROR；不能承诺任意 fork 风暴都会准确分类。普通 Docker 的边界、固定 Linux M1/M5、OPS_ADMIN 与最终门禁仍待验收，见 L-031/L-032。没有提交/推送或发布。
