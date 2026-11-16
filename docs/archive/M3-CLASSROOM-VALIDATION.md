# M3 班级与成员首单元验收

2026-10-08 总门禁补充：完整M3现已 [VERIFIED，4/4 PASS](M3-GATE-AUDIT.md)。本文件以下保留该单元执行时的范围、结果及失败轮次；最终整体状态以总门禁为准，不将各轮次重新描述为同一次运行。

状态 **VERIFIED（首单元）**；2026-10-06。起点 feat/m2-accounts / d228e3a；完整M2 VERIFIED保持，完整M3 IN_PROGRESS。设计见 [M3-CLASSROOM-DESIGN.md](M3-CLASSROOM-DESIGN.md)。班级私有题、作业、教师提交关联尚未实现；首单元验证不能替代完整四门禁。可提交事实见 [证据目录](evidence/m3-classroom/README.md)。

## 1. 实现范围

V15追加班级、永久成员关系、不可变请求凭证和转让历史；不改V1–V14。API普通身份复核、班级作用域角色、CAS、邀请码摘要、LEFT/REMOVED、显式接受转让、归档/恢复、严格空删除。新增 `/classrooms` 可用页面和导航，保留原入口。Worker仅测试schema helper同步V15，生产执行权限未扩大。

## 2. 已执行检查和失败轮次

- 本机编译：Java21、Maven3.9.14，API82个生产文件编译 BUILD SUCCESS（target编译日志）。编译不代表可用验收。
- 首次 `mvn -o ... test` 因既定Testcontainers等依赖缺失未执行测试；日志 `target/m3-classroom-focused.log`。使用正常联网依赖解析后，第一轮班级真实MySQL 6项零失败/错误/跳过，`target/m3-classroom-focused-online.log`。
- V15唯一有效OWNER约束和V14升级后，组合7项零失败/错误/跳过，`target/m3-classroom-focused-2.log`。升级逐列/字节比较全部旧业务表，额外保留真实旧自测payload/job/attempt/output fixture；V15只新增空表。
- 前端首轮格式检查发现新增页面未格式化，第二轮mock类型规则拒绝未指定类型；均修正。第三轮受限Windows临时目录EPERM，15 suites均未执行（不能说测试通过）；日志 `target/m3-classroom-frontend-3.log`。
- 允许正常临时文件访问后，前端74项/15 suites，类型/lint/格式/生产构建通过，`target/m3-classroom-frontend-4.log`。随后修正退出成功后的页面刷新提示和名称预填；最终固定Linux将再次验证最终源码。
- 复核发现转让INSERT目标外键隐式锁与目标加入的锁序倒置；实现改成先升序锁双方账号，再复核sid、锁班级。新确定性测试锁住目标账号，并用另一个真实连接NOWAIT证明提出转让此时未占班级行。初版Mockito callRealMethod用于MyBatis接口代理，使该测试后续认证返回401；9项中1失败，另1升级通过，`target/m3-classroom-focused-3.log`。独立诊断保留 `target/m3-classroom-lock-diagnostic.log`。
- 将该测试委托到真实SqlSessionTemplate后，确定性锁测试单项真实MySQL通过，`target/m3-classroom-lock-diagnostic-2.log`，未删除断言或模拟数据库结果。

## 3. 最终构件与验证

| 检查 | 实际结果 | 本地原始证据 |
| --- | --- | --- |
| 最终班级/升级聚焦测试 | 10项，零失败/错误/跳过 | `target/m3-classroom-focused-final.log` |
| 最终固定Linux API | 192项、38 suites，零失败/错误/跳过，BUILD SUCCESS | `target/forgeoj-linux-20261006-080830-db84cf2c` |
| 固定Linux Worker | 133项、23 suites，零失败/错误/跳过，Maven BUILD SUCCESS；包装脚本随后失败，见下节 | `target/forgeoj-linux-20261005-221349-6bab490d` |
| 最终固定Linux前端 | 75项、15 suites，type/lint/format/生产build通过 | `target/forgeoj-linux-20261006-085751-6072a86f` |
| 实际浏览器前端输入 | 59个文件与最终测试构建及当前源码完全相同 | 最终replay `frontend-runtime.sha256` |
| API/Worker完整业务与测试输入 | API147、Worker110个匹配（共享输入不可重复计为独立文件） | `verification.json`；含当前新增文件清单检查 |

API JAR SHA256：`1a2c1f6e21313aa1ac80bcd10d8c2265a7642c9c9606bd109d9fe2481f38f962`。

Worker JAR SHA256：`6bd5fddfd5d2248fd095bc1886747e80e141e91cd7993d46697c1cbfae9c51ab`。

两个不同构建目录的JAR只作逐字节复制，组合在 `target/m3-classroom-runtime-20261006-0821`，并记录 `origins.json`。API取修正锁序后的最终构建；Worker生产代码未改，全部Worker源码/测试/迁移/契约输入与已完成133项的构建相同。不能声称一次All包装脚本全绿。最终验证工具同时读取Surefire XML、BUILD SUCCESS、哈希、当前文件清单、实际浏览器前端及cleanup。

## 4. 实际运行事实

接受的最终replay：`target/forgeoj-e2e-20261006-084053-74bfeb1b`。Start曾因npm连接重置未完成；只恢复既有frontend和本地mailbox，保留同一API容器。后续WorkerStart增加 `--no-recreate`，避免依赖配置变化重建API丢日志。最终完整API/Worker日志均保留。

