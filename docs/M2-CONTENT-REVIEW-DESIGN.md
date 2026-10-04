# M2 不可变送审与撤回

2026-10-04，基线d6393ce，状态VERIFIED；依据Requirements6.5、7.2/7.3及D-022，完整M2仍IN_PROGRESS。普通作者可送审和撤回自己的版本，CONTENT_REVIEWER身份、批准/驳回与受控发布保持M4边界，本单元不制造公共题或官方题解。

V12新增content_review记录，绑定同一owner/draft/version的既有PASSED验证job和不可变snapshot。复合外键约束绑定，快照和测试不重复复制；引用的冻结内容不能修改。reviewNo在同一草稿锁下递增，状态PENDING→WITHDRAWN，独立version0→1，唯一generated active_draft_id保证同一草稿只有一条待审。API仅可更新状态/version/撤回时间，Worker没有该表权限。

POST `/api/v1/me/authored-problems/{draft}/reviews` 精确body `{expectedVersion,validationJobId,requestId}`；复核当前写会话，按quota→draft锁顺序序列化owner请求，检查当前DRAFT和相同版本FINISHED/PASSED/两AC。owner/requestId幂等；换草稿/版本/job返回409，重放旧请求只返回旧送审（撤回/归档后也如此），不会隐式重送。当前待审状态通过authoring DTO显示UNDER_REVIEW，底层草稿status仍DRAFT；所有保存、逐条/ZIP测试、归档、删除与新验证统一拒绝409，必须先撤回。送审/撤回不改内容版本，独立reviewVersion完成生命周期CAS；内容编辑仍按原草稿CAS，修改后必须重新验证。

POST `.../reviews/{review}/withdraw` 精确body `{expectedVersion,expectedReviewVersion}`，必须本人且绑定版本；PENDING只能version0撤回，WITHDRAWN仅接受原version0或当前1的幂等读取，不能影响后来的待审记录。撤回恢复编辑，旧review永久保留。GET列表page/size20最大50和GET单条仅owner可读、no-store；详情返回冻结content/testCount及review白名单，不返回隐藏输入输出、凭据或其他作者源码。历史详情始终从snapshot读，不能被后续草稿修改替换。

前端显式选择当前版本已通过的验证，再送审；仅已保存内容进入送审，本地未保存编辑保留。审核中编辑/新验证禁用，可查看冻结历史和显式撤回；请求不确定重试复用requestId，409不自动覆盖，切草稿/身份/卸载忽略迟到私有响应。

验收需真实HTTP/MySQL的通过门槛、同请求/竞争送审、全部待审写禁止、撤回/再送审隔离、旧快照保留、owner/Origin/撤销、迁移与列权限；前端身份/请求/历史回归，固定Linux全量和相同构件实际浏览器送审→禁止修改→撤回→修改→旧快照，现有判题/队列/日志/权限/精确清理。本轮结果见 [验收记录](M2-CONTENT-REVIEW-VALIDATION.md)；完整内容与 M2 保持 IN_PROGRESS。
