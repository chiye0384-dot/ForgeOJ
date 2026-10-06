# ForgeOJ：M2 已验收，转入 M3 的新对话交接

> 2026-10-06 最新接续：班级私有题单元已VERIFIED，完整M3仍IN_PROGRESS。先读 [私有题设计](M3-PRIVATE-PROBLEMS-DESIGN.md)、[最终验收](M3-PRIVATE-PROBLEMS-VALIDATION.md)、[输入/JAR/测试关联](evidence/m3-private-problems/verification.json)，再核对当前Git。基线089d777上的V16/私有题页面/真实共享执行链路完成；API203、Worker133、前端82、155HTTP、实际两账号页面、13私有SQL拒绝和公共38拒绝、7空队列、所有测试资源清理通过。默认AFTER_AC且未AC可提醒后再次确认，作业策略不能被自由练习绕过。作业/教师关联/完整四门禁尚未开始或完成；用户要求本轮只完成这一步，下一步另说。旧交接与SMTP文件保留。下方两段及正文均为先前日期的历史快照，不能再沿用“下一步私有题”作为当前事实。

> 2026-10-06 接续更新：本交接下方为2026-10-05起点快照。班级成员首单元现已VERIFIED，完整M3 IN_PROGRESS；下一步班级私有题。请在本交接后读 [最新设计](M3-CLASSROOM-DESIGN.md)、[实际验收/构件/失败轮次](M3-CLASSROOM-VALIDATION.md) 和 [证据](evidence/m3-classroom/README.md)，再核对当前Git HEAD，不沿用d228e3a或M3 PLANNED作为当前事实。私有题写路径前须补真实引用/外键和删除保护；作业、教师关联、完整四门禁仍待完成。

> 整理日期：2026-10-05，Asia/Shanghai。用户明确要求结束过长对话、详细交接并创建可直接接手的新对话。
> 实际仓库：`D:\Java项目\ForgeOJ`。桌面保存的项目目录是其父目录 `D:\Java项目`，新对话必须先进入 ForgeOJ。
> 已验收业务基线：`feat/m2-accounts` / `b66c2d69ea06d71d191ae6a0bbe2910104f31f5e`，远端已核实一致；本交接随后作为单独文档提交，可能使 HEAD 再前进一条。
> M-1、M0、M1、完整 M2 均 VERIFIED；M3 尚为 PLANNED；未 Release、未新增 RESUME_READY。本文件是最新续接入口，不替代需求、已接受决策或 AGENTS.md。

## 1. 给新对话的直接指令

你接手的是现有 ForgeOJ 项目，用户已认可按推荐顺序持续完成项目。请用中文交流，承担实际实施和验证，不停在泛泛建议或能力确认。

第一轮按以下顺序执行：

1. 宣布使用 `forgeoj-development`，读取其 SKILL.md 和 delivery-workflow.md。
2. 进入 `D:\Java项目\ForgeOJ`，检查实际 Git 分支、HEAD、工作树与远端；读取 AGENTS.md 和本文件。
3. 按第 5 节读取当前需求、路线图、决策和边界，确认完整 M2 VERIFIED，辨认历史快照。
4. 读取 M2 最终审计，确认已经完成的能力；不要重做 M2 账号认证，也不要再次要求用户获取 QQ 授权码或重发激活邮件。
5. 为 M3 建立 `docs/M3-CLASSROOM-DESIGN.md`，明确分阶段范围、角色/资源权限矩阵、班级/成员/转让/归档状态机、接口、事务锁序、版本冲突、迁移与最小 grants、与后续作业的关系、验收矩阵。具体实现选择需从源码和需求推导，不能把本文件的实施建议当成新增用户产品决定。
6. 从“班级与成员”首单元开始实施：以实际角色/成员隔离和状态机测试驱动最小实现，提供可用页面，完成真实浏览器和一次性数据库验收。单元闭环后再进入班级私有题、作业与教学记录。
7. 普通工程选择在已确认需求内自行落实，不要每一步都让用户确认。若发现尚未确定且会实质改变权限、公开行为、历史数据或外部费用的取舍，说明具体影响、给出推荐，并只问必要问题；同时继续不依赖该答案的工作。

第一轮应产出明确设计和可执行的首单元推进，而不是从头访谈整个项目或把旧“首次 M2 设计需确认”的约束重新套用到当前全部工作。整个 M3 未满足四项门禁前不得标为 VERIFIED。

用户希望“能正常用”，不是只编译通过；明确要求不要靠删功能、删已有代码或把失败检查改成跳过解决问题。完成现有授权内的工作，遇到错误读真实输出、修复后复验；重要变更用简明中文说明目的、证据和限制。

