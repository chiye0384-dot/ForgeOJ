# M2 官方题解访问

访问单元 VERIFIED（2026-10-03）；依据 Requirements 7.3 和 D-014。这里只实施学习者读取与私有提前查看记录，不提前实现 M4 审核身份。

`official_problem_solution` 是受控发布产生的不可变判题版本快照，思路与 JAVA_21 源码独立于作者参考程序。API 只有 SELECT，Worker 无访问权限，迁移无 seed，普通用户没有发布路由。未来受控发布事务必须在参考/题解正式沙箱验证和不可变审核通过后写入；本阶段不从作者草稿或 AC 源码自动生成题解。测试中的合成快照仅在可丢弃数据库使用，不是已审核正式内容。正式内容生命周期仍待实现。

`GET /api/v1/me/problems/{slug}/solution` 返回当前 judgeVersion、access 和 solution。access 为 UNAVAILABLE（没有快照）、LOCKED（尚未解锁）、AC（本人该当前版本 FINISHED/AC）、EARLY_VIEW（本人明确确认过该版本）。前两者 solution=null，不查询题解正文。解锁返回仅 idea/language/sourceCode，不含参考程序、测试、他人代码或内部 ID。不存在/归档题统一空 404，所有响应 no-store。

`POST /api/v1/me/problems/{slug}/solution/early-view` 仅接受整数 judgeVersion 与布尔 confirmEarlyView=true。先锁当前账号/会话，再 FOR SHARE 当前题和版本；旧版本确认 409，不替用户确认新版本。无题解不留记录；已有 AC 不制造提前查看记录。其他情况通过 INSERT IGNORE(user_id,judge_version_id) 首次记录，重复与并发确认幂等，不改首次时间。API 对记录只有 SELECT/INSERT，无 UPDATE/DELETE；记录不公开、不限制继续提交，不制造 Submission 或 AC。旧版本记录保留但不会解锁新版本。

读取采用可重复读事务保持版本、解锁和正文一致；写事务重新检查会话防止已通过认证后撤销竞争。未来所有修改当前版本/发布快照的流程必须遵守 problem 行锁。用户界面两步展示“可能影响独立思考”和确认，取消不请求写接口；当前 AC 终态只触发重新读取权威接口，不能凭客户端 verdict 解锁。退出/卸载/切题清空正文并忽略迟到响应。正文以文本渲染，不执行 HTML 或源码。

保持现有题目身份：slug 只检查非空及长度最多 80，所有 SQL 参数绑定，不用新的格式正则拒绝既有题目。当前 problem 模型只有兼容的公共示例；M3 引入班级私有题时，读取和确认均必须追加权威可见权限及作业开放策略，不能沿用 ACTIVE 即公开的推断。

真实 MySQL/Tomcat 权限、用户/版本隔离、幂等/并发/回滚/撤销、迁移保留、旧 slug 与前端确认/乱序均通过；最终固定 Linux 269/45、实际两账号提前查看/取消/真实 AC 自动解锁、降级与独立审计清理见 [验收](M2-SOLUTION-ACCESS-VALIDATION.md)。完整 M2 仍 IN_PROGRESS。
