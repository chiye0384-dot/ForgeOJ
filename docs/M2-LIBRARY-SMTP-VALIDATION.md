# M2 题库与 SMTP 适配验收

> 2026-10-03，基线 `feat/m2-accounts` / `36cb6c4`。公开题库与 SMTP 适配单元为 VERIFIED，完整 M2 为 IN_PROGRESS。真实服务商投递尚未验收。

## 1. 范围

公开题库列表、标题字面关键词、难度/标签、稳定有界分页、只读一致统计、按实际 slug 选题，以及切题/退出/迟到请求的页面隔离。V7 仅追加难度/标签模型和最小只读权限，不改 V1～V6 或历史判题依据。旧 `/`、账号路由、题目详情八字段、提交/MQ/Worker 契约保留。

SMTP 适配按模式选择，显式启用、必需 TLS、正常信任和主机校验、配置/地址/链接约束及 socket 超时。正式 JAR 仅有适配代码，测试证书动态生成于 JUnit 临时目录。服务商真实配置、真实邮箱送达和持久邮件重试尚未完成；SMTP 本地协议验收与实际投递严格分别记录。

本单元不表示题目内容/参考程序/审核版本、官方题解、题单/进度、代码草稿、自测或提交历史已经实现。步骤见 [剩余实施计划](M2-REMAINING-IMPLEMENTATION.md)；下一学习记录单元设计见 [设计文档](M2-LEARNING-RECORDS-DESIGN.md)。未创建 PR、合并 main、tag 或 Release，也无性能验收或简历准入变更。

## 2. 局部检查和真实失败记录

- Windows JDK21：SMTP 11 项真实 loopback STARTTLS/implicit TLS/MIME、证书和主机拒绝、认证/收件拒绝、超时与默认禁用检查全部通过。命令 `mvnw.cmd --batch-mode -pl forgeoj-api -Dtest=SmtpAccountMailTests test`，完成 09:08:58（Asia/Shanghai）。
- 题库 7 项新增真实 MySQL/Tomcat 检查及旧账号/详情回归合计 11 项全部通过（09:13:31）；最后分页超界优化后题库 6 项窄复验通过（09:14:32）。SQL 转义、重复标签、参数/404/503 空体、仅 GET 放行、最小权限与 V6→V7 的旧十二表内容保留均覆盖。
- 前端 `npm run verify`：6 文件/24 测试、类型、OxcLint/ESLint、Prettier、Vite 全通过。新增 11 项覆盖 URL/分页/搜索、响应乱序、实际选题提交、切页/退出停止监控、失败退出重试与两位用户本地代码隔离。
- 新迁移测试先暴露测试比较错误：JDBC byte[] 不能以 Map 对象身份比较，改为十六进制内容；migrator 的权限视图不能代表其他账号，改为 API/Worker 各自读取自己授权并实际执行拒绝查询。不扩大数据库权限，不弱化旧内容保留断言。
- 首轮 Linux `target/forgeoj-linux-20261003-091739-9b32c8ec` 在 Docker Desktop 冷启动后的映射端口连接超时，人工停止该 builder，exit143，未验收。第二轮 `target/forgeoj-linux-20261003-092222-3a1d6e95` 错用只证明 TCP 连接的 VM gateway，Ryuk 协议连接失败，未验收。完整 HTTP 对照证明当前正确地址为 `host.docker.internal`，恢复该地址并新增真实 HTTP 预检；只移除本轮两个明确归属的临时探针，没有全局 prune 或修改防火墙。

## 3. 统一固定 Linux 与独立 Replay

最终轮为 `target/forgeoj-linux-20261003-092851-6fec7617`。命令 `tools/validation/Verify-FixedLinux.ps1 -Scope All`（DockerCommand 指向本机 Docker CLI）；真实 HTTP 预检通过。固定 Linux clean verify 于 01:39:00Z 完成：API 118 + Worker 111 = 229，零失败/错误/跳过；SMTP 11 项包含其中。前端于 01:39:15Z 开始，6 文件/24 测试及全部检查通过。环境使用固定镜像和只读源码复制，排除 Git、主机缓存/产物及本地凭据；builder Maven3.9.14/JDK21.0.10，正式用户代码镜像 Temurin21.0.12，分别记录。

精确构件 SHA-256：API `4ef464a287d727068d011677a88bbeab59e77378b2be7a0f2d14b492f2357134`；Worker `ad8042b7572eed75fbb1ac0a0f0abe10fe683e3d26a2952cdfe76ca1475fc718`；输入清单 `b4b3c5a530d51479f787c64fc6085fc196d62e2c96e5b6e0291f81e8972fe6cb`。正式 JAR 不包含 SMTP 测试类、证书或私钥。

独立栈 `target/forgeoj-e2e-20261003-094624-96b5db54` 使用上述精确构件。依次执行 Replay 的 Start、Library、Matrix、WorkerStop/Start、Permissions、Audit、Stop。匿名真实 HTTP 检查列表/组合筛选/字面百分号/400/字段白名单；真实浏览器从第一页翻到第二道原创题，筛选后按实际 slug 进入作答，提交 `bf7bd19b-6de2-4422-afaa-6079205c8879` 到 FINISHED/AC。旧 `/` 入口提交 `441eaf8f-ff55-4b74-9e25-d6c26f04cb1d`，阻断 WebSocket 后轮询到 AC。DB 事实另核对两个 UUID 分别绑定两数较大值/两数之和及 judgeVersion=1。

01:53:16.042Z 审计通过：八 verdict、11 个提交（10 完成/1 取消）、11 个已发布 Outbox、4 个空队列，关联日志链、所有者/Origin/退出撤销及 API/Worker 最小权限。Stop 成功移除精确归属资源；Docker 重启后 02:17:09Z 又逐项检查命令退出码，确认自有容器/卷/网络/Worker 镜像、Linux build 镜像、Testcontainers 与 managed sandbox 均为零，无全局 prune。脱敏结果和三张实际浏览器截图见 [证据](evidence/m2-library/README.md)。

第一次 Replay `094032-90837a68` 未通过：mysql fixture 客户端默认 latin1，使中文标签失真。停止该栈后修为 `--default-character-set=utf8mb4`，重放通过。该修正及 DB 导出 slug/version 只改变 Replay 脚本；192 个后端/前端/测试/迁移/契约输入仍与已构建源码完全一致。最初 205 项全匹配记录在修改前；修改后的记录明确列出唯一变动工具 `Replay-FixedLinux.ps1`（8cffc2d2… → 330c9c58…），不能把当前工具也称作 205 项全部匹配。其余 12 个工具未变，最终重放实际执行修正脚本。

Replay Start 不再默认复用历史 M1 构件，必须提供 `BuildDirectory` / `ApiSha256` / `WorkerSha256`；新增 `Library` 动作读取真正 Servlet HTTP 接口并保存脱敏结果。`check-build-inputs.mjs` 比较构建清单中实际后端/前端/测试/工具输入，文档后续补证允许独立变化。
