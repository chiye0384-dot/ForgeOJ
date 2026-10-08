# M4 第3步公共题审核治理验收

2026-10-08，本单元 **VERIFIED**。完整 M4 仍 **IN_PROGRESS**；M-1～M3 保持 VERIFIED。基线 `0f983988aa27841c8d110e9f821adbe6494c90f0`，分支 `feat/m2-accounts`。用户确认 D-044：仅原作者把当前公开冻结内容复制到本人新草稿，再编辑、验证、送审；其他用户提反馈，审核员批准或驳回。

实现 V19、当前管理员权限及事务内授权/审计、冻结案件和显式参考读取、最新双验证批准/驳回及重验 Outbox、原作者复制及 TEXT/CORRECTION 发布、普通下架/恢复、不可逆严重错误标记、合并反馈与本人隐私。公开详情返回全部样例、来源及许可；本人提交/学习历史显示作废提醒，学生及教师作业为 INVALID，保留原提交和尝试次数并排除完成分母。

文案修订保持测试数据、资源、镜像、比较/沙箱规则及输入输出说明一致；判题依据变化发布关联新题并暂停旧题。旧 AC 不证明新题完成，旧作废标记不能恢复；旧快照、审核、正式提交和尝试事实保留。复制不会继承 PASSED，旧用户不能复制他人题目；无作者归属的历史种子题不自动认领。界限见 [L-041](KNOWN_LIMITATIONS.md)。

最终证据为 [verification.json](evidence/m4-public-review/verification.json)、[输入摘要](evidence/m4-public-review/source-inputs.json)、[测试套件](evidence/m4-public-review/test-suites.json)。

| 检查 | 当前结果 |
| --- | --- |
| 固定 Linux All | `target/forgeoj-linux-20261008-201146-a1bd1a95`；API 263/47 suites、Worker 133/23 suites，0失败/错误/跳过，BUILD SUCCESS；后端28:34分钟 |
| 前端 | 同一 All；118/23 suites，type-check、lint、format、build通过 |
| 相同构件真实回放 | `target/forgeoj-e2e-20261008-204157-6a542650`；102公共治理HTTP检查、6次真实双PASSED、5审核案件（3批准、1驳回、1待审）、2关联公开题 |
| 已有流程回归 | 实际8种判题结果；本人/其他人/匿名、Origin、注销WS、排队取消；题库、学习CAS及62项班级检查 |
| 历史隔离 | 真实作业旧AC→普通下架拒绝正式提交及自测→恢复→严重错误不可逆；AC事实保留，学生/教师INVALID且尝试1/完成0；新题自测SUCCESS不解锁，正式WA→AC后解锁 |
| 实际浏览器 | 审核员冻结版本/修订类型/全部样例，显式参考；运维及普通用户受限；原作者真正点击复制，版本1新草稿/0验证任务；本人反馈隐私、旧AC提醒、停用审核员后私密内容清空 |
| 数据和执行证据 | 13正式提交（12完成、1取消）、1独立自测、98审计及HTTP requestId；冻结绑定/Outbox发布、内容和自测提交后ACK、正式任务日志/闭合attempt；7队列ready/unacked均0 |
| SQL及容器边界 | 14新治理权限拒绝+8既有不可变/私有权限拒绝；API无Docker socket/Worker/迁移/维护凭据；API与Worker运行JAR为核对过的只读挂载 |
| 精确清理 | 20:53:14 +08：本次容器、卷、网络、镜像、builder、Testcontainers及受管沙箱全0；只清理已核对的任务资源 |

197 API、111 Worker/shared、79 frontend输入与最终构建匹配；79前端输入与实际只读浏览器运行时逐字节相同；5个回放工具输入匹配实际执行。最终核验工具另记录自身摘要。当前日志未匹配 ERROR/Exception 或私有程序、反馈、凭据哨兵。压缩的完整后端/前端检查日志和摘要保存在证据目录；日志中的迁移夹具连接清理警告、预期失败夹具和故障演练日志仍保留，0测试失败不等于每条测试日志无警告。

API SHA256：`004391da1f75b8d0751738a3d1f4f87a784e6995cb735b8580fa5d5a5375b43f`。
Worker SHA256：`8653b386c9fb3f010d7398d891ec87a3edfacd1e4c38416ba155407b7cc39182`。

浏览器仅使用已由HTTP准备的现有测试密码登录，不声称通过UI改密/创建管理员。原作者复制是实际UI写操作，截图 [复制成功](evidence/m4-public-review/public-review-browser-author.png) 和 [冻结案件](evidence/m4-public-review/public-review-browser-reviewer.png) 已保存；10份DOM记录保留。直接用已归档slug筛选历史返回404，清空筛选后的本人历史保留AC及作废提醒。没有终止、更新、重启Codex/ChatGPT，没有关闭IAB标签；tab5已about:blank并markDeliverable，不宣称防止OS/客户端崩溃。

保留失败与修正：初始修订夹具误用表/列，随后重复用户冲突、普通域CSRF遗漏；前端构造签名/mock、Vue多语句分页和静态props测试包装问题均修正，权限/隔离断言保留。首个 All `target/forgeoj-linux-20261008-193850-4023adf1` 中15项公共审核通过，但263项有1项旧题解权限断言失败，未接受其构件，Worker/前端跳过。V19需要INSERT及idea/source_code/published_at三列UPDATE；测试改为三列允许、身份/语言/DELETE拒绝、非法FK无孤儿行和提前解锁插入失败回滚。随后单项和当前完整All通过。首轮失败后端完整压缩日志已保留，早期分次检查见历史checkpoint；没有删除失败断言或用SQL合成PASSED/AC证明Worker。

重放：先运行 `Verify-FixedLinux.ps1 -Scope All`，核对新JAR摘要后 `Replay-FixedLinux.ps1 Start`，依次 Matrix/WorkerStop/Permissions/WorkerStart/Library/Learning/Classroom，显式一次性CLI bootstrap，再管理员Setup/ReadyBrowsers、公共审核Http、真实浏览器、RevokeReviewer、CaptureRuntime/Audit、Stop/CleanupSnapshot，最后 `Verify-M4PublicReviewEvidence.mjs <backend> <frontend> <replay> <out>`。旧通用Audit要求其它单元的历史浏览器夹具，本单元直接核验实际矩阵/权限/学习/班级和新旧SQL边界，没有伪造那些旧浏览器文件。

用户文件摘要保持：未跟踪M2交接 `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054`，忽略SMTP配置 `6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907`，未读取/复制/暂存其内容。

本单元交付提交/证据应推送 `chiye0384-dot/ForgeOJ` 的既有feature分支，以实际git提交及远端HEAD核对。到本单元为止；第4步运维、Redis/ES/监控、完整M4总门禁、真实管理员初始化、真实库/SMTP、PR/main/tag/release及M5/V1.1不在本轮范围。