## 2. 当前仓库、授权与保留文件

| 项目 | 当前事实 |
|---|---|
| 仓库/父项目 | `D:\Java项目\ForgeOJ` / `D:\Java项目`；父目录不是本仓库 |
| 分支 | `feat/m2-accounts` |
| 业务验收 HEAD | `b66c2d69ea06d71d191ae6a0bbe2910104f31f5e`，简写 `b66c2d6` |
| 上一实现提交 | `184ba30`，独立自测与真实 QQ SMTP；再前一个 `2fc7e72`，参考输出预览/确认 |
| 远端 | `https://github.com/chiye0384-dot/ForgeOJ.git` |
| 未跟踪用户文件 | `docs/M2-NEXT-CHAT-HANDOFF.md`；保留，不暂存、不删除、不自动覆盖 |
| 用户文件 SHA-256 | `E08ACD3D26A8CD27ACA394BDAC91828E13B9455A86F62133C5BAFC038B18C054` |
| 私密本地配置 | `.smtp.qq.local`，Git 忽略、Windows 当前用户 DPAPI 加密；保留，不分享或复制进验收镜像 |
| 最近 Docker 查询 | 29.8.0，managed sandbox=0、Testcontainers=0；这是交接时事实，开始测试仍须重新核对 |
| 页面/服务 | 上次一次性验收栈已 Stop；旧网页页签已关闭；不要沿用历史端口，不能宣称服务此刻正在运行 |

目前有明确授权在已核实的 `chiye0384-dot/ForgeOJ` / `feat/m2-accounts` 提交、推送通过验收的独立单元。没有 PR、main 合并、tag、Release、部署授权。若为 M3 另建分支，先核对当前状态并保留文档和用户改动；不要把旧分支推送授权默认为任意新远端目标的授权。新任务无需创建 worktree，也不要未经必要性评估改变运行目录。

以下旧入口仅作为历史，不作为当前待办：

- `docs/NEXT-CHAT-HANDOFF.md`：更早阶段。
- `docs/M2-NEXT-CHAT-HANDOFF.md`：2026-10-02 的 M1→M2 交接，仍写 M2 PLANNED 和“首轮只做账号设计”。这已经被完整 M2 验收及当前用户继续授权取代，但文件属于用户保留内容，必须原样留着。
- AGENTS/README/需求/路线图里带日期的旧单元段落：记录当时的失败、状态和下一步；看最上方最新记录与 `M2-GATE-AUDIT.md`。不能从旧段落推断 SMTP/自测仍未完成。

## 3. 已经完成什么

### 3.1 M-1、M0、M1

| 阶段 | 已交付及验证 |
|---|---|
| M-1 工程准备 | 工程/依赖/许可证和要求基线；Java 21、Boot 4.1.1、原生 MyBatis 4.1.0；API 与 Worker 独立可执行 Maven 模块、Vue 前端 |
| M0 最小判题纵向切片 | 公共题、提交、不可变判题依据、Submission/JudgeTask/Outbox 原子建立；幂等请求；RabbitMQ confirm/持久任务；独立 Worker Docker 执行；结果查询与真实页面 AC。20 项门禁通过 |
| M1 可靠异步判题 | attempt/lease/heartbeat、有限重试/恢复、旧 owner fencing、取消和配额；数据库提交后 ACK；通知与轮询；受控沙箱、安全/资源证据和八种用户 verdict；真实子 Worker 故障与固定 Linux。15/15 门禁通过，见 `docs/M1-GATE-AUDIT.md` |

不要改回 Boot 3.x、MyBatis-Plus、单进程判题、同步执行用户代码或仅 JWT 认证。正式状态与判题事实必须保留。

### 3.2 完整 M2

