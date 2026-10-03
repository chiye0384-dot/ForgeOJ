# M2 个人学习记录设计

状态：学习记录单元 `VERIFIED`（2026-10-03），证据见 [验收](M2-LEARNING-RECORDS-VALIDATION.md)。完整 M2 仍为 `IN_PROGRESS`。实施顺序见 [剩余 M2 计划](M2-REMAINING-IMPLEMENTATION.md)。

依据：[Requirements 6.1、6.2、6.6、8.2](ForgeOJ-Requirements.md)、[Roadmap M2](ForgeOJ-Roadmap.md)、D-018 私有题单、D-032 原生分页评估及 D-041 普通账号合约。本单元只完成个人私有题单、官方题单公开只读与本人进度、本人提交历史、服务端作答草稿和冲突提示。

## 1. 行为与复用边界

- 个人题单始终属于当前登录用户；没有公开开关、分享链接或他人题单入口。
- 收录条件与公共题库一致：当前可浏览的公共题、ACTIVE、存在当前 judge_version。M3 引入班级私有题前必须升级公共可见性谓词；本单元不把 ACTIVE 单独当成未来私有题的公开授权。
- 不可用题目保留题单位置和不可用占位，不能据此返回题面、标题、slug、标签或当前判题版本，也不能从占位发起新提交。
- 进度仅由本人 `submission` 中 `processing_status=FINISHED AND verdict=AC`、且 `judge_version_id=problem.current_judge_version_id` 的记录计算。旧版本 AC、自测成功、草稿或他人的 AC 不计入。
- 题单、草稿和进度不修改历史提交、JudgeTask、attempt 或 Outbox；正式提交继续使用点击时发送的代码快照。
- 官方题单的维护属于平台后续内容管理流程。本单元只提供公开读取和本人进度，测试内容由隔离 fixture 建立；不新增管理员写入口，也不把种子题单称作已完成发布前审核。

可复用的当前源码：

| 现有位置 | 复用方式 |
| --- | --- |
| `forgeoj-api/.../auth/AccountService.java` 的 `requireCurrentWrite` | 每次私有业务写在同一事务中锁定并复核当前用户/会话，沿用账号撤销的锁顺序 |
| `config/SecurityConfig.java`、`SameOriginFilter`、`AccountCsrfRepository` | 所有 `/api/v1/me/**` 保持认证；写操作沿用精确 Origin 与 CSRF |
| `problem/ProblemLibraryMapper.java`、`ProblemLibraryService.java` | 复用可浏览公共题条件、COUNT/LIMIT/OFFSET、批量标签和只读可重复读事务思想；不复制包含隐藏字段的查询 |
| `submission/SubmissionMapper.java` 的 `findStatusByOwner` | 历史查询同样由 principal 提供 user_id，并在 SQL 中限制所有者 |
| `submission/SubmissionQueryService.java` | 复用已有安全诊断规则和状态详情，不扩大详情字段 |
| `submission/SubmissionTransactionService.java` | 正式提交仍执行当前会话、配额、当前题目版本检查；不引用可变草稿行 |
| `frontend/.../forgeojApi.ts` | 沿用同源 Cookie、refresh 协调和 CSRF；添加私有记录客户端方法 |
| `frontend/.../JudgeWorkspaceView.vue` | 沿用实际 slug、页面代次检查和监控清理，将草稿保存与提交结果监控分别管理 |

草稿允许空文本和未完成的 Java 程序。因此不能直接复用正式提交的完整 `public class Main` 校验；只复用语言、UTF-8 字节上限、非法 NUL 等通用输入规则。

## 2. 最小持久化方案

实际实施追加 V8，V1–V7 不改。生产迁移不写入官方题单或用户业务数据，V7→V8 保留全部旧表事实的迁移测试已通过。

| 新表 | 必要列与约束 |
| --- | --- |
| `personal_problem_list` | `id CHAR(36) ascii_bin` 主键，`owner_id BIGINT UNSIGNED` 外键，`title VARCHAR(64)`，`version BIGINT UNSIGNED`，created_at/updated_at；索引 `(owner_id, created_at, id)` |
| `personal_problem_list_item` | 独立 UUID `id` 主键，`list_id` 外键，`problem_id` 外键，正整数 `position`；唯一 `(list_id, problem_id)` 和 `(list_id, position)` |
| `official_problem_list` | UUID id 主键，title、简短 description、`status ACTIVE/ARCHIVED`、version、created_at/updated_at；status/version 约束 |
| `official_problem_list_item` | 独立 UUID id 主键，list_id/problem_id 外键，正整数 position；唯一 `(list_id, problem_id)` 和 `(list_id, position)` |
| `user_code_draft` | `(user_id, problem_id, language)` 联合主键和用户/题目外键，`language=JAVA_21` CHECK，`source_code MEDIUMTEXT`，`version BIGINT UNSIGNED`，updated_at |