- HTTP三账号62项：创建幂等/分页、班级作用域角色及另一班隔离、轮换旧码失效、LEFT重新加入为MEMBER、REMOVED禁止自助恢复、负责人恢复、显式转让/旧终态重放、归档负责人退出恢复、严格空删除/删除后创建请求不可复活。HTTP班级探针前后正式Submission均为0。
- 真实页面三账号：`learner`创建原创班，`other-learner`加入/退出/重入、设为助教/移除/加入被拒/负责人恢复为MEMBER；`classroom-fixture`加入并显式接受转让，原OWNER成为ASSISTANT。新OWNER归档/退出后仅有恢复重新加入入口，恢复为ACTIVE OWNER，再归档至版本15。实际DOM、SQL、截图一致；没有注入Vue状态或伪造截图。
- 真实SQL三个保留班级均恰有1个ACTIVE OWNER，且与classroom.owner_id匹配。实际38项SQL权限拒绝，其中9项班级新增拒绝；Worker无班级成员、转让或创建凭证权限。API无Docker socket或Worker/migrator凭证。
- 既有八verdict、公开题库、本人学习记录、同源/撤销/排队取消，以及真实正常WebSocket和阻断WebSocket后的轮询页面QUEUED→FINISHED/AC通过。正常路径还实际观察到RUNNING。11正式提交/10完成/1取消，11发布Outbox，7队列ready/unacked为0；request→submission→task→outbox→attempt→commit→ACK链路匹配。
- 日志未出现源码/隐藏数据/fixture密码/Token/Cookie/邀请码哨兵。前端回退端口故意阻断WebSocket的ECONNREFUSED和npm启动失败日志保留，不声称日志完全无错误。
- 两轮replay均精确按项目拥有权Stop；2026-10-06 09:20:47 +08:00实际daemon检查项目容器/卷/网络/Worker镜像、builder、Testcontainers、managed sandbox全为0。原始报告保留，不操作真实开发数据库或外部邮件。用户未跟踪旧交接与忽略SMTP文件SHA256保持不变，未暂存。

本轮未重放生产Worker SIGKILL或QQ外部SMTP；既有历史证据保持其日期，不升级为本轮事实。

## 5. 其余失败轮次及修正

- 首次Linux All运行在转让锁序修正前冻结，API191、Worker133的Maven均通过。运行期间错误地编辑了正在挂载/流式读取的verify-linux.sh，Maven结束后shell偏移造成 `ist: command not found`，包装脚本失败。旧API构件未用于最终replay；后续单独Api scope重建最终API。以后不得修改正在运行的挂载脚本。Surefire既有fork关闭警告保留，未据此伪造零警告。
- 聚焦Windows最终10项通过，但宿主休眠跨夜使墙钟跨度约9小时；不声称连续运行用时。
- 第一轮浏览器原生confirm阻塞，改成可取消的页面内确认；新增测试验证未确认不发写、刷新取消旧确认、明确确认携带当前CAS。登录提示链接改到实际登录工作台。后续最终75项与真实页面通过。
- 第一轮学习probe顺序错误：取消前仅8提交，原脚本要求9；重跑又发现此前已创建草稿影响version0断言。只清理该一次性环境中探针自产题单与特定哨兵草稿；最终全新replay按Matrix→Permissions→Learning顺序通过，不修改正式数据或断言。
- 初次浏览器轮前端重启改端口并导致API重建，早期正式提交日志丢失；该轮仅保留预验证事实并精确Stop，最终全新replay完整重做，不能沿用缺失日志作完整审计。
- 最终前端/新replay首次npm下载ECONNRESET；均重试并成功，无依赖版本升级或绕过锁文件。
- 最终审计首轮拒绝实际观察到的RUNNING中间态；允许严格QUEUED/SUBMITTING→可选RUNNING→FINISHED/AC。第二轮班级审计遇到MySQL JSON EXISTS返回true而不是数字1；接受true或1，同时保持activeOwners必须数字1。原失败日志和最后成功日志分别保留，业务断言未删除。
- 默认暂存空白检查仅提示两个新测试文件各有末尾空行。保留已经构建验证的源字节；单次 `git -c core.whitespace=-blank-at-eof diff --cached --check` 通过其余空白检查，未修改仓库或全局Git配置。

## 6. 复核与后续

```powershell
node tools/validation/Verify-M3ClassroomEvidence.mjs target/forgeoj-linux-20261006-080830-db84cf2c target/forgeoj-linux-20261005-221349-6bab490d target/forgeoj-linux-20261006-085751-6072a86f target/forgeoj-e2e-20261006-084053-74bfeb1b
```

本地原始target不是远程发布产物；提交的脱敏JSON和截图便于审阅，复跑应使用新目录、新fixture及实际哈希。部署必须先以migrator应用V15，再启动新API，Worker最小权限保持。

下一单元是班级私有题：复用作者PASSED不可变快照，保留created_by与班级拥有权；开放写前必须增加实际引用、外键和空删除保护。作业/成员快照/教师关联/完整四门禁后续完成；M4审核发布与Redis/ES、M5部署仍PLANNED。限制见L-039，首单元VERIFIED不等于RELEASED或RESUME_READY。
