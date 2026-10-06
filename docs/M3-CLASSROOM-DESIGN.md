# M3 班级与教学闭环设计

状态：IN_PROGRESS；2026-10-05；起点 feat/m2-accounts / d228e3a。依据 Requirements 4.1、6、7、8、9，D-015/D-017/D-038/D-041。M2 VERIFIED 保持；本设计和首单元不代表完整 M3 VERIFIED。

## 1. 分阶段交付

1. 班级与成员：创建、邀请码、角色、退出/移除/恢复、双向转让、归档/恢复、空删除以及可用页面。
2. 班级私有题：复用作者不可变验证快照，班级拥有、created_by 保留；参考和独立题解均 PASSED 才发布。公共申请复制独立候选，审核发布留 M4。
3. 作业：草稿/定时开始、成员快照、已有真实 AC 的 PRECOMPLETED、真实正式尝试、迟交/硬截止及题解策略。
4. 教师关联查询和完整四门禁：仅本班作业提交可见；无关私人源码不得经学习 history 或 userId/problemId 查询泄露。

后续单元不预建空表或伪路由；每一单元扩展实际引用和删除保护后再开放写路径。M4 管理、Redis/ES 和 M5 部署不在本次范围。

## 2. 首单元权限矩阵

所有接口复用普通账号认证、精确 Origin/CSRF、当前 MySQL sid；私有响应 no-store。角色从当前成员行读取，不写进 JWT。

| 资源/动作 | OWNER | ASSISTANT | MEMBER | LEFT/REMOVED/非成员 |
| --- | --- | --- | --- | --- |
| 我的班级列表 | 本人关系 | 本人关系 | 本人关系 | 仅本人关系摘要，不能读班级详情 |
| 班级详情/有效成员名单 | 是 | 是 | 是 | 404 |
| 关闭/轮换邀请码、改名 | 是 | 403 | 403 | 404 |
| 任免助教、移除、恢复成员 | 是 | 403 | 403 | 404 |
| 退出 | ACTIVE 班级须先转让/归档 | 是 | 是 | LEFT 重放成功；REMOVED 不可变 LEFT |
| 提出/撤回转让 | 是 | 403 | 403 | 404 |
| 接受转让 | 仅当前目标有效成员 | 仅当前目标有效成员 | 仅当前目标有效成员 | 404 |
| 归档/恢复/空删除 | 是 | 403 | 403 | 404；归档 OWNER 退出后保留恢复管理入口 |
| 有效邀请码加入 | 已有效成员幂等返回 | 已有效成员幂等返回 | 已有效成员幂等返回 | LEFT 恢复 MEMBER；REMOVED 统一无效码响应 |

名单只返回 userId/username/role/status，不返回邮箱、sid、口令、学习历史或源码。普通成员只看 ACTIVE 名单；OWNER 可查看离开记录。邀请码仅 OWNER 的显式轮换响应返回一次，不在详情、列表或日志中返回；丢失可轮换。

归档历史只读；只允许 OWNER 恢复、空删除和用户退出。OWNER 归档后退出不转让所有权：classroom.owner_id 保留，成员为 LEFT；其恢复操作原子恢复自身 OWNER/ACTIVE。该管理例外不授予退出 OWNER 读取私有题/成员详情权。运行中永远有一个 ACTIVE OWNER。

后续单元的资源授权基线（当前未实施）：

| 资源 | 当前有效OWNER/ASSISTANT | 当前有效MEMBER | LEFT/REMOVED/无关班级 |
| --- | --- | --- | --- |
| 本班私有题元数据/题面 | 维护/读取；归档只读 | 学习读取；归档只读 | 不可读 |
| 本班隐藏测试/私有参考 | 教学维护专用入口，不能借公共题或正式提交接口读取 | 不可读 | 不可读 |
| 官方题解 | 教学维护专用入口；学习入口仍带作业作用域 | 依当前作业IMMEDIATE/AFTER_AC/AFTER_DEADLINE；自由练习用默认策略 | 不可借个人题单/公共提前确认绕过 |
| 作业文案、开始、参与快照 | 本班教学写；归档禁止新开始和修改 | 仅本人参与视图/真实提交 | 新访问拒绝；本人已存在历史的具体只读白名单在作业单元确认 |
| 作业完成状态/尝试统计 | 本班作业关联内查询 | 本人关联内查询 | 不授予任意userId的跨班查询 |
| 学生源码 | 仅本班作业正式尝试关系；PRECOMPLETED私人历史源码默认不扩大授权，作业单元确认 | 既有本人提交所有者权限 | 教師退出后的历史权限需另行明确，未明确前拒绝 |
| 私人题单/作答草稿/自测/无关正式历史 | 仅本人，教师角色不提供额外权利 | 仅本人 | 班级关系不改变M2 owner边界 |

