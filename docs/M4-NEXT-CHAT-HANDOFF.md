# ForgeOJ：M4 第6步设计与额度检查点

2026-10-10交付事实：第5步实现3abd1d9及检查点70042f87b6c79ff3a73c7d9c0b3032afb9ff22be已推送到chiye0384-dot/ForgeOJ的feat/m2-accounts，远端已核验；搜索设计a6d6295为本地文档检查点，交付SHA以当前Git/远端为准。

2026-10-10许可确认：在解释Elasticsearch/OpenSearch及ELv2影响后，用户明确回复“按推荐来”，已批准官方Elasticsearch9.5.3默认发行ELv2、免费Basic、仅ForgeOJ内部公共题搜索、保留原许可告知；ForgeOJ自有源码保持Apache-2.0。D-047已接受，不再询问该选择。许可阻断解除，尚未拉取/运行镜像、增加业务代码或V24。最新五小时额度已用85%（剩约15%），按照用户额度不足及时停止规则，停在已批准设计检查点；不是新的自动审批拒绝。下一次额度足够且用户继续时直接按M4-SEARCH-DESIGN的实施顺序，从V24公开版本/Outbox与真实MySQL合约测试开始，只完成第6步后交付并停。既有第5步VERIFIED、完整M4/E-04 IN_PROGRESS、第7步/M5/Release未开始。

## 当前权威检查点：第5步VERIFIED（2026-10-10）

固定Linux接受构建：`target/forgeoj-linux-20261010-081555-deb02bb5`，API289/50 suites、Worker133/23 suites，零失败/错误/跳过；前端124/24及全部检查通过。390业务/前端输入匹配，82个前端输入同时匹配挂载和实际提供内容。API SHA256 `4a13a56a97d531ef3457216e10fabfdaa3f903e0027563b1f009faa54188959d`；Worker `e142de6b117ad1edc0a1554c4c71db0a720d4e5100930b0379240a0985f9e3e2`。

接受同JAR重放：`target/forgeoj-e2e-20261010-090029-fb7dc71a`。Redis专项108项HTTP、双API共享预算、7个同键回执仅1个Submission/task/event、Redis健康及暂停期间各一次真实AC、撤销/降级/停用时旧JWT仍未过期但均被拒绝、暂停期间至少2个失败失效事件恢复后全部交付、清空后旧回执保持且本机预算不重置。权限DB失败真实503空body/no-store且finally恢复授权。4项SQL拒绝、7队列全空、原作业截止前接受/截止后完成、Worker提交后ACK通过。浏览器6张截图/5份页面快照：OPS表、撤权后账号/表清空、暂停期间匿名题库、降级审核页可用及运维页拒绝；原异常列表为空，没有选中任务详情，不宣称详情清空。

所有拥有者容器/卷/网络/镜像、builder、Testcontainers和managed sandbox精确清理全0。旧URL不可用，tab1保留在about:blank并标记deliverable，不能关闭它或Codex/ChatGPT。实际22个嵌套依赖JAR摘要匹配23项解析依赖目录（空starter不打包）。最终机器证据 `docs/evidence/m4-redis/verification.json`。仅证据审计器在回放后修正published标量子查询的数值1类型，原冻结脚本与当前摘要均保留；其余运行辅助及全部业务输入未变化。首次健康页只有截图，缺失文本未列入交付，未补造页面事实。

本单元起点4eb49c0e4c88bd6624bdc1a63995953d8405fa09，分支feat/m2-accounts，远端https://github.com/chiye0384-dot/ForgeOJ.git。验收完成后按既有授权提交/推送这个功能分支，然后停止；交付SHA以当前Git/远端核验，不把起点当当前提交。用户本轮已授权第6步，许可已确认，当前仅停在额度检查点；第7步监控、M5、PR/main/tag/Release/RESUME_READY、真实数据库/SMTP/管理员初始化均不启动。用户额度不足及时停止的规则继续有效。

保护文件只能Get-FileHash：docs/M2-NEXT-CHAT-HANDOFF.md e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054；.smtp.qq.local 6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907。不读/输出/复制/暂存/覆盖/删除，禁止git add .、reset、clean。桌面连续性规则始终有效。

## 历史检查点（仅追溯，以下旧接续步骤不再有效）

## 最新检查点：响应头修复后重新全量构建（2026-10-10）

