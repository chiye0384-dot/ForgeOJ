# M3 教学作业记录验收

2026-10-08，feat/m2-accounts / 实现起点82afd00。**本单元 VERIFIED**。完整M3仍IN_PROGRESS，四项总门禁尚未汇总；不声称Release或RESUME_READY。

原生一次性MySQL新增3项测试先失败（缺失教学接口返回401），实现后全部通过，记录target/teacher-red.log及teacher-green.log。覆盖PRECOMPLETED原私人AC源码拒绝、正式关联读取、同题非作业/跨作业/跨班拒绝、OWNER/ASSISTANT/MEMBER/LEFT/REMOVED/无关/匿名/停用账号、降级即时失权、历史参与和归档、本人/教学成绩一致、分页边界与no-store。测试中受控终态fixture仅用于SQL/权限验证，不冒充真实Worker判题。

前端原生首次系统沙箱临时目录EPERM，未执行测试；测试类型ApiRequestError构造和Vue根props响应式包装修正。改为项目target临时目录后，4/5通过，一项立即DOM断言在Vue更新前执行；改为nextTick再断言，不删除权限断言。最终5/5通过，类型及lint通过。覆盖只读纯文本源码、刷新403清空、路由变化晚到源码丢弃、响应后身份变化丢弃、匿名和空正式尝试提示。

固定Linux后端：target/forgeoj-linux-20261008-110105-55b90a88，单次Backend driver BUILD SUCCESS，API221/42 suites、Worker133/23 suites，失败/错误/跳过均0，24:24分钟。作业17项含3新教学测试。旧迁移fixture的撤权/销毁阶段调度器日志包含预期连接与grants噪声，不把整份测试日志称为零告警。实际回放生产日志另查。

前端初轮target/forgeoj-linux-20261008-110112-966909c1已97项全部通过。随后在学生作业页补充正式代码教学可见的提示，最终重验target/forgeoj-linux-20261008-112857-b4f26f08：18 suites / 97 tests，typecheck/lint/format/build全部通过。后端输入不变。

API JAR SHA256：1fce68604e3845fbf7e8b3ea3bebcad4d4abaa0c1fa6e0cbcf1f467751ef0f16；Worker：58da20d24421b4c7c28065b64d2b424212f1ffa98cac24de6d14096ab79510dd。回放只用这两个构件。

首轮回放target/forgeoj-e2e-20261008-112700-e94f09d6公共八判型及权限/学习通过，页面正式提交0889b212-4b06-4ad3-a1da-401376f7297a真实RUNNING→FINISHED/AC，但观察工具未捕捉到原审计要求的初始QUEUED/SUBMITTING。保留public-observation-running.txt，不伪造队列观察、不改变原审计断言；精确清理并确认零残留后重建，最终公共页面在暂停本轮Worker后捕捉真实QUEUED，再恢复判题。

最终回放target/forgeoj-e2e-20261008-113310-17b0a2af通过：原八判型、权限/学习记录、真实正常与轮询回退页面QUEUED→FINISHED/AC，原公共审计11次提交/10完成/1取消、11个Published Outbox、38实际SQL拒绝、7空队列。公共审计完成后才创建教学fixture，不改变既有数量断言。私有题前置154HTTP、3个实际双PASSED验证作业、正式WA/AC与自测SUCCESS通过。

教学Setup/Revoke/Archive共151HTTP通过；5次真实正式结果WA/AC/AC/AC/AC、2项相同用户/冻结版本真实PRECOMPLETED、1次自测SUCCESS（输出11）均由真实Worker执行。全员与本人成绩一致，正式次数2/1，迟交AC延期后恢复按时，私人预完成/同题自由练习/其他作业/班级/自测代码请求拒绝，分页与单条源码字段边界通过。三份作业9个永久参与关系，学生退出后3项LEFT历史仍保留，归档后当前教学身份只读。