| 能力 | 当前可用内容 | 主要设计/验收入口 |
|---|---|---|
| 普通账号 | 注册、邮箱激活、用户名/邮箱登录、短 JWT + MySQL 独立 sid；refresh 轮换/重用撤销；当前/全部退出；改密、找回、旧账号补邮箱；停用即时拒绝旧会话与通知 | `M2-ACCOUNTS-DESIGN.md` / `M2-ACCOUNTS-VALIDATION.md`；D-041 |
| 公共题库 | 标题关键词、难度、标签、分页；真实 slug 选题；详情白名单、状态复核；原 `/` 入口保留 | `M2-LIBRARY-SMTP-VALIDATION.md` / `M2-REMAINING-IMPLEMENTATION.md` |
| 学习记录 | 官方题单；本人私有题单/排序/删除；当前判题版本本人真实 AC 进度；服务端代码草稿与 CAS 冲突；本人提交历史，归档/不可用占位及隐私 | `M2-LEARNING-RECORDS-DESIGN.md` / `M2-LEARNING-RECORDS-VALIDATION.md` |
| 私有作者内容 | 本人草稿/题面/样例；独立参考与题解代码；逐条/有界 ZIP 测试导入；gzip/大小/摘要；所有者与版本冲突；归档及引用后禁止物理删除 | `M2-CONTENT-DESIGN.md` / `M2-CONTENT-VALIDATION.md` |
| 双程序正式验证 | 独立冻结快照和测试；参考/官方题解两份代码分别正式沙箱运行；全部通过才 PASSED；修改失效；共享额度、独立 Outbox、租约恢复与私有历史 | `M2-CONTENT-VALIDATION-JOBS-DESIGN.md` / `M2-CONTENT-VALIDATION-JOBS-VALIDATION.md` |
| 不可变送审/撤回 | 当前 owner/draft/version/PASSED job 的冻结审核候选；唯一待审、幂等/CAS、待审禁止修改；撤回后修改；永久冻结历史 | `M2-CONTENT-REVIEW-DESIGN.md` / `M2-CONTENT-REVIEW-VALIDATION.md` |
| 输出预览/确认 | 仅运行私有参考程序生成输出；整组原子保存；先预览，owner 明确确认后 CAS 更新测试输出/版本；不自动覆盖、不代表双通过或成绩 | `M2-OUTPUT-PREVIEW-DESIGN.md` / `M2-OUTPUT-PREVIEW-VALIDATION.md` |
| 官方题解访问 | 本人当前版本 FINISHED/AC 自动解锁；公共题提前查看必须明确确认并记录私人学习事实；独立题解快照；他人/版本隔离，不公开私有参考 | `M2-SOLUTION-ACCESS-DESIGN.md` / `M2-SOLUTION-ACCESS-VALIDATION.md` |
| 独立自测 | 冻结源码/自定义输入；owner、UUID 幂等、排队取消、短期历史；独立 job/attempt/output 与 shared quota；真实 Worker SIGKILL 自然租约恢复 | `M2-SELF-TEST-DESIGN.md` / `M2-SELF-TEST-VALIDATION.md` |
| QQ SMTP | 真实 QQ 服务商投递激活邮件，用户确认收取/点击，DB ACTIVE/emailVerified 已核实；确认按钮 busy/disabled 光标问题已修正 | `QQ-SMTP-LOCAL-SETUP.md`；`evidence/m2-self-test/smtp-delivery.json` |

表内路径均相对于 `docs/`；对应证据保存在 `docs/evidence/m2-*`。某些文件保留旧阶段的“尚未完成后续内容”说明，当前总体结论看最终审计。

自测 SUCCESS 绝不能伪造正式 Submission、AC、学习进度或题解解锁。自测终态私有源码/输入/输出 24 小时清理；最小 job/不可变快照元数据与闭合 attempt 证据永久保留，用于幂等及孤儿安全清理（L-038）。验证任务、输出预览也不能混入正式成绩。

### 3.3 M2 最后关闭的五项门禁

`docs/M2-GATE-AUDIT.md` 逐项 PASS：

1. 注册→激活→登录→真实 AC 的端到端记录通过。
2. 题目版本切换不会篡改历史提交：新增 API 逐列比较旧 Submission/Task/version，旧 UUID 重试仍返回原提交，新请求使用新资源版本；新增 Worker 检查既有任务仍加载原隐藏测试和资源。已有学习测试证明旧 AC 不算新版本进度、旧记录不删。
3. 普通用户不泄露公共题隐藏测试或私有参考；本人作者可读取自己提供的内容，不等于能看他人/平台私有数据。
4. 私人题单所有读取/写入都隔离；他人、不存在、非法 ID 一致处理。
5. 停用账号使两个独立 sid 的未过期 JWT、refresh 和重新登录均被拒绝。内部服务已实现；管理员操作 UI 属于 M4。

VERIFIED 是规定范围的验证通过；不是已上线，也不是任何代码都能安全对抗的保证。

## 4. 验收产物与不能混淆的证据