审批已恢复。旧同JAR回放`target/forgeoj-e2e-20261010-080112-15ffe30c`通过基础回归、教师131项、作业161项、管理61项及Redis Healthy50项；GuardFailure正向缓存下返回503但无no-store，按原断言判失败。普通认证过滤器补no-store并清空上下文，原Redis专项补真实HTTP回归。业务输入变化后，20261009构建只属历史，不能用于最终交付；新完整Linux All目录`target/forgeoj-linux-20261010-081555-deb02bb5`，日志`target/m4-redis-linux-all-header-final.log`，须检查真实完成及新摘要。两旧栈已精确清理全0，tab1在about:blank且保留。没有step5交付提交。下方额度检查点是历史；额度不足及时停止仍是用户要求。

下一次干净回放顺序：Matrix→WorkerStop→Permissions→WorkerStart→Library→Learning→Classroom→PrivateProblems→Assignment Setup/Queue/原UTC截止后Finish→Teacher Setup→Redis Bootstrap交互CLI→Admin Setup→Redis Setup→必要时等待真实登录预算→Healthy→立即GuardFailure→OPS浏览器→Pause/Outage/Pending→ResumeFlush/Recovered→浏览器撤权清空和审核角色/运维拒绝→Audit/CaptureRuntime→about:blank保留→Stop/CleanupSnapshot→证据审计→文档/功能分支交付→停止。Teacher先运行会把第三人变助教，影响Assignment晚加入前提，现有ResetAssistant可通过真实leave接口恢复LEFT，但干净重放采用前述顺序。Bootstrap使用已存在的公开fixture迁移维护账号，root仅允许本地连接，不给它加远程授权。辅助工具已修正UTC DateTime转换、Redis profile清理和重复夹具准备；任何失败仍须检查实际结果，不把工具预期当事实。

## 最新检查点：额度阻断后停止（2026-10-09）

用户最新要求“继续，如果额度不够做下一件事就及时停下来”。完整固定Linux All已完成、退出0：`target/forgeoj-linux-20261009-155402-e3d3bd3f`，API289/50 suites、Worker133/23 suites，零失败/错误/跳过；前端124/24及type/lint/format/build全通过。最新专项 `target/m4-redis-null-fallback.log` 11项通过。业务源码从最终构建后未再改动，之后仅调整文档/开发Compose/验收辅助工具；接续仍须按构建manifest逐字节复核输入。

API SHA256 `bda76c023ffb0010fb7bcc633a8fc57e012672772b7602e3f04c0fd97545e984`；Worker `c13ce6bbcd913bdac46e0809f9d602c416e3c5fca2b858feecc09f2a06e6c565`。下一条独立重放启动的提权请求因自动审批服务提示额度不足而未执行；不是安全性否定，不绕过审批。启动动作没有创建本次Redis栈，也没有新浏览器tab；完整测试结束后builders/Testcontainers/managed sandbox已检查为空。旧step4重放目录均为已清理历史。未完成新单元，不提交/推送半成品，HEAD仍`4eb49c0`。

下次正常审批可用后，用上述目录和两摘要执行`Replay-FixedLinux.ps1 -Action Start -EnableRedis`，Docker绝对路径仍`C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe`。然后执行原Matrix/Permissions/Library/Learning/Classroom/PrivateProblems回归、Teacher Setup及Assignment Setup/Queue/真实截止后Finish；`Verify-M4RedisReplay Bootstrap`在唯一fixture里使用交互CLI创建`m4_super`（公开初始测试密码`m4-public-initial-admin-password`），`Verify-M4AdminReplay Setup`通过HTTP改密并准备隔离角色。禁止真实管理员初始化。

Redis专项顺序：Setup启动第二API/普通fixture → 沿用真实300秒登录窗口，必要时等待，不能调高上限 → Healthy → 立即GuardFailure（必须已有正向缓存；临时REVOKE会话SELECT后503，finally恢复） → 实际OPS浏览器 → Pause → Outage（真实AC、旧普通会话拒绝、OPS降级/审核员停用、公共题归档/恢复） → Pending（至少两条真实失败失效事件） → ResumeFlush → Recovered → 旧OPS页面清空/当前审核角色及运维拒绝 → Audit（待失效全交付、暖缓存TTL/白名单、实际SQL拒绝、队列/冻结作业事实） → CaptureRuntime → 浏览器about:blank并markDeliverable → Replay Stop → CleanupSnapshot → Verify-M4RedisEvidence。