题单 item 的 UUID 是占位所需的稳定标识；不可用时只返回 itemId/position/available，不返回 problem_id。题目外键保留归档后的引用。题单删除由同一事务显式先删除 item、再删除本人题单，不级联删除题目或学习历史。

建议初始执行上限：每人 100 个个人题单、每个个人题单 1,000 项；标题 strip 后 1–64 个 Unicode 字符，题单 version 初始为 1。这些是有界请求和事务的实施参数，不是性能验收结果。官方题单由后续受控维护流程保持相同有界查询；本单元所有列表 page 默认 1、size 默认 20、最大 50，offset 使用 long，固定排序。

为当前版本 AC 计算和本人历史查询新增必要索引：`submission(user_id, problem_id, judge_version_id, processing_status, verdict)`、`submission(user_id, created_at, id)`；不新增冗余的 AC 事实表、缓存进度或 Worker 写路径。是否需要改成游标分页取决于后续测量，当前不增加 PageHelper、Redis 或 ES。

数据库权限：

- API 对两张 personal 表仅授予 SELECT/INSERT/UPDATE/DELETE；必要列 UPDATE 可限制到 title/version/updated_at 或 position，不授权 owner_id、list_id、problem_id 的 UPDATE。
- API 对 official 两表仅授予 SELECT；普通 API 连接不能改动官方题单。
- API 对 draft 授予 SELECT/INSERT，以及 source_code/version/updated_at 的列级 UPDATE；不提供删除草稿接口，不授予改变 user_id/problem_id/language 的权限。
- Worker 对以上新表不增加任何权限；隐藏测试和未来私有参考程序仍保持独立受限边界。
- 数据库 grant 不能替代所有者检查；每个私有查询或更新都必须携带 principal 的 user_id。

## 3. 接口合约

所有 `/api/v1/me/**` 受保护。读取私有记录返回 `Cache-Control: no-store`；请求体不接受 ownerId、userId、AC 标记或进度字段。格式错误返回空 400；他人、不存在及非法 UUID 统一空 404；版本冲突返回 409；数据库不可用返回直接 503，不经过受保护的 `/error` 再分派。

### 3.1 本人私有题单

| 接口 | 请求/响应与规则 |
| --- | --- |
| `GET /api/v1/me/problem-lists?page=&size=` | 仅本人；`{items,page,size,total}`，item 为 id/title/version/entryCount/availableCount/completedCount/unavailableCount；固定 created_at DESC、id DESC |
| `POST /api/v1/me/problem-lists` | `{title}`；服务端生成 UUID/owner/version，201 返回新题单摘要 |
| `GET /api/v1/me/problem-lists/{id}?page=&size=` | 本人题单标题、version、有界 items 与进度；items 按 position 排序 |
| `PATCH /api/v1/me/problem-lists/{id}` | `{title,expectedVersion}`；成功返回递增后的 version，竞争修改 409 |
| `DELETE /api/v1/me/problem-lists/{id}?expectedVersion=` | 版本明确且同事务删除本人 item/list；成功 204，不删除题目、提交或草稿 |
| `POST /api/v1/me/problem-lists/{id}/items` | `{problemSlug,expectedVersion}`；仅可浏览公共题，重复问题不建立第二项 |
| `DELETE /api/v1/me/problem-lists/{id}/items/{itemId}?expectedVersion=` | 可移除不可用占位；必须同时匹配 owner/list/item，不能按 itemId 单独删除 |
| `PUT /api/v1/me/problem-lists/{id}/order` | `{itemIds:[...],expectedVersion}`；必须是本题单全体 itemId 的无重复完整排列，拒绝缺失、追加及跨题单 ID |

题单摘要与页内 items/进度在同一只读 REPEATABLE_READ 事务中查询。可用 item 返回 `itemId/position/available=true/problem`，problem 仅为 slug/title/difficulty/tags/judgeVersion/completed。不可用 item 仅为 `itemId/position/available=false`。进度为 completedCount、availableCount、unavailableCount；分母只含当前可用题目，界面同时显示不可用数量，避免把已归档题继续显示为未完成。

写事务固定顺序：复核并锁定当前账号/会话 → 以 `(id,owner_id)` 锁题单 → 校验 expectedVersion → 校验 item 与公共可见性 → 执行变更 → version+1。新题单限额检查也在账号锁之后执行。添加题目对当前公共题目行使用共享锁，防止可用性验证后、写入提交前被并发归档；未来内容归档流程须遵守相容锁顺序。

position 始终规范为 1..N。移除及排序在题单锁内分两步更新：先把既有 position 整体移到不与 1..N 重叠的临时区间，再写入新位置。两步同事务，唯一约束始终启用；任一步失败全部回滚。不会用删除再重建题单或禁用唯一约束实现排序。

