# ForgeOJ 接手说明：M4 第4步已验收

2026-10-09，Asia/Shanghai。本文件替代2026-10-08“第4步未开始”的启动说明。本轮第4步已VERIFIED，交付后停止；完整M4 IN_PROGRESS。当前用户的“继续”授权已用于完成第4步，不能顺便启动第5～7步。

实际仓库是 `D:\Java项目\ForgeOJ`，项目父目录 `D:\Java项目`。先进入实际仓库，检查 `git status --short`、`git branch --show-current`、`git log -1`、远端同名分支及保护文件摘要，保留无关改动。分支 `feat/m2-accounts`，远端 `https://github.com/chiye0384-dot/ForgeOJ.git`；本单元起点 `02b18fed1ba1c8da9da128f12acba0617c7efcba`，交付以当前Git/远端核验，不恢复旧exec/session。

## 必读顺序

1. 根 `AGENTS.md` 当前区和用户桌面连续性规则。
2. [当前交接](M4-NEXT-CHAT-HANDOFF.md)、[最终验收](M4-OPERATIONS-VALIDATION.md)、[机器证据](evidence/m4-operations/verification.json)。
3. [七步计划](M4-IMPLEMENTATION-PLAN.md)、[需求](ForgeOJ-Requirements.md)、[路线图](ForgeOJ-Roadmap.md)、[决策](ForgeOJ-Decision-Log.md)及[L-042](KNOWN_LIMITATIONS.md#l-042人工恢复的终身额度与死信保留)。
4. 需要了解当前恢复实现时读[设计](M4-OPERATIONS-DESIGN.md)，不要再问已接受的D-045三项选项。

## 当前事实

| 范围 | 状态 |
| --- | --- |
| M-1～M3 | VERIFIED；M2 5/5、M3 4/4总门禁 |
| M4第1步 | 设计完成，D-043已确认 |
| M4第2步 | 管理身份/CLI/维护/审计VERIFIED |
| M4第3步 | 公共题审核治理及D-044原作者修订VERIFIED |
| M4第4步 | 运维及D-045有限人工恢复VERIFIED |
| M4第5～7步 | Redis/ES/监控与完整M4门禁尚未开始 |
| 完整M4 | IN_PROGRESS |
| M5/V1.1/Release/RESUME_READY | 未完成，不提前实施或宣称发布 |

固定Linux接受构建 `target/forgeoj-linux-20261009-112325-efec5cc0`：API278/49、Worker133/23、前端124/24，全部门禁通过。真实重放 `target/forgeoj-e2e-20261009-140259-41c05bc4`：6个原任务第4次真实成功、1个同事件第6次发布后AC、122项HTTP、原作业时间、四身份浏览器和撤权、14 SQL拒绝、提交后ACK、冻结/旧历史/运行输入一致。7队列unacked=0，4活动队列空，6旧死信保留。所有拥有者资源已清理，旧URL不可用。tab1留about:blank且markDeliverable，不关闭它，新turn重新保留标记。

D-045已确认：①原任务终身人工追加一次执行，不重置计数或新造提交；②按原接受时间判成绩且复核当前账号/题目/班级/成员/参与资格，自然截止允许恢复原已接受提交；③内容仅当前同版本DRAFT或当前PENDING案件最新VALIDATE，旧快照拒绝，预览不会自动确认。自测payload过期/缺失、活动租约、SNAPSHOT_INVALID、用户verdict和额度已用均拒绝。

## 后续边界

收到针对新单元的继续指令后，下一步只做M4第5步Redis与降级。先细化设计、相关当前接口与失效事件/权限边界，再做实现、故障降级和同构件验收；不得直接实现ES或监控。常规工程选择可按已批准规则落实；新的产品、持久数据、安全、费用或外部行为分歧才需要解释选项。

用户已授权完成验收单元提交/推送上述功能分支，不重复问许可。没有PR/main/tag/Release/部署或真实管理员初始化授权；不改真实数据库/SMTP。E-05完整候选IMPLEMENTED，其身份/审核/运维子范围VERIFIED，未新增RESUME_READY。

两个用户文件只能Get-FileHash：未跟踪 `docs/M2-NEXT-CHAT-HANDOFF.md` 为 `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054`；忽略 `.smtp.qq.local` 为 `6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907`。不读、输出、复制、暂存、重置或删除内容；禁止git add . / reset / clean。不能关闭/重启/更新Codex/ChatGPT，不能关闭IAB标签、全局停进程/WSL/服务；只核对后停止精确任务拥有者资源。

旧检查点、失败轮及第3步交接仍可追溯；它们的“下一步”“运行中”或session ID不具有当前执行权威。