# ForgeOJ：M4 管理身份单元完成后交接

2026-10-08，完整M4仍 `IN_PROGRESS`，第1步设计与第2步管理身份单元已完成，第2步 **VERIFIED**；M-1～M3 VERIFIED。工作目录 `D:\Java项目\ForgeOJ`，分支 `feat/m2-accounts`，实现前HEAD为 `0e213e1adc3ae04de7d9160622ea8442940dac28`。设计、实现和证据作为本单元提交，具体交付hash及与远端一致性以 `git log -1`、`git status` 和 `origin/feat/m2-accounts` 核对；用户允许推送至 `chiye0384-dot/ForgeOJ` 同名分支，不授权PR/main合并/tag/release。

本轮只完成第2步并停止。**用户再次要求继续时，下一单元是第3步公共题审核治理**，按 [实施计划](M4-IMPLEMENTATION-PLAN.md)、Requirements 12/13、Roadmap M4、D-016/D-043及现有不可变送审合约先收敛案件阅读/权限/事务/发布/反馈范围，再实现和验收。不重做管理员身份，不提前扩展Redis/ES/运维。若重要产品选择未被既有文档决定，再问用户，常规工程选择自主推进。

先读 [本单元验收](M4-ADMIN-IDENTITY-VALIDATION.md)、[机器证据](evidence/m4-admin-identity/verification.json)、[管理员身份设计](M4-ADMIN-IDENTITY-DESIGN.md)、[CLI](M4-ADMIN-CLI.md)、Requirements 4.2/12/13及Roadmap M4。每种身份一个账号；一个人兼任两个职责分配两个账号；新建/重置/bootstrap首次改密，SUPER重置其他管理员，全体SUPER无法登录时显式本机恢复既有SUPER并留审计，不开放管理员邮件找回，见D-043，无需再问。题解/班级/作业已确认规则保持。

当前代码在 `forgeoj-api/src/main/java/com/forgeoj/api/admin/`、V18及前端 `services/adminApi.ts`、`views/AdminView.vue`，包括独立JWT/Cookie/CSRF/principal、MySQL每请求及fence事务内授权、账号创建/CAS维护、审计、CLI和 `/admin/login` `/admin` `/admin/accounts` `/admin/audit` 页面。管理JWT密钥必须独立配置，不能等于普通JWT；没有管理员种子或Web初始化。API无Docker/Worker/维护凭据，Worker管理六表无权限。后续审核/运维必须直接测试各自业务矩阵，不能因身份认证已通过就认为公共题审核或任务重试授权已验证。

最终当前固定LinuxBackend `target/forgeoj-linux-20261008-153916-11d8804d`：API245/45 suites、Worker133/23 suites，0失败/错误/跳过，BUILD SUCCESS；前端 `target/forgeoj-linux-20261008-144420-e6317968`：20 suites107及所有检查。184 API、110 Worker/shared、72 frontend与只读真实浏览器运行输入匹配，5执行辅助工具关联。相同JAR replay `target/forgeoj-e2e-20261008-170446-46f5ab43`：86HTTP、三角色/首改密/撤权清空/审计筛选详情页面、交互初始化/重跑拒绝/恢复/无终端拒绝、67审计及requestId日志、10SQL拒绝、普通10提交/9完成/1取消、62班级检查、7空队列通过。精确清理于17:17:57 +08全0，局部原生HTTP15/JWT6/升级并发恢复回滚1也通过。

实际JAR SHA256：API `45fb87b26526b9ff32ff3a0b1cc1c13096aaa2b69bfb37271cd4d0892ad56e8e`，Worker `a0a63dc74444269ae0b9dbc9184e6c9344577505a0a2c30488842f9fa0621055`。当前测试数据均一次性，已清理，没有真实管理员/真实库写入。浏览器未提交新凭据；实际改密/创建HTTP和Vue表单检查分别记录，浏览器观察门禁/已有密码重登/表单和权限，不冒充完整UI改密或创建提交。CLI恢复是操作者演练，不是实际全体SUPER失联事故。

初始Backend `target/forgeoj-linux-20261008-144735-22c5d4d9` 的Worker队列等待失败保留，未接受其旧API快照；仅补最后队列观测诊断，20秒/两队列0/原断言不变。最终Backend的Surefire30秒关闭测试fork及fixture连接清理警告、耗时1小时17分钟保留；不推断前轮超时原因。运行证据导出SQL JSON类型两次失败也保留，最后字段断言和事实关联通过。详细失败与实测边界见验收。E-04/E-05仍PLANNED（E-05身份子范围已验证），完整M4其余步骤、M5/V1.1未开始，无Release/RESUME_READY。

保留未跟踪 `docs/M2-NEXT-CHAT-HANDOFF.md`，SHA256 `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054`；忽略 `.smtp.qq.local` 只检查摘要，SHA256 `6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907`，禁止读取/输出/复制/暂存内容。M3交接及M3总门禁是完成阶段的历史证据，本文件是后续执行入口。保留 [L-040](KNOWN_LIMITATIONS.md) 的串行化、单机限流、元数据和本机维护信任边界。

Codex/ChatGPT不得关闭、终止、重启或自动更新；禁止广泛进程杀死、WSL/服务关闭。只停已核对的确切任务PID或容器。最后内置自动化标签关闭与崩溃存在强相关，不能tab.close/UI关闭/复现；临时清理about:blank并markDeliverable，真正待接续才markHandoff。本轮tab4已about:blank并保留，没有声称修复客户端缺陷。