| 项目 | 实际目录/结果 |
|---|---|
| M2 门禁新后端 | `target/forgeoj-linux-20261005-192744-ecabee05`；API182（36 suites）+Worker133（23 suites）=315，零失败/错误/跳过、BUILD SUCCESS |
| 新 API JAR SHA-256 | `a3a26a537b81c0b8fcb55314db720f2cb2517515df961b06e7f3ccdc8d1217e6` |
| 新 Worker JAR SHA-256 | `5fdb2bb558b310d047e8672ad31056afbd9a2be16367fa302012b3bac67d86e2` |
| 最后前端全门禁 | `target/forgeoj-linux-20261005-182241-40614bbd`；69 tests/14 suites，类型、lint、格式、生产构建通过；门禁审计只改测试/文档，56 个前端输入完全一致，未重复跑前端 |
| 已实际回放的后端 | `target/forgeoj-linux-20261005-174012-b069321c`；当时 API181+Worker132=313；165 个生产输入及文件清单、旧 JAR 重新核对一致 |
| 实际回放 API SHA-256 | `6d990fa0529a692402b1b52f07c552147a2ef85967bd7f9724f11efda26988cc` |
| 实际回放 Worker SHA-256 | `7b51a8057afca665cbc1b3861bd186e1a974349edb7f44c6ce8f507abebc2f01` |
| 最新真实自测/正式判题回放 | `target/forgeoj-e2e-20261005-182028-1c3fcd89`；实际页面 CE、冻结编辑/输出/历史/取消、Worker SIGKILL→自然租约过期→LEASE_EXPIRED→第二 attempt 成功；旧沙箱清理；正式八 verdict、学习、普通与回退 AC |
| 回放事实 | 自测4/已发布Outbox5；正式11/终态10/取消1；七个空队列、29 项实际 SQL 权限拒绝、提交后 ACK、相关日志/Outbox审计；精确 Stop 完成 |
| QQ 验收 | 先前隔离 SMTP probe 使用真实适配器，用户确认收取/点击；DB ACTIVE/emailVerified；与最终自测回放分开，不把它说成最终审计新发的一封邮件 |
| 可提交新汇总 | `docs/evidence/m2-gate/verification.json`、`cleanup.json` 和 README；与各单元脱敏证据通过 SHA-256 关联 |

门禁阶段没有新的浏览器/SIGKILL/SMTP 回放，不得把新 JAR 哈希附在旧浏览器结果上声称已回放。旧产物与当前生产源码完全相同，是已有运行证据可复用的具体依据；M3 修改生产源码后不能继续沿用这项结论代替新验收。

300 个本轮已有执行输入匹配；本轮新审计工具在构建冻结后添加，不参与业务运行，不能称它在构建清单中。历史自测阶段 Replay 空 SQL 导出器曾在构建后修正并在真实最终 Audit 执行；那次“299/300”仅是当时快照。本轮两个新增测试已在新 Linux 构建中实际执行。

两个模块仍有已知 Surefire fork 30 秒退出警告，测试总计零失败/错误/跳过并 BUILD SUCCESS；不是全部日志无错误/警告。新证据解析器曾修正 XML 属性名、列表对象过滤、ANSI 彩色摘要解析；重新直接运行通过，不是业务测试失败。原始日志保留在忽略的 target，不提交用户凭据或未脱敏数据。

## 5. 新对话必读文件及顺序

先读下面的最小闭环，再按涉及模块补读，不要把整个历史日志一次性全载入：

| 顺序 | 文件 | 阅读目的 |
|---|---|---|
| 1 | `C:/Users/Lenovo/.codex/skills/forgeoj-development/SKILL.md` 及 `references/delivery-workflow.md` | 当前工程工作流程；不要仅依赖技能名称 |
| 2 | `AGENTS.md`、本交接 | 实时约束、授权、工作树与最新起点；后面日期记录为历史 |
| 3 | `docs/ForgeOJ-Roadmap.md` 第6～9节 | M2已验收、M3四门禁、M4/M5边界 |
| 4 | `docs/ForgeOJ-Requirements.md` 第4.1、6、7、8、9节 | 班级角色、内容/私有题、题解策略、判题与成员/作业/教师可见范围；其他章节按依赖补读 |
| 5 | `docs/ForgeOJ-Decision-Log.md` | D-006～D-015、D-017、调度/通知D-038～D-040、账号D-041；原接受决定不直接覆盖，变更需新记录 |
| 6 | `docs/M2-GATE-AUDIT.md`、`docs/evidence/m2-gate/README.md` 和 JSON | 当前实际验收基线、证据口径和未完成边界 |
| 7 | `docs/KNOWN_LIMITATIONS.md`、`docs/Resume-Evidence-Matrix.md` | 限制与状态/简历准入；M2 VERIFIED不等于Release/RESUME_READY |
| 8 | `docs/M2-ACCOUNTS-DESIGN.md`、`docs/M2-LEARNING-RECORDS-DESIGN.md`、`docs/M2-CONTENT-DESIGN.md` | 复用普通身份、事务复核、owner/CAS、学业成绩及作者内容，避免复制另一套认证或草稿模型 |
| 9 | 需要动判题/内容验证/题解时读其 M2 DESIGN/VALIDATION 及 M1设计/门禁 | 保护 immutable snapshot、共享额度、fencing、短期数据清理与访问白名单 |
| 按需 | `docs/UPSTREAM-AND-LICENSE.md`、`docs/DIRECT-DEPENDENCY-LICENSES.md` | 新依赖/代码导入的许可证与来源；没有明确需要不新增框架 |
| 按需 | `docs/Performance-Test-Plan.md`、`docs/QQ-SMTP-LOCAL-SETUP.md` | 真实性能或邮件操作时再读，不靠别的项目数字或公开配置猜凭据 |