真实内置浏览器依次验证OWNER全员记录及源码、MEMBER拒绝且清空、ASSISTANT同一源码、在显示源码时降级后刷新403并清除成绩和源码、OWNER查看归档与LEFT学生2/2完成和旧正式源码。DOM、5张真实截图及观察记录见 [证据](evidence/m3-teacher-records/README.md)，不是模拟HTML。

只读SQL和日志确认用户/题目/冻结版本/资源绑定、原源码SHA256、每项1个SUCCEEDED attempt、lease清空、Published Outbox以及attempt.finished在delivery.ack_sent之前。Worker对8项新增作用域表读取被真实grants拒绝，API仍没有Docker、Worker DB配置或socket挂载；真实API日志不含认证/源码/私有题解哨兵，7队列ready/unacked为0。原始日志和带配置state只保留忽略的target，不公开。

清理前从实际frontend容器/source只读挂载直接采集68个输入，与最终Linux前端构建及当前源码完全一致；254后端输入及API/Worker JAR均关联。3个已执行回放helper摘要也匹配。Verify-M3TeacherEvidence.mjs输出allPassed:true，API221/Worker133/前端97，失败/错误/跳过0。两个回放栈精确清理，首轮11:33:08 +08、最终11:48:51 +08验证owned容器/卷/网络/镜像、builders、Testcontainers和managed沙箱均0，见cleanup证据。浏览器复用后about:blank+markDeliverable保留；不关闭、更新或重启Codex，不声称已修复客户端崩溃。旧未跟踪M2交接和忽略SMTP文件SHA与开工前一致，未纳入提交。

复现命令（Docker Desktop开启，均是一次性专属fixture，不操作真实数据）：

```powershell
./tools/validation/Verify-FixedLinux.ps1 -Scope Backend
./tools/validation/Verify-FixedLinux.ps1 -Scope Frontend
./tools/validation/Replay-FixedLinux.ps1 -Action Start -BuildDirectory <Backend产物目录>
# 同一RunDirectory依次Matrix/WorkerStop/Permissions/WorkerStart/Library/Learning
# 真实浏览器两入口各WorkerStop后提交捕捉QUEUED，再WorkerStart观察AC并保存browser.json
./tools/validation/Replay-FixedLinux.ps1 -Action Audit -RunDirectory <Run目录>
./tools/validation/Replay-FixedLinux.ps1 -Action PrivateProblems -RunDirectory <Run目录>
./tools/validation/Verify-M3TeacherReplay.ps1 -Action Setup -RunDirectory <Run目录>
# 三账号实际页面观察；源码显示时Revoke，再刷新确认清除；Archive后观察LEFT归档历史
./tools/validation/Verify-M3TeacherReplay.ps1 -Action CaptureRuntime -RunDirectory <Run目录>
./tools/validation/Verify-M3TeacherReplay.ps1 -Action Audit -RunDirectory <Run目录>
# 浏览器about:blank并保留，禁止tab.close()
./tools/validation/Replay-FixedLinux.ps1 -Action Stop -RunDirectory <Run目录>
./tools/validation/Verify-M3TeacherReplay.ps1 -Action CleanupSnapshot -RunDirectory <Run目录>
node tools/validation/Verify-M3TeacherEvidence.mjs target/forgeoj-linux-20261008-110105-55b90a88 target/forgeoj-linux-20261008-112857-b4f26f08 target/forgeoj-e2e-20261008-113310-17b0a2af docs/evidence/m3-teacher-records
```

本机执行附加DockerCommand C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe；Node D:/Node.js/node.exe。两个固定Linux目录分别对应最终Backend和最终Frontend，不将前端初轮或诊断回放冒充最终轮次。

没有新迁移、grants、外部依赖或Worker生产逻辑。页面只读正式Submission代码，不提供私人练习、PRECOMPLETED原代码、自测源码、隐藏测试、参考或测试输出；50人/页、20题/人、50正式尝试/页，不宣称性能压测或已撤回曾显示的数据。不增加教师成绩修改、导出、统计排名或管理员权限；完整M3四总门禁、M4/M5、生产发布及RESUME_READY仍待后续。
