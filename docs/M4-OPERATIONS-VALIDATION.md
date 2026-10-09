# M4 第4步运维与有限人工恢复验收

2026-10-09，**VERIFIED**。本单元在 `D:\Java项目\ForgeOJ` 的 `feat/m2-accounts` 实施，起点 `02b18fed1ba1c8da9da128f12acba0617c7efcba`。用户已确认 [D-045](ForgeOJ-Decision-Log.md)：原任务终身追加一次执行、原接受时间与当前资格、当前草稿或待审最新验证。完整 M4 仍 **IN_PROGRESS**，本轮完成第4步后停止。

异常任务、执行尝试、Outbox 和恢复历史均提供有界白名单查询；OPS_ADMIN、SUPER_ADMIN 可操作，普通用户及 CONTENT_REVIEWER 拒绝。`/admin/operations` 提供理由、确认、原请求重发、不可变凭证与历史。V20～V22 为追加迁移，API 只读取 attempt 元数据列，Worker 生产实现未修改。

人工执行恢复沿用原任务、原 Submission/快照/用途。默认自动3次耗尽后增加到4次，不重置 attempt_count，不改源码、测试或原接受时间；每任务最多人工追加一次，硬上限10。用户 verdict、SNAPSHOT_INVALID、活动租约、已失效内容/资格和过期或缺失自测 payload 拒绝。自然截止后的原作业提交，真实 AC 按原接受时间计成绩；输出预览不会自动确认输出。投递恢复只重启同一失败未发布 Outbox 事件，保留 payload、发布次数与旧失败事实。

| 门禁 | 本次结果 |
| --- | --- |
| 固定 Linux/amd64 All | API **278/49 suites**、Worker **133/23 suites**，零失败/错误/跳过；前端 **124/24 suites**，类型、lint、格式、测试和构建全部通过 |
| 真实故障与执行恢复 | 同一 JAR 的真实 Worker 先耗尽三次 Docker 创建故障；修复后6个原任务各仅追加第4次执行，三项正式 AC、VALIDATE 双 PASSED、预览正确但未自动确认、自测输出42 |
| 作业接受时间 | 自然硬截止后恢复原提交，成绩仍 ON_TIME_AC，正式作业尝试仍1条 |
| 投递恢复 | 正式事件真实 UNROUTABLE 耗尽5次；恢复同一事件第6次发布，再由 Worker 得到真实 AC，执行仅1次 |
| HTTP/当前授权 | 122项检查，空错误体、no-store、有限角色、同请求重放、请求键冲突、再次恢复拒绝；另有真实MySQL并发/CAS/配额/审计回滚/内容和公共题作业资格测试 |
| 浏览器 | OPS实际恢复、SUPER查询、审核员与普通用户拒绝；HTTP撤销OPS后页面刷新清除任务详情和凭证；旧三次失败和第四次成功可见 |
| 持久事实与权限 | 冻结指纹前后相同、旧attempt字段逐项相同、7条唯一凭证及对应审计；14项真实 SQL 越权拒绝；API 无 Docker/Worker/迁移或维护凭据 |
| 消息与 ACK | 恢复事件发布与持久事实关联；7次真实成功的完成日志先于各自 ACK；4个执行/重试队列 ready=0，7队列 unacked=0 |
| 死信历史 | 保留6条旧死信，正式3、内容2、自测1，与旧失败事件逐用途对应；未清空历史死信制造成功 |
| 构建/运行输入与清理 | API/Worker 实际只读 JAR 与本次构建摘要一致；前端构建、挂载源与实际服务副本一致；本次 containers/volumes/networks/images/builders/Testcontainers/managed sandbox 全0 |
| 用户文件保护 | 两项既定摘要不变，仅 Get-FileHash 核对，没有读取内容、复制或暂存 |

最终构建 `target/forgeoj-linux-20261009-112325-efec5cc0`，完整脚本退出0。重放 `target/forgeoj-e2e-20261009-140259-41c05bc4`，最终核验时间 2026-10-09 14:13 +08。机器结论、所有输入摘要、原始事实、当前压缩构建日志及真实页面见 [证据说明](evidence/m4-operations/README.md)和[verification.json](evidence/m4-operations/verification.json)。不能用旧轮构件替代这次产物。

```powershell
& tools/validation/Verify-FixedLinux.ps1 -Scope All `
  -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
& tools/validation/Verify-M4OperationsReplay.ps1 -Action <phase> -RunDirectory target/forgeoj-e2e-20261009-140259-41c05bc4
# 实际顺序：FaultInstall → Fault → FaultRestore → Recover → DeliveryCreate → DeliveryRecover
# 实际浏览器恢复与角色检查 → BrowserAudit → RevokeOps → 刷新清空 → CaptureRuntime/Audit
# Replay-FixedLinux Stop → CleanupSnapshot；当前一次性栈已删除，不能继续运行旧URL。
& 'D:/Node.js/node.exe' tools/validation/Verify-M4OperationsEvidence.mjs `
  target/forgeoj-linux-20261009-112325-efec5cc0 `
  target/forgeoj-linux-20261009-112325-efec5cc0 `
  target/forgeoj-e2e-20261009-140259-41c05bc4 docs/evidence/m4-operations
```

当前 API JAR SHA256 `0c4ffb2cff2f405c078dd5fc78eed4205a2dfdc1f8a213f750e66da0beb06019`；Worker `f86b269316fb8b779050b6d2dd003b6aceb2907c22522ca48ea358a6cb194942`。重放由显式 BuildDirectory/两项摘要启动，临时交互CLI及HTTP准备测试账号；没有通过UI改密/创建管理员，没有初始化真实管理员或修改真实数据库/SMTP。

保留的失败与修复证据：首轮Linux发现表级SELECT暴露attempt私有列，追加V22限定列授权，保留私有字段拒绝断言；第二轮277项API有旧COUNT(*)拒绝断言与已批准分页相冲突，改为允许计数/元数据并拒绝SELECT */token/worker/message；第三轮因发现公共题作业遗漏当前班级/成员资格而主动中止，先以真实HTTP红测复现409预期却200，再补正式和自测assignment关联。一次夹具错误试图把已开始作业设STOPPED，改为合法CANCELLED，没有放松数据库约束。以上失败/中止构件均未接受，当前278全量独立通过。

首次真实重放已恢复6任务和1投递事件，但工具意外重建API丢失前段日志，明确标 INCOMPLETE_EVIDENCE 后清理，最终完整新栈独立重跑。工具现只启动原Worker且验证API容器ID不变。新栈连续登录命中既有429，等待原300秒窗口并减少无用身份登录，不调整生产限流；原任务和失败事件保留后复验通过。两项事件记录均保留，不把中断算作通过。

范围限制见 [L-042](KNOWN_LIMITATIONS.md#l-042人工恢复的终身额度与死信保留)：单进程管理限流、管理fence、元数据最多50条；没有死信自动清理、集群容量或生产安全/性能结论。Redis/ES/监控及完整M4门禁是第5～7步，M5/Release/RESUME_READY另行验收。本单元随已授权功能分支交付；实际提交及远端一致性以 `git log -1`、`git ls-remote origin refs/heads/feat/m2-accounts` 核对。