## 6. 接下来 M3 做什么、按什么顺序

这是推荐交付拆分，不是已实现状态，也不新增产品承诺。M3 总体设计先覆盖全范围，避免首单元的数据模型与后续作业/权限冲突；随后逐单元闭环。

### 第一个单元：班级与成员

实现目标：普通用户创建班级并成为 OWNER，有效邀请码加入；同一用户可在不同班级拥有不同角色；状态和所有操作都验证当前账号、成员状态和资源所属班级。

既定规则必须保留：

- OWNER 管班级、成员/助教、邀请码、转让与归档；ASSISTANT 协助教学，不能转让/归档、不能任免助教；MEMBER 普通学习。角色不是全站权限或学校认证教师身份。
- 邀请码可关闭/重新生成，已有成员不受影响；不要在日志里泄露有效邀请码，接口限流/错误/并发规则在设计中明确。
- 主动退出为 LEFT，可用当前邀请码恢复同一成员记录；被移出为 REMOVED，只能 OWNER 恢复。不能通过换邀请码或重新加入绕过移出。
- 退出/移除后立即拒绝本班私有内容访问，历史保留；不能只靠浏览器按钮禁用或 JWT 中过时的角色声明。
- OWNER 只能向当前有效成员提出转让；对方接受前原 OWNER 负责；接受后新 OWNER、原 OWNER 默认 ASSISTANT。并发接受/撤回/成员退出必须有单一事务结论，不能出现零 OWNER 或双 OWNER。
- 运行中的班级 OWNER 退出前必须转让或归档；空班级无其他成员、题目、作业、提交时才物理删除，有业务记录只能归档。
- 归档停止新加入、发布、班级提交/讨论，历史只读；后续作业单元必须落实未开始停止启动、活动作业提前结束并留原因，恢复不自动复活旧作业。首单元不要宣称已完成尚未实现的作业联动。

首单元验收至少包含跨班级同人不同角色、角色越权、邀请码关闭/轮换、并发加入/创建/转让、LEFT恢复和REMOVED禁止自恢复、最后OWNER约束、归档/恢复、空删除与有记录保留、账号停用/会话撤销、非法/不存在/他人资源响应策略，以及实际页面完成多账号链路。权限码和事务锁序在设计中写清，不由前端决定权限。

### 第二个单元：班级私有题与受控访问

复用现有作者草稿、测试/参考/官方题解独立验证和冻结版本。OWNER/ASSISTANT 维护，本班有效成员学习；退出/移除/归档按当前关系立即限制。班级题发布须通过参考和官方题解的正式验证，不能直接把草稿测试暴露给 MEMBER，也不能用普通用户充当 CONTENT_REVIEWER。

班级私有发布和公共候选送审是不同流程；将班级题申请公开时应复制独立候选，不复制班级提交/作业/讨论。公共审核批准仍属 M4。个人草稿如何转为班级题、哪些列可更新/冻结须在设计中明确，不能破坏 M2 历史或把永久快照重新指向可变草稿。

### 第三个单元：作业与成绩关联

- 引用公共题和本班私有题；草稿、立即/定时开始；开始时生成参与成员快照。
- 后加入不默认承担旧作业，可由 OWNER/ASSISTANT 手动加入；退出/移出不删旧作业或提交。
- 作业是否接受已有 AC 是创建时选择；PRECOMPLETED 必须关联本人、对应题目/判题依据的真实 AC，尝试次数0，不创建伪 Submission/AC。
- 新尝试关联真实正式提交，首次整题 AC 完成，不限制尝试数；允许迟交默认并标记，或硬截止。
- 开始后文案可修正，截止可延长不可缩短；延期只允许迟交升级为按时。题目集合、已有AC规则、迟交规则与题解策略锁定。
- 实质变化须取消旧作业新建；已开始取消需要原因，不物理删除历史。
- 班级作业题解支持 IMMEDIATE/AFTER_AC/AFTER_DEADLINE，开始后不可改；自由练习使用题目默认策略，不能直接套公共题提前查看接口绕过班级策略。