### 3.2 官方题单与本人进度

| 接口 | 可见范围 |
| --- | --- |
| `GET /api/v1/official-problem-lists?page=&size=` | 匿名可读 ACTIVE 官方题单摘要，只有公共元数据，不含任何个人进度 |
| `GET /api/v1/official-problem-lists/{id}?page=&size=` | 匿名可读公共题项与不可用占位，按 position 排序 |
| `GET /api/v1/me/official-problem-lists/{id}?page=&size=` | 当前登录用户的同一题单、items 和本人进度，在同一事务快照中返回 |

只增加以上官方公共 GET 的明确 Security matcher；不得放行 `/me` 或任何 POST/PUT/PATCH/DELETE。公共响应不混入可缓存的个人进度；登录后的页面使用受保护的本人视图，避免先读题项、后读不同版本统计造成短暂矛盾。官方题单 ARCHIVED 时接口统一 404。

AC 查询用带 user_id 与当前 judge_version_id 的 EXISTS，避免多次 AC 导致重复题项或错误 COUNT。完成一题后重新读取相关进度；WebSocket 通知只触发读取，不能凭客户端 verdict 或通知更新数据库进度。

### 3.3 本人提交历史

`GET /api/v1/me/submissions?page=&size=&problemSlug=`：固定 `created_at DESC,id DESC`，COUNT 与页查询处于同一只读 REPEATABLE_READ 事务；按本人 user_id 过滤，不允许指定其他用户。problemSlug 为可选精确范围，不能借筛选枚举受限题目或他人提交。

列表响应为 `{items,page,size,total}`。每项仅包含 submissionId/createdAt/language/processingStatus/statusVersion/verdict/judgeVersion/problem。judgeVersion 是这次提交绑定的历史版本号；只有 FINISHED 才返回 verdict。problem 仅在题目当前公共可浏览时含 slug/title，否则为 null。列表不返回源码、隐藏用例、dataset/image hash、attempt 信息或详细诊断。

点击历史项沿用现有所有者 `GET /api/v1/submissions/{submissionId}` 状态详情及安全 CE 诊断；本单元不扩大它的字段，也不增加修改、删除历史记录或他人源码入口。题目版本变化不会改变历史 verdict/judge_version_id，旧 AC 仍可在个人历史中看到，但不会证明当前版本完成。

## 4. 服务端作答草稿与 CAS

草稿按用户、题目和 JAVA_21 唯一保存；它是编辑状态，不是正式提交或 AC 事实。

- `GET /api/v1/me/problems/{slug}/draft?language=JAVA_21`：可用公共题没有草稿时返回虚拟 `{language,sourceCode:null,version:0,updatedAt:null,editable:true}`，GET 不创建行。已有本人草稿返回 language/sourceCode/version/updatedAt/ editable。题目不可用而本人确有该草稿时，可只读返回本人的源代码并令 editable=false；不返回受限题目的标题、题面或当前版本。既无本人草稿又无可浏览题目统一 404。
- `PUT /api/v1/me/problems/{slug}/draft`：`{language,sourceCode,expectedVersion}`；sourceCode 可以为空或不完整，必须为可表示的 UTF-8 文本，不含 NUL，最多 65,536 UTF-8 字节；只允许 JAVA_21，expectedVersion 为非负、安全可表示整数。
- expectedVersion=0 仅表示首次 INSERT；行已存在则 409，不能转成 UPDATE。更新必须包含 `(user_id,problem_id,language,version=expectedVersion)`，原子写入 source_code 并 version+1；更新 0 行按本人草稿状态判定冲突，不用覆盖式 UPSERT。
- 初始持久化 version=1；成功返回最新 version/updatedAt。冲突返回 HTTP 409 与固定业务 code，不返回其他用户内容；客户端通过受保护 GET 取最新本人草稿。不能用 200 加冲突字段掩盖失败。
- 每次写在同一事务先调用 `requireCurrentWrite(userId)`，然后锁定可浏览公共题目，再执行 INSERT 或 CAS。归档与不可见题目拒绝新保存；保留已有草稿数据。草稿不绑定 judge_version，版本变化不会把旧代码标成已完成。
- 正式提交仍使用客户端点击当时的源码字符串；草稿保存失败、草稿后来更新、另一个页面冲突都不能篡改已建立的 Submission 快照。未来自测也应复制点击时的代码与自定义输入，不能在执行时再读取草稿。

JSON 接口的 version/expectedVersion 限制到 JavaScript 安全整数范围，数据库 CHECK 与递增前检查一致，溢出返回明确冲突而不能绕回。题单 version 采用同样规则。

## 5. 前端交互

新增本人题单、官方题单及历史页面，沿用账号入口和实际 slug 做题路由。题单修改期间禁用重复提交，409 后重新读取题单并明确提示用户再次选择；不静默合并排序或删除。