新增`Verify-M4RedisEvidence.mjs`已语法检查，但尚未以真实重放运行，不能把预期字段当事实；遇到夹具/类型错误核对实际SQL/JSON修复，保留业务断言，不伪造浏览器结果或放松权限/幂等。Cua已选IAB浏览器ID2，目前无本次tab，不能拿旧tab1当有效句柄；需要时先重新读取文档/库存，只创建一次fixture tab并立即保留。私有Cookie文件`redis-private-clients.json`只留target，禁止复制/提交；证据只复制白名单安全文件。全部完成后更新VERIFIED、验收/限制/路线图/接手说明，按既有授权commit/push功能分支，然后停在第5步；ES/监控/M5不开始。

2026-10-09 第4步已交付并推送 4eb49c0 后，用户授权继续第5步 Redis/降级；当前 IN_PROGRESS，设计见 M4-REDIS-DESIGN.md。先检查实际工作区和最新测试日志；完成第5步实现、固定环境验证、证据和既有分支交付后停止。第6/7步与完整 M4/M5 尚未完成。下方第4步是历史验收，旧等待第5步授权的文字已被本次继续取代；不恢复旧运行 session。

## 当前交接：第4步 VERIFIED，完整M4 IN_PROGRESS（2026-10-09）

实际仓库 `D:\Java项目\ForgeOJ`，分支 `feat/m2-accounts`，远端 `https://github.com/chiye0384-dot/ForgeOJ.git`。第4步实施基线 `02b18fed1ba1c8da9da128f12acba0617c7efcba`，最终交付包含当前代码、验收与本文；核对 `git log -1` 和远端同名分支取得实际交付提交，不把实施基线误作交付SHA。用户已授权本单元功能分支提交/推送，没有PR/main/tag/release/部署授权。

先读[统一接手说明](M4-STEP4-NEW-CHAT-START.md)、[本单元验收](M4-OPERATIONS-VALIDATION.md)、[当前机器证据](evidence/m4-operations/verification.json)、[设计](M4-OPERATIONS-DESIGN.md)、[七步计划](M4-IMPLEMENTATION-PLAN.md)。D-045三项推荐已由用户确认，不能再次问选项。M-1～M3和M4第2/3/4步VERIFIED；完整M4仍IN_PROGRESS。

本单元已完成白名单查询、原任务终身人工追加一次、同事件有限投递恢复、不可变凭证/理由审计和最小页面，V20～V22追加迁移。固定Linux All API278/49、Worker133/23、前端124/24全过；同JAR6次真实执行恢复、1次投递恢复、122项HTTP、截止后原接受时间判成绩、四身份浏览器和撤权清空、14 SQL拒绝、冻结/旧尝试不变、提交后ACK、运行输入一致及精确清理通过。4活动队列空、7队列unacked为0，保留6旧死信；没有新增死信清理接口。

唯一接受构建 `target/forgeoj-linux-20261009-112325-efec5cc0`；重放 `target/forgeoj-e2e-20261009-140259-41c05bc4` 已删除全部拥有者资源。旧exec全部结束，旧URL失效，不继续旧session或以旧失败构件启动。首次日志不完整重放和429等待记录为历史，最终证据完整。浏览器tab1保留在about:blank并markDeliverable，永远不要关闭标签或Codex/ChatGPT；新turn重新标记保留。

保护文件 `docs/M2-NEXT-CHAT-HANDOFF.md` 未跟踪SHA256 `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054`、`.smtp.qq.local` 忽略SHA256 `6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907`。仅Get-FileHash，不读内容/复制/暂存/重置/删除。新接手核对Git和保护摘要，不执行真实数据库/SMTP或真实管理员初始化。

本轮到第4步停止。后续用户明确继续的下一单元才是第5步Redis与降级：先按七步计划阅读并细化缓存/会话/限流/失效与回退设计，再进入该单元。Redis、ES、监控、M5/V1.1、Release/RESUME_READY均未开始或未完成，不能把本单元结论推广成完整M4通过。下方第3步交接保留历史；其“下一步第4步”“先等继续”不再描述当前待完成工作。

---

以下第3步记录为已验收历史。

新对话统一从 [M4 第4步启动说明](M4-STEP4-NEW-CHAT-START.md) 开始，包含完整状态、必读顺序、已确认规则、下一单元步骤及验收/保护要求。第3步已提交并推送：`c60b75d60bfe210944a9cefde17cade92bb8a71f`，分支 `feat/m2-accounts`，远端 `chiye0384-dot/ForgeOJ`。2026-10-08 本次交接已核对本地/远端一致，后续可有纯文档交接提交。新对话首次只读核对并报告准备情况，等用户“继续”后实施第4步；本次交接不启动业务实现。