设计时需解决定时启动的数据库权威时间/并发幂等、截止比较依据、提交创建时与任务完成时的关联、归档取消联动、当前版本变化和成绩证明关系。不要先写一个无锁扫描器再靠重试掩盖重复参与快照或成绩。

### 第四个单元：教师记录、权限矩阵与完整 M3 验收

OWNER/ASSISTANT 仅查看本班作业成员完成状态、尝试次数/首次AC时间、本班作业相关提交与代码。成员加入班级不授予教师读取其其他私人练习代码的权利。不能用 userId + problemId 查询所有个人历史后由前端过滤。

PRECOMPLETED 关联的历史私人 AC 是否授予教师源码访问、后离开成员的历史只读权限及作业具体时间判定若正式需求尚有歧义，需要在对应单元设计里明确；不能偷偷扩大代码可见范围。优先推进不依赖这些答案的班级成员单元。

完整 M3 必须同时通过路线图四门禁：班级作业端到端、完整权限矩阵、后加入/退出/移除/归档边界、教师不能查看无关私人代码。最终写 `docs/M3-GATE-AUDIT.md`，按实际证据逐条关闭。成员单元通过只能把该单元标 VERIFIED，完整 M3 仍 IN_PROGRESS。

## 7. 现有代码位置与必须保持的结构

| 位置 | 作用/注意点 |
|---|---|
| `forgeoj-api/src/main/java/com/forgeoj/api/auth` | AccountService、JWT+DB过滤链、当前敏感写复核、sid撤销；M3在其上做班级授权，不重建全站认证 |
| `.../api/problem` | 公共题详情/列表白名单；班级题别直接并入匿名公共查询 |
| `.../api/learning` | 本人题单/草稿/AC进度/历史；教师访问必须独立按作业关联授权，不扩宽个人history入口 |
| `.../api/content` | 作者内容、验证、送审、输出；owner/CAS与冻结引用/删除规则 |
| `.../api/solution` | 题解快照及当前本人AC/提前查看；班级作业策略需要额外作用域 |
| `.../api/submission` | SubmissionTransactionService 原子创建/幂等/共享额度/结果/取消/通知；正式依据永久不可变 |
| `.../api/selftest` | 独立短期运行任务，不能变成作业成绩来源 |
| `forgeoj-api/src/main/resources/db/migration` | 当前 V1～V14；M3如需迁移追加下一编号，不改执行过的脚本 |
| API/Worker `src/test/resources` 与 Worker `testinfra` | 最小 grants、一次性数据库和 schema helper；新迁移要检查独立子Worker测试是否仍漏建表/权限，不能测试root冒充受限业务账号 |
| `forgeoj-judge-worker/src/main/java/com/forgeoj/worker` | snapshot、task lease/fencing、messaging手动ACK、sandbox、content/selftest运行；Worker不应为班级功能拿到用户/成员隐私表权限 |
| `frontend/src/views` | AccountView、ProblemLibraryView、JudgeWorkspaceView、LearningView、AuthoringView；保留既有入口与交互 |
| `frontend/src/__tests__` | 69项最近前端基线：身份变化、乱序响应、停止轮询、draft冲突、自测冻结、作者验证/送审/输出等 |
| `tools/validation` | 固定Linux构建、独立Replay、HTTP probes、权限/日志/队列审计及恢复工具 |

数据库是最终事实源，MQ 至少一次传输，WebSocket只通知，GET/轮询恢复事实。API没有Docker控制权；Worker按最小表/列权限读取执行依据，不从任务消息接收隐藏测试或凭据。

一个用户同时最多一个运行槽位（内部 WAITING_RETRY 仍占槽位）和三个排队任务，正式提交/双程序验证/独立自测共享；M3作业提交不能绕开这个额度。既有三类任务/各自重试与死信路由现在共有七个验收队列，升级时不能无说明改变 durable 队列参数导致 RabbitMQ PRECONDITION_FAILED。

新增班级/作业关联建议单独建可追溯关系，保留原 Submission/判题版本依据；最终模型以设计/真实数据库约束为准，不为了教学报表更新正式 verdict 或源码快照。历史资源/代码查询必须有后端白名单和当前作用域校验。

