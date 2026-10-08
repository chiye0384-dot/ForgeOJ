# M3 班级私有题单元验收

2026-10-08 总门禁补充：完整M3现已 [VERIFIED，4/4 PASS](M3-GATE-AUDIT.md)。本文件以下保留该单元执行时的范围、结果及失败轮次；最终整体状态以总门禁为准，不将各轮次重新描述为同一次运行。

状态 **VERIFIED（班级私有题单元）**；2026-10-06；起点 `feat/m2-accounts / 089d777`。本单元验收与完整 M3 分开，完整 M3 保持 IN_PROGRESS。设计见 [M3-PRIVATE-PROBLEMS-DESIGN.md](M3-PRIVATE-PROBLEMS-DESIGN.md)，可提交证据见 [证据目录](evidence/m3-private-problems/README.md)。

## 实现和用户确认

V16 追加 PUBLIC/CLASSROOM、真实班级外键、不可变发布绑定及个人提前查看记录。OWNER/ASSISTANT 从本人当前双通过验证快照发布新私有题；发布内容归班级，修订复制成维护者自己的新草稿并重新验证。学习入口、教学维护、公开入口和本人执行记录分别复核当前权限。正式提交沿用原 submission/task/outbox/Worker 和共享额度，自测不制造正式 AC。

本轮按用户明确确认补充 D-014：自由练习默认 AFTER_AC；未 AC 时显示提醒，取消不写记录，再次确认后仅本人提前查看。作业策略留到后续作业作用域，不得用自由练习确认绕过。

## 最终构建和真实回放

| 检查 | 结果 | 本地原始记录 |
| --- | --- | --- |
| Linux 后端完整构建 | API 203、Worker 133，零失败/错误/跳过，BUILD SUCCESS | `target/forgeoj-linux-20261006-174426-4e8dec91` |
| Linux 前端完整检查 | 82 项 / 16 suites；类型、lint、格式、生产构建通过 | `target/forgeoj-linux-20261006-173953-2091fbee` |
| 私有题 HTTP | 三账号 155 项；3 次真实双程序 PASSED | `target/forgeoj-e2e-20261006-190704-e70d4e52/private-problems-http.json` |
| 私有题实际页面 | 负责人发布；成员无维护入口；取消/再次确认；自测 SUCCESS；正式 AC；移除后清空 | 证据目录 `private-browser-*.txt/png` |
| 私有题链路审计 | 3 私有题冻结摘要/资源/压缩测试一致；3 验证任务；WA/AC/AC 三正式任务；2 自测；2 个人提前查看；13 实际权限拒绝 | `private-problems-audit.json` 与对应 SQL facts |
| 公共回归 | 八 verdict、题库/学习流、取消；正常通知和阻断通知后轮询均 AC；11 提交/10 完成/1 取消；38 实际 SQL 拒绝 | `audit.json`、`browser.json`、`privilege-denials.json` |

API JAR SHA256：`fd05d8fcb0f7e3c13de4f1e39956f91098392e7fe6fdea4f921fbecd8cba91bf`。

Worker JAR SHA256：`b000502ca68ddbefa67a9255275481950e7b1151c0abd4e9ebc80c263689412b`。

验收使用上述同次后端构建的实际 JAR，不沿用旧单元运行构件。最终240个后端源码及测试输入、62个前端构建与浏览器实际运行文件、工具摘要、Surefire XML、运行 JAR、清理结果由 `Verify-M3PrivateEvidence.mjs` 逐项关联，全部通过，详见 `verification.json`。2026-10-06 19:20:52 +08:00 live daemon 核查本次容器/卷/网络/镜像、builder、Testcontainers、managed sandbox全为0。用户未跟踪旧交接和忽略SMTP文件摘要保持不变。

实际 Worker 对三个原创候选完成 FINISHED/PASSED，参考和独立题解均 ACCEPTED，且各有一条关闭成功 attempt、已发布 Outbox 与 commit-before-ACK。API 集成测试里的 PASSED fixture 仅用于发布谓词测试，未作为真实执行验收证据。正式 WA/AC/AC 同样核对原共享执行链路。自测分别输出 `15\n`、`11\n`；浏览器接受后把输入改成 `100 200`，结果仍是原 `5 6` 对应的 11。取消提前查看前后数据库均总数1、浏览器新题0；明确确认后只新增用户2记录，用户1仍无提前查看。成员移除通过实际负责人 HTTP，刷新后的 DOM 无私有内容。

退出/移除后私有资源拒绝，本人原提交结果保留，全部历史隐藏班级当前标题/链接。PUBLIC 详情、题解/提前查看、正式提交、题库、题单和跨班入口不能旁路。助教退出不改变班级题，个人原草稿变更不改变冻结快照。归档后新执行/提前查看拒绝，实际引用及 FK 禁止删除。Worker 没有新增班级表权限；API 对正式隐藏测试仍无 SELECT 权限。

日志哨兵检查无源码、参考、独立题解、测试密码、Cookie/CSRF泄漏。七队列 ready/unacked 均0；实际 request→submission→task→Outbox→attempt→commit→ACK 关联通过。原始日志留在 target，不提交凭证或原始运行日志。

## 失败和诊断记录

- Windows 首次前端测试受限临时目录 EPERM，16 suites 未执行；不能记为通过。正常临时目录访问后新增7项通过，最终 Linux 全量82项通过。
- 首次 Docker 引擎未运行；启动已安装官方 Docker Desktop 后继续。第一次 Linux API 新测试因错误题解路径及需要 SUPER 的触发器注入失败；改为实际正确路径和 Mockito 中途异常注入，未降低业务断言，最终203项通过。升级测试保留全部旧业务事实。
- 后端最终 Maven 用时约1小时19分钟。Worker 进程故障5项全部通过，其中该 suite 耗时约3705秒；RabbitMQ连接重试和 API fork 退出30秒警告保留，最终 reactor 与包装脚本成功，不声称无警告。
- 公共权限审计第一轮仍预期禁止修改 problem.difficulty 返回1142。V16只授权更新 status/current_judge_version_id 后，MySQL 对无权更新 difficulty 实际返回列级1143；同步错误码预期后原拒绝断言和38项全部通过。
- 最终证据关联工具第一轮未去除Vitest输出的ANSI颜色码，文本计数匹配失败；只剥离颜色码后，仍严格要求16 suites/82 tests、构建成功及完整输入相同，全部通过。
- Stop 已成功按拥有权移除本次容器/卷/网络/Worker镜像。随后 Docker Desktop 进程与引擎处于关闭状态，最后只读 daemon 核查无法执行；启动官方 Docker Desktop 后再核对实际零残留。没有调用关闭 Codex 或关闭 Docker Desktop 命令，退出原因未确认。

本轮未新增生产 Worker SIGKILL 回放或 QQ 外部邮件投递；全量故障测试不冒充生产回放。本轮没有依赖升级、公共审核发布、PR/main/tag/Release 或 RESUME_READY。

## 复核与边界

```powershell
node tools/validation/Verify-M3PrivateEvidence.mjs target/forgeoj-linux-20261006-174426-4e8dec91 target/forgeoj-linux-20261006-173953-2091fbee target/forgeoj-e2e-20261006-190704-e70d4e52 docs/evidence/m3-private-problems
```

每班最多1000条永久发布记录（含归档）。每次修订发布独立新题及 judge version 1；未实现同题覆盖、关联纠错通知或自动作废旧题/历史AC，严重错误需归档旧题并重新验证发布。当前私有题结果按需手动刷新，不新增私有题 WebSocket。作业及教师关联提交查询未开始，完整 M3 四门禁未通过。