2026-10-08：**公共题审核治理单元 VERIFIED，完整 M4 IN_PROGRESS**。目录 `D:\Java项目\ForgeOJ`，分支 `feat/m2-accounts`，第3步实施基线 `0f983988aa27841c8d110e9f821adbe6494c90f0`。实现、设计和证据组成此单元交付；实际交付hash及推送结果核对 `git log -1`、`git status` 和 `origin/feat/m2-accounts`，不把实施基线误认为最终提交。用户授权推送 `chiye0384-dot/ForgeOJ` 的既有分支，无PR/main/tag/release授权。

先读 [本单元验收](M4-PUBLIC-REVIEW-VALIDATION.md)、[机器证据](evidence/m4-public-review/verification.json)、[设计](M4-PUBLIC-REVIEW-DESIGN.md)、[7步计划](M4-IMPLEMENTATION-PLAN.md)、Requirements及D-043/D-044。D-044已确认：仅原作者复制当前公开冻结内容到本人新草稿，再验证送审；他人反馈、审核员批准或驳回。不重新问管理员、题解、班级、作业或修订选择。

V19、PublicReview/Feedback/Revision服务及页面已完成。冻结审核/显式参考/最新双验证重验Outbox、原作者TEXT与关联CORRECTION、普通下架/恢复与不可逆作废、反馈合并与本人隐私、公开样例/许可、本人历史警告及学生/教师INVALID均通过。复制不继承通过；旧AC不证明新题完成。

完整固定Linux All `target/forgeoj-linux-20261008-201146-a1bd1a95`：API263/47 suites、Worker133/23、前端118/23及所有检查。相同JAR回放 `target/forgeoj-e2e-20261008-204157-6a542650`：102公共治理HTTP、6真实双PASSED、13正式提交、1自测、98审计、62班级检查、14新增+8既有SQL拒绝、实际多角色浏览器/复制/撤权、日志及七空队列通过。197 API/111 Worker/shared/79 frontend输入匹配，79浏览器输入只读且相同，运行JAR摘要一致。20:53:14 +08所有确切测试资源清理为0，tab5已about:blank并保留。没有活跃验收exec或回放栈。失败记录和测试边界见验收；历史checkpoint输入不替代最终source-inputs。

API SHA256 `004391da1f75b8d0751738a3d1f4f87a784e6995cb735b8580fa5d5a5375b43f`；Worker `8653b386c9fb3f010d7398d891ec87a3edfacd1e4c38416ba155407b7cc39182`。测试数据全为一次性，未初始化真实管理员或修改真实库/SMTP。浏览器没有执行新密码输入；HTTP准备凭据与真实UI读取/复制分别记录。

**本轮停止于第3步。用户再次要求继续时，下一单元是第4步运维任务查询和手动重试**：先读已批准的M4管理员角色及有限重试/DLQ/审计设计，核对当前提交及不可变执行合约，收敛OPS/SUPER权限与可重试状态，再实现和验证。第5～7步、完整M4五总门禁、Redis/ES/监控、M5/V1.1、发布和RESUME_READY仍未完成。不重做已验证第2/3步；必要产品选择才问用户，常规工程自主推进。

保留未跟踪 `docs/M2-NEXT-CHAT-HANDOFF.md` 摘要 `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054`；忽略 `.smtp.qq.local` 摘要 `6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907`，不能读取/输出/复制/暂存内容。不得关闭、退出、终止、更新或重启Codex/ChatGPT；禁止广泛进程/WSL/服务停止、IAB tab.close/UI关闭或崩溃复现；只清理确切任务PID/容器。临时页面about:blank并markDeliverable，后续取得标签需重新标记。不能声称已修复客户端崩溃。

---

以下为第2步历史交付记录。

2026-10-08，完整M4仍 `IN_PROGRESS`，第1步设计与第2步管理身份单元已完成，第2步 **VERIFIED**；M-1～M3 VERIFIED。工作目录 `D:\Java项目\ForgeOJ`，分支 `feat/m2-accounts`，实现前HEAD为 `0e213e1adc3ae04de7d9160622ea8442940dac28`。设计、实现和证据作为本单元提交，具体交付hash及与远端一致性以 `git log -1`、`git status` 和 `origin/feat/m2-accounts` 核对；用户允许推送至 `chiye0384-dot/ForgeOJ` 同名分支，不授权PR/main合并/tag/release。

本轮只完成第2步并停止。**用户再次要求继续时，下一单元是第3步公共题审核治理**，按 [实施计划](M4-IMPLEMENTATION-PLAN.md)、Requirements 12/13、Roadmap M4、D-016/D-043及现有不可变送审合约先收敛案件阅读/权限/事务/发布/反馈范围，再实现和验收。不重做管理员身份，不提前扩展Redis/ES/运维。若重要产品选择未被既有文档决定，再问用户，常规工程选择自主推进。