## 8. 运行环境、命令与复现注意

已核实本机路径（开始时仍验证可用性）：

- Java：`D:/JDK21/bin/java.exe`。
- Maven：`D:/Maven/apache-maven-3.9.14/bin/mvn.cmd`；本机缓存 `C:/Users/Lenovo/.m2/repository`。
- Node：`D:/Node.js/node.exe`。
- Docker CLI：`C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe`。
- Docker Desktop 如需重启，后台启动应隐藏窗口；不能将界面正在运行当成Docker daemon/发布端口可用的证据。

初始只读检查：

```powershell
Set-Location 'D:\Java项目\ForgeOJ'
git status --short
git branch --show-current
git rev-parse HEAD
git remote get-url origin
```

按改变选择验证，不要求首轮文档整理就重跑完整判题套件：

```powershell
# Windows普通后端入口，实际Maven/JDK缓存以本机环境为准
.\mvnw.cmd --batch-mode clean verify
# 前端有改动时，在frontend目录跑现有全门禁
Set-Location frontend
npm ci
npm run verify
Set-Location ..
# 有实质实现变更后，最终固定Linux；Backend/Frontend/All按影响选
.\tools\validation\Verify-FixedLinux.ps1 -Scope All -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
```

固定 Linux 会创建全新缓存构建和一次性 Testcontainers，后端完整运行可能十几分钟。执行前检查没有另一个 managed sandbox 故障实验；脚本会先验证真实 Docker 发布端口 HTTP，不只是TCP连接。任何生产网络/依赖事实以本机代码和官方来源核对，不从记忆猜版本。

本地 M2 汇总工具是这个历史验收基线的精确核对器：

```powershell
node tools/validation/Verify-M2GateEvidence.mjs target/forgeoj-linux-20261005-192744-ecabee05
```

它要求 M2 的315项以及已有产物/输入一致，并会重写 `docs/evidence/m2-gate/verification.json` 的核对时间；仅做参考核对时不要无意提交覆盖历史报告。M3修改生产代码或测试数后，它报不同是正常的基线变化，不要改成自动放行或继续用它证明M3。为M3新增针对本阶段的验证/证据。

实际浏览器/分离进程验收用 `Replay-FixedLinux.ps1`：先从本次成功构建读取两个JAR哈希，再显式传 `-Action Start -BuildDirectory <本次目录> -ApiSha256 <实际值> -WorkerSha256 <实际值> -DockerCommand <实际CLI>`。不能使用其历史默认M1目录/哈希。Status查看随机端口及 `state.json`；Start完整成功后才进行页面操作。M3要按新schema/fixture扩展Replay，不把旧库/旧JAR当成新链路。

按当前脚本参数使用 Library/Matrix/Learning/Content/Review/Output/SelfTest/Permissions、真实普通与阻断WebSocket页面、Audit、Stop。API/MQ/DB事实及日志相关ID需来自真实运行；用户界面不能靠hidden Vue状态、伪截图或直接写终态伪造。正常与回退UI proof保存数组，观察到的状态实事求是。要稳定观察QUEUED可在唯一fixture WorkerStop后点击，再WorkerStart，不能改数据库状态冒充队列执行。

WorkerStart/恢复compose若改变邮件APP_URL可能重建API并丢失原日志；Start成功后保持其state/env一致。发生这类证据丢失应新建清洁Replay，不手写重构日志。真实SIGKILL验收需在点击前按 ownership核对/arm恢复工具，再杀实际Worker、自然等租约，不SQL推进lease或伪造attempt终态。

其他已知工具坑：

- `rg -g '*.md' docs`可用；Windows不要把未展开的 `docs/M2-*` 当成实际路径传入。
- Git默认core.autocrlf=true，正常 `git diff --check` / `git diff --cached --check` 即可；不要用 `git -c core.autocrlf=false diff --check`把所有CRLF误报空白问题。
- 独立exec是新进程，前一次设置的env不能自然延续；需要SMTP时同一PowerShell先dot-source `Set-QqMail.ps1 -Load`再启动API。已运行的IDE/容器不会自动继承。
- 验收用的DirectMySQLContainer/test-only桥接是 opt-in 测试适配，不允许打进生产JAR；不要靠重新运行失败测试直至偶然绿掩盖环境问题。
- 空SQL结果也必须生成空文件供Audit读取；原Replay导出器已修正，勿回退。
- 执行SQL/读日志只导出脱敏白名单；真实数据库不用于验证，不做全局Docker prune，只清理本次唯一project资源。