班级私有内容发布时须增加独立当前资源可见性谓词和快照；本表不表示已交付后续功能，也不默许修改M2正式判题依据。出班立即失去私有内容权，永久正式提交的本人历史保留要求需与资源白名单共同实现。

## 3. 状态机与并发结论

- 班级 ACTIVE → ARCHIVED → ACTIVE；version 初始 1，每次实际班级/成员/转让变更 +1，范围为 JS 安全整数。
- 成员不存在 → ACTIVE/MEMBER；ACTIVE → LEFT 或 REMOVED；LEFT 由有效邀请码恢复为 MEMBER（不恢复旧助教权限）；REMOVED 只能 OWNER 恢复为 MEMBER。主键 (classroom_id,user_id) 保证永久同一关系，joined_at 保留，updated_at 记录变化。
- 角色 MEMBER ↔ ASSISTANT 仅 OWNER 操作；禁止修改/移除自身 OWNER；OWNER 更替只走转让接受。
- 转让 PENDING → ACCEPTED 或 WITHDRAWN；一班至多一个 PENDING。目标退出/移除、归档时同步撤回。接受锁内复核当前 owner、目标 ACTIVE、版本及 PENDING；原 owner 降 ASSISTANT、新 owner 升 OWNER、classroom.owner_id 更新同事务。撤回/接受竞争只有一个成功。
- 关闭/轮换邀请码不影响已加入关系。随机 192 bit URL-safe 码，仅存 SHA-256；当前码唯一。加入必须在锁内重新比较摘要、enabled、ACTIVE；关闭/轮换胜出后旧码不能加入。
- 空删除严格指从未有其他成员记录（包括 LEFT/REMOVED），且没有业务引用。转让历史也保留而阻止删除。首单元不存在私有题/作业表；后续必须增加权威引用外键和删除查询才能开放其写入。不能只统计 ACTIVE 成员把历史删掉。

## 4. 接口、版本与幂等

| 接口 | 合约 |
| --- | --- |
| GET /api/v1/me/classrooms?page=&size= | 本人关系列表（最多50），固定 created_at/id DESC |
| POST /api/v1/classrooms | {title,clientRequestId(UUID)}；201，owner+UUID request 唯一；重放返回同一班级，不重新生成邀请码 |
| POST /api/v1/classrooms/join | {inviteCode}；200；同一有效成员重放不递增版本 |
| GET /api/v1/classrooms/{id} | 班级摘要、本人角色、当前有效名单；OWNER 另含离开名单和 pendingTransfer |
| PATCH /api/v1/classrooms/{id} | {title,expectedVersion} |
| POST /api/v1/classrooms/{id}/invite | {enabled,expectedVersion}；true 轮换返回一次新码，false 关闭 |
| POST /api/v1/classrooms/{id}/members/{userId}/{action} | role: {role,expectedVersion}；remove/restore: {expectedVersion} |
| POST /api/v1/classrooms/{id}/leave | {expectedVersion} |
| POST /api/v1/classrooms/{id}/transfers | {targetUserId,expectedVersion,clientRequestId}；持久独立 transferId |
| POST /api/v1/classrooms/{id}/transfers/{transferId}/{accept\|withdraw} | {expectedVersion}；终态重放只返回该转让既有结果，不操作新的转让 |
| POST /api/v1/classrooms/{id}/{archive\|restore} | {expectedVersion} |
| DELETE /api/v1/classrooms/{id}?expectedVersion= | 空删除204 |

请求严格字段白名单；400 格式错误，401 会话失效，403 当前有效成员角色不足，非法/不存在/非成员资源统一空404；409版本/状态冲突固定code，不泄露他人资源。无效/关闭/过期轮换/REMOVED邀请码统一空404。加入使用既有有界进程限流设施（每账号每分钟10次）；不宣称分布式限流。创建最多100个本人拥有班级，每班最多1000关系，有界分页和请求。

普通 CAS 动作响应丢失后 GET 复核，不自动用新version再写。创建 clientRequestId 重放校验标题相同，否则409；转让同一request须同目标。删除后创建重放不复活已删资源；保留请求凭证独立表，删除班级不删除凭证。接受重放只对原目标、撤回重放只对原提出者可见；权限例外仅用于读该终态，不授予当前班级访问。

