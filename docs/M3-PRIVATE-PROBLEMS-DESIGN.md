# M3 班级私有题设计

2026-10-08 总门禁补充：完整M3现已 [VERIFIED，4/4 PASS](M3-GATE-AUDIT.md)。本文件以下保留该单元执行时的范围、结果及失败轮次；最终整体状态以总门禁为准，不将各轮次重新描述为同一次运行。

2026-10-06，本单元 VERIFIED；起点 `feat/m2-accounts / 089d777`。实际结果见 [验收](M3-PRIVATE-PROBLEMS-VALIDATION.md)。完整 M3 仍需作业及教师关联查询，保持 IN_PROGRESS。本单元不包含 M4 公共审核发布。

## 归属和不可变依据

发布者必须是当前有效 OWNER/ASSISTANT，只能从自己的当前 DRAFT 发布。服务同时锁定会话、班级、成员、草稿，并校验所选 VALIDATE job 与本人、草稿、版本的精确绑定，且 FINISHED/PASSED、参考和独立题解均 ACCEPTED。输出预览不能代替验证。

V16 为 problem 增加 PUBLIC/CLASSROOM 与真实 classroom 外键，既有题默认 PUBLIC；追加 classroom_problem 的不可变发布绑定、created_by、幂等请求和自由练习题解策略。发布事务从验证快照复制新 problem、judge version 和压缩隐藏测试，保持摘要、资源和运行镜像；官方题解读取原不可变验证快照。每次发布是独立新题及 judge version 1，修订不覆盖旧依据。当前没有同一题覆盖版本或自动作废旧题；严重错误先归档旧题，再从修订草稿重新验证并发布，旧 AC 不能证明新题完成。

发布后归班级，保留 created_by。创建者退出、草稿编辑或归档均不改变班级内容。其他有效教学成员可以查看维护快照并复制到自己的个人作者草稿，修改测试/参考/题解、重新验证再发布。复制不继承 PASSED。个人作者草稿和个人自测、正式提交始终按 owner 隔离。

空班删除查询实际 problem.classroom_id，FK 在数据库再次禁止删除；归档的题和班级引用永久保留。发布表无 DELETE 权限，除生命周期 version 外无 UPDATE 权限；judge version/test case 不可修改，API 对正式隐藏测试仍无 SELECT 权限。Worker 未增加任何表权限，继续读原 formal snapshot 和自身已有执行表，不查班级身份或成员。

## 可见性与自由练习

所有班级读取按账号/session → classroom → 当前 ACTIVE member 顺序加锁，退出/移除后下一次读取、发布、提交或提前查看统一拒绝。OWNER/ASSISTANT 的身份每班分别判定；教学维护入口与普通学习入口分开。学习题面只选择 metadata，开放题解只选择独立 idea/code，不读取或返回参考/隐藏测试。公共题库、标签、公共详情、题单选题、公共题解、公共正式提交和自测明确要求 PUBLIC。

按本轮用户确认补充 D-014：自由练习默认 AFTER_AC；未 AC 可以先看到提醒，再通过明确 `confirmEarlyView=true` 提前查看，个人记录按 user/problem 唯一、只 INSERT。取消不发请求不留记录，错误/过期版本不返回内容；看过题解不制造 AC、扣分或限制继续提交。也可在发布时选择 IMMEDIATE。本单元题目不可变，problem identity 即对应唯一 judge version；新题独立解锁。作业 IMMEDIATE/AFTER_AC/AFTER_DEADLINE 尚未实现，下一单元必须加入作业作用域，不能用自由练习确认绕过作业策略。

班级 ARCHIVED：有效成员只读，禁止发布、复制、归档题、新正式提交、自测和新增提前查看记录；已有授权题解仍可读。题目 ARCHIVED：保留题面和已开放题解，禁止新正式提交、自测和新增提前查看。新请求不自动改变旧状态。

正式提交通过现有共享额度锁与原 enqueue 在同一事务创建 submission/task/outbox；Worker 只执行冻结依据。幂等重放须当前班级可提交且与题目/源码一致。队列中的已接受任务不因成员退出改写快照。退出后本人 submission 状态/诊断及自测本人快照按最小白名单保留；个人全部正式历史隐藏班级题当前题面、标题和链接，他人及教师没有自由练习源码权限。

## 接口及页面

根路径 `/api/v1/classrooms/{id}/problems`；所有返回 no-store。非法/不存在/非成员资源空404，当前角色不足403，版本/状态409，失效会话401，严格请求字段白名单。

| 方法/路径 | 行为 |
| --- | --- |
| GET 根路径 `?page=&size=` | 当前成员分页，最多50，含归档历史 |
| POST 根路径 | `{draftId,draftVersion,validationJobId,clientRequestId,solutionPolicy}`；201，当前教学成员；同请求同绑定重放同题 |
| GET `/{slug}` | 学习题面/公开样例/来源许可/资源 |
| GET `/{slug}/maintenance` | 当前 OWNER/ASSISTANT，私有参考与独立题解、测试数量 |
| POST `/{slug}/copy` | `{}`，复制为当前教学成员自己的新草稿，需重新验证 |
| POST `/{slug}/archive` | `{expectedVersion}`；204，CAS，只归档不删除 |
| GET `/{slug}/solution` | LOCKED/AC/IMMEDIATE/EARLY_VIEW；锁定时无内容 |
| POST `/{slug}/solution/early-view` | `{expectedVersion,confirmEarlyView:true}`，版本及身份绑定确认 |
| POST `/{slug}/submissions` | `{clientRequestId,language,sourceCode}`；202，原正式链路及共享排队上限3 |
| POST `/{slug}/self-tests` | 原 `{requestId,language,sourceCode,input}`；202，独立自测、不计正式 AC |

`/classrooms/:id/problems` 提供成员学习和按当前角色维护：分页选自己的验证草稿、双通过验证选择、发布再次确认、复制修订、归档确认、源码冻结正式提交、自测/输出、按需刷新结果，以及提前看题解再次确认。身份和路由变化清空私有数据，generation/unmount 丢弃晚到响应，409 明确刷新复核，不自动用新版本写。当前结果使用用户点击刷新，不新增私有题 WebSocket；既有公共题实时通知保留。

每班最多1000条永久发布记录（含归档），每人原100草稿边界仍有效。不是生产吞吐/容量结论。公共候选复制、作业定时/成绩/教师代码均不属于本单元。

## 必须实测

空库/V15历史迁移、双程序/用途/版本/本人绑定、同请求并发重放、失败中途回滚、教学角色与跨班隔离、助教退出、公共/题单旁路、当前session二次撤销、提前查看取消/确认/用户隔离、自测无AC、真实Worker正式AC、归档/删除保护、API列级拒绝及Worker拒绝新表、真实多账号页面、构件输入/哈希关联、七空队列及独立fixture精确清理。未通过前不得标 VERIFIED 或提交推送。