工作台加载本人草稿后才启用自动保存；没有服务端草稿时显示当前题目的初始模板。建议编辑停止 1 秒后保存，单个编辑器只允许一个保存请求在途；期间新输入保存在本页，前一个响应只推进 base version，不能用它替换新输入。网络错误保留本页代码和 dirty 标记；显示“未保存”，禁止假报保存成功。退出、切题与卸载清理保存计时器和响应代次；服务端已提交的保存保留，但迟到响应不能恢复前一用户的代码。

409 时暂停自动保存，保留当前文本，显示两个明确动作：

1. **载入服务端草稿**：读取最新本人草稿，在用户明确选择后替换编辑器并同步 version。
2. **保留本页代码**：继续保留和编辑本页内容，自动保存保持暂停；允许复制或保存到本地。若用户随后明确选择保存本页版本，先读取最新 version，再以该版本执行 CAS；再次竞争仍显示 409，不提供绕过版本检查的强制写入。

提交按钮复制点击时本页代码。题单中的 unavailable 占位没有做题链接；本人历史的不可用题项仍可打开自己的提交状态，但不能进入新提交。自动保存、进度加载和历史页翻页都复用请求代次检查，避免旧响应覆盖新页、切题或退出后的状态。

## 6. 验证与交付门禁

| 范围 | 必须有的实际证据 |
| --- | --- |
| 私有边界 | 两个真实账号；甲的题单读取、改名、添加、移除、排序、删除和草稿不能由乙操作；非法/不存在/他人 ID 统一 404，响应没有受限元数据 |
| 会话/CSRF | 缺失与错误 Origin/CSRF 拒绝；停用、退出与改密后保存和题单变更拒绝；在事务阻塞期间撤销会话的竞争不能越过校验 |
| 排序事务 | 两个请求同 expectedVersion 只有一个成功；唯一约束、完整排列、重复/遗漏/跨题单 item 拒绝；中途数据库错误全部回滚 |
| 进度 | 本人当前版本真实 AC 计 1；多次 AC 不重复；他人 AC、旧版本、自测、WA、QUEUED、SYSTEM_ERROR 不计；版本切换后统计更新，旧提交保持不变 |
| 不可用项 | 归档与没有当前版本后列表不泄露详情；保留占位、可以由本人移除；新增项、草稿写和正式提交都重新校验可用性 |
| 草稿 CAS | 首次 INSERT 竞争与已有行同版本竞争各只有一个成功；第二个返回 409，不覆盖；空/不完整代码可保存，超字节、非法语言/NUL 拒绝；跨用户/题目隔离 |
| 前端 | 两个真实浏览器页面复现冲突，分别验证载入/保留动作；保存期间继续输入不丢失；退出/切题迟到响应无效；提交快照不随之后编辑改变 |
| 历史 | 真实本人记录分页顺序、相同 created_at 的 ID 排序、total 一致；不能指定乙；白名单不含源码、隐藏测试、hash 与原始平台错误 |
| 迁移/权限 | 新迁移保留已有账号、session/token/quota、题目版本、Submission/Task/attempt/Outbox 全部旧事实；生产不伪造题单、草稿或 AC；API 官方写、Worker 新表访问与 API 隐藏内容访问拒绝 |
| 目标环境 | 新鲜固定 Linux clean verify 与前端全门禁；精确产物在隔离栈真实浏览器回放；日志不含草稿/提交源码、口令与邮件令牌；队列/沙箱/自有资源闭环清理 |

验证只使用一次性数据库、测试账号和原创 fixture。已有 M1 并发配额、取消、MQ/Worker/通知与账号撤销回归必须保留。本设计文件尚未执行以上验证，不可标为 VERIFIED。

## 7. 与后续 M2 的交叉依赖

- 题目内容与公共审核版本单元必须提供真正的公共可见性/审核状态，届时替换本单元复用的公共谓词；不得让新增私有草稿或未审核题通过 ACTIVE 分支泄漏。
- 正式公共题完整内容还缺私有参考程序、正式沙箱验证和官方题解对象；题单读取不会完成这些发布条件。
- 官方题解解锁与提前查看确认可以复用本人的当前版本 AC 查询，但须另建学习查看事实；本单元不会把“已查看题解”写成 AC。
- 自测需独立任务/配额/快照和短期结果策略；不能写正式 Submission 的 AC 来省略实现。
- SMTP 真实服务商投递、可靠邮件重试及后续 Redis 分布式支持分别验收；题单和草稿完成不能代替这些门禁。
- M3 班级题、公开个人题单、排行榜、Redis/ES、管理员维护界面及新依赖不属于本单元。

本单元固定 Linux、真实浏览器和审计已通过；题目内容、自测、题解与真实邮件投递等剩余事项未闭环前，完整 M2 仍保持 IN_PROGRESS。