## 5. 事务、迁移与数据库权限

首单元追加 V15（不改V1–V14）：classroom、classroom_member、classroom_transfer、classroom_creation_request。FK 保留账号和班级关系，CHECK约束状态/角色/version，唯一请求及唯一PENDING generated key。owner_id 为所有权权威；member.role 同事务维护，受限业务连接不能随意修改主键/created_by。

锁顺序：账号行（通常仅当前操作者；提出转让须先按user_id升序锁提出者和目标两行）→ 当前session（AccountService.requireCurrentWrite复核）→ classroom → member → transfer/request。转让INSERT的目标账号外键会隐式加父行共享锁，故不能在班级锁内才锁目标；否则目标加入持账号锁再等班级时会形成死锁。其他写只涉及本人新外键；接受更新owner_id的目标就是当前操作者。目标状态读已锁账号，接受由目标本人先锁账号；停用后过滤器和写事务即拒绝该账号。班级服务使用 READ_COMMITTED，每个班级业务修改先锁同一classroom行再读关系；成员授权使用锁定读，避免邀请码定位的先前快照使后续限额或名单过时。create 账号锁串行化额度/请求；join 查摘要定位后锁班级重新校验。成员表 generated active_owner 的唯一约束额外拒绝双有效OWNER；接受先降原OWNER再升目标，同事务对外不可见中间状态。不增加 Worker 表权限，不修改正式判题/MQ/共享额度。

API SELECT/INSERT 四张表；classroom UPDATE仅title/owner_id/status/invite摘要/enabled/version/updated_at，member UPDATE仅role/status/updated_at，transfer UPDATE仅status/closed_at；classroom/member空删除权限，request/transfer无DELETE。迁移账号DDL/GRANT与业务账号分离。生产权限、测试权限、独立回放权限同步；真受限API连接执行接口，Worker SELECT新表必须实际拒绝。

## 6. 后续作业/私有题关联

私有题独立班级归属与不可变判题/题解版本；所有资源查询显式classroom作用域和当前ACTIVE关系，退班立即拒绝。归档保留有效成员历史只读，禁止新发布/班级提交。正式提交创建时原子附加 assignment attempt 关系，Worker只处理原快照；成绩由真实FINISHED/AC聚合，不通过客户端或自测赋值。

定时开始使用数据库时间和作业行锁，幂等创建参与快照；锁序需与classroom归档统一。已开始集合/已有AC/迟交/题解策略锁定；延期仅升级迟交为按时。归档同事务停止未开始作业、提前结束活动作业并记原因；恢复不复活旧作业。PRECOMPLETED绑定真实本人对应依据AC、尝试0。教师入口从本班作业关系查询，不能扩大me/history。历史私人PRECOMPLETED源码授权及精确时间口径在作业单元单独解决，未决定前不扩大访问。

## 7. 验收矩阵

| 范围 | 必须实际验证 |
| --- | --- |
| 身份/作用域 | 三账号两班同人不同角色，所有角色动作矩阵、非法/不存在/他人响应、no-store字段白名单 |
| 成员 | 并发加入仅一关系、LEFT同记录恢复为MEMBER、REMOVED不能换码绕过、OWNER恢复及助教任免 |
| 转让 | 唯一PENDING、目标当前关系、接受/撤回/退出/移除竞态、单OWNER、旧请求重放不影响新转让 |
| 生命周期 | ACTIVE OWNER退出拒绝、归档禁止写/加入、归档OWNER退出和恢复、空删除与历史保留 |
| 安全 | Origin/CSRF、停用/撤销，过滤后事务内撤销、邀请码摘要与日志，API列级权限和Worker实际拒绝 |
| 升级 | V14有历史数据升级V15不修改旧事实；空库约束和真实故障事务回滚 |
| 页面 | 多账号实际浏览器创建/加入/助教/移除恢复/转让/归档，身份变化与乱序响应不恢复旧数据，冲突明确GET复核 |
| 交付 | 固定Linux新源码/JAR SHA与真实回放关联，M0–M2回归、队列/权限/日志核查、唯一fixture精确Stop和daemon零残留 |

首单元证据保存docs/evidence/m3-classroom和M3-CLASSROOM-VALIDATION.md；无实际证据不得标VERIFIED，也不提交推送未通过单元。完整M3仍需四项最终门禁。