先读 [本单元验收](M4-ADMIN-IDENTITY-VALIDATION.md)、[机器证据](evidence/m4-admin-identity/verification.json)、[管理员身份设计](M4-ADMIN-IDENTITY-DESIGN.md)、[CLI](M4-ADMIN-CLI.md)、Requirements 4.2/12/13及Roadmap M4。每种身份一个账号；一个人兼任两个职责分配两个账号；新建/重置/bootstrap首次改密，SUPER重置其他管理员，全体SUPER无法登录时显式本机恢复既有SUPER并留审计，不开放管理员邮件找回，见D-043，无需再问。题解/班级/作业已确认规则保持。

当前代码在 `forgeoj-api/src/main/java/com/forgeoj/api/admin/`、V18及前端 `services/adminApi.ts`、`views/AdminView.vue`，包括独立JWT/Cookie/CSRF/principal、MySQL每请求及fence事务内授权、账号创建/CAS维护、审计、CLI和 `/admin/login` `/admin` `/admin/accounts` `/admin/audit` 页面。管理JWT密钥必须独立配置，不能等于普通JWT；没有管理员种子或Web初始化。API无Docker/Worker/维护凭据，Worker管理六表无权限。后续审核/运维必须直接测试各自业务矩阵，不能因身份认证已通过就认为公共题审核或任务重试授权已验证。

最终当前固定LinuxBackend `target/forgeoj-linux-20261008-153916-11d8804d`：API245/45 suites、Worker133/23 suites，0失败/错误/跳过，BUILD SUCCESS；前端 `target/forgeoj-linux-20261008-144420-e6317968`：20 suites107及所有检查。184 API、110 Worker/shared、72 frontend与只读真实浏览器运行输入匹配，5执行辅助工具关联。相同JAR replay `target/forgeoj-e2e-20261008-170446-46f5ab43`：86HTTP、三角色/首改密/撤权清空/审计筛选详情页面、交互初始化/重跑拒绝/恢复/无终端拒绝、67审计及requestId日志、10SQL拒绝、普通10提交/9完成/1取消、62班级检查、7空队列通过。精确清理于17:17:57 +08全0，局部原生HTTP15/JWT6/升级并发恢复回滚1也通过。

实际JAR SHA256：API `45fb87b26526b9ff32ff3a0b1cc1c13096aaa2b69bfb37271cd4d0892ad56e8e`，Worker `a0a63dc74444269ae0b9dbc9184e6c9344577505a0a2c30488842f9fa0621055`。当前测试数据均一次性，已清理，没有真实管理员/真实库写入。浏览器未提交新凭据；实际改密/创建HTTP和Vue表单检查分别记录，浏览器观察门禁/已有密码重登/表单和权限，不冒充完整UI改密或创建提交。CLI恢复是操作者演练，不是实际全体SUPER失联事故。

初始Backend `target/forgeoj-linux-20261008-144735-22c5d4d9` 的Worker队列等待失败保留，未接受其旧API快照；仅补最后队列观测诊断，20秒/两队列0/原断言不变。最终Backend的Surefire30秒关闭测试fork及fixture连接清理警告、耗时1小时17分钟保留；不推断前轮超时原因。运行证据导出SQL JSON类型两次失败也保留，最后字段断言和事实关联通过。详细失败与实测边界见验收。E-04/E-05仍PLANNED（E-05身份子范围已验证），完整M4其余步骤、M5/V1.1未开始，无Release/RESUME_READY。

保留未跟踪 `docs/M2-NEXT-CHAT-HANDOFF.md`，SHA256 `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054`；忽略 `.smtp.qq.local` 只检查摘要，SHA256 `6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907`，禁止读取/输出/复制/暂存内容。M3交接及M3总门禁是完成阶段的历史证据，本文件是后续执行入口。保留 [L-040](KNOWN_LIMITATIONS.md) 的串行化、单机限流、元数据和本机维护信任边界。

Codex/ChatGPT不得关闭、终止、重启或自动更新；禁止广泛进程杀死、WSL/服务关闭。只停已核对的确切任务PID或容器。最后内置自动化标签关闭与崩溃存在强相关，不能tab.close/UI关闭/复现；临时清理about:blank并markDeliverable，真正待接续才markHandoff。本轮tab4已about:blank并保留，没有声称修复客户端缺陷。