## 9. QQ SMTP 与用户激活状态

用户已选择QQ邮箱、开启POP3/IMAP/SMTP、完成本机配置。真实授权收件箱已收到“ForgeOJ 邮箱验证”，用户点击并确认，数据库曾核实ACTIVE/emailVerified；当时忙碌光标不代表未激活，相关按钮光标bug已修正。

`.smtp.qq.local`保存Windows用户DPAPI加密配置；地址/授权码仅用于已授权的本机邮件操作，不要粘贴到新任务提示或提交证据，不必再次询问用户这些既有配置。授权码不是QQ登录密码，不打印解密值、环境变量全集、容器inspect中的密码或完整邮件令牌链接。确有新的外部邮件验收需求时按实际授权边界操作，常规M3测试用本地模拟邮件。

该次真实激活账号位于可丢弃SMTP probe数据库，验收后精确清理；不能声称真实开发数据库中一定已有一个持久可登录账号。不要为“找不到先前测试账号”去修改真实DB、造邮箱verified字段或重置用户密码。需要常规使用时先检查当前真实运行配置，再通过正常产品注册/激活流程操作。

SMTP只验证授权收件箱的激活投递与点击，没有生产域名/HTTPS、多收件人送达率、真实找回/补邮箱或持久投递SLA证据；默认发信disabled、无持久邮件Outbox限制仍保留。不要把该限制重述成“真实QQ投递未做”。

## 10. 还没完成什么

- M3全部功能尚未开始实施；按第6节推进。
- M4独立管理员认证、CONTENT_REVIEWER批准/驳回与公共发布、OPS_ADMIN人工死信重试/审计、SUPER_ADMIN管理、Redis缓存/会话/分布式限流降级、ES搜索/同步/重建/回退、最小监控未完成。
- M5实际生产Linux主机、Nginx/HTTPS、密钥/配置、迁移升级/回滚、备份恢复、小范围上线与真实环境性能验收未完成。Docker内固定Linux通过不等于生产Linux主机验收。
- V1.1用户题解/讨论与更后续内容按路线图推进，不能在M3顺手加入；项目未tag/Release，也未取得RESUME_READY。

既有L-006普通Docker受控边界、L-027调度、L-029通知、L-030日志、L-031清理、L-032可信资源分类、L-033～L-035账号/邮件/限流和L-038自测保留限制须按当前KNOWN_LIMITATIONS核对，不承诺高可用/吞吐/绝对隔离，不把性能数字从其他项目搬过来。

## 11. 每个单元做到什么才算交付

1. 对照需求与设计确认范围，接口、状态机、ownership、事务、权限、历史保留和删除边界可评审；先测关键状态/竞态/越权，避免假测试镜像实现。
2. 后端/前端真实实现，空库与旧库升级加法迁移；API/Worker最小grants；既有M0～M2功能保持回归。
3. 测试失败先定位真实失败层，修正最小问题并重新验证；不改原迁移、不删功能、不减断言、不以skip或假MySQL替代真实数据库结果。
4. 通过匹配源码/JAR的固定Linux检查及实际多账号浏览器流程。身份变化、乱序请求、退出/停用、版本冲突和页面卸载不导致旧结果恢复权限。
5. 队列/Outbox/日志/权限及资源事实核查；有相关故障机制改动时做真实恢复验证。保存命令、退出码、时间、文件/JAR SHA、观察结果，失败轮次也保留。
6. 只使用唯一一次性fixture；清理前核对所有权、精确Stop本项目；读实际daemon确认无残留，不能从Docker命令失败推出“零残留”。用户文件/本地SMTP配置和真实数据保留。
7. 更新单元DESIGN/VALIDATION、`docs/evidence/m3-*`、AGENTS/README/Requirements/Roadmap/KNOWN_LIMITATIONS/Resume证据等相关状态；旧日期段落标历史，不悄悄改写旧失败/结果。
8. 在已有授权目标上，只暂存本单元相关文件，检查diff、凭据/私有邮箱/令牌和用户文件；commit/push后核实实际远端SHA。不要自动PR/main/tag/Release；新分支远端目标超出现有授权时先做完可评审结果，再处理必要授权。
9. 最终回答说清楚实现了什么、为什么这样做、实际如何验证、哪些未验收、下一具体步骤。不得仅回复“可以继续”，也不能把单元通过写成完整M3通过。

当前这份交接只更新续接说明、入口指针和既有QQ验收的限制说明，没有重新跑业务测试、修改业务源码/数据库、发送邮件或部署。新对话应从实时Git核对与M3首单元设计开始。
