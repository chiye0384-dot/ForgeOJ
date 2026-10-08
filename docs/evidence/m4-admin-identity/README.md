# M4 第2步：独立管理员身份证据

2026-10-08，身份单元VERIFIED，完整M4仍IN_PROGRESS。机器关联 [verification.json](verification.json) allPassed:true，验收说明见 [M4-ADMIN-IDENTITY-VALIDATION](../../M4-ADMIN-IDENTITY-VALIDATION.md)。这些是当前一次性环境的实际元数据、日志、截图和DOM；不含真实管理员、SMTP配置、密码hash或会话令牌。

- API245/45 suites、Worker133/23 suites全0失败/错误/跳过；前端107/20 suites及所有检查。source-inputs与test-suites记录184 API、110 Worker/shared、72 frontend及5辅助工具的实际输入与检查数。
- HTTP86、三角色直接访问、首改密门禁/重登、SUPER维护表单/账号列表、撤销后数据清空、实际审计筛选/详情有admin-http/admin-browser及四张截图、八份DOM。浏览器没有提交新密码或创建账号凭据；实际HTTP和Vue表单检查分别记录，不能把表单观察写成UI创建提交。
- CLI交互初始化0、重复拒绝1、既有SUPER恢复0、无交互拒绝2，SQL facts和进程ID证明目标状态/版本/旧会话撤销及原API未被重启。仅测试恢复演练，没有真实全体SUPER失联事故。
- 67持久审计、10 SQL拒绝；10正式提交/9完成/1取消、原八verdict/协议及班级62检查、七队列0，实际日志/task/attempt/outbox/ACK元数据对应。未宣称本轮普通浏览器轮询回退或新生产Worker SIGKILL/SMTP验收。
- cleanup全0，临时页面about:blank，内置标签保留。失败日志、Surefire关闭测试fork警告及慢耗时见验收，不以最后通过抹去早期失败。

原完整XML/构建日志/JAR及首次失败记录在验收列出的target目录保留，不提交二进制JAR或测试缓存。当前README仅解释事实，不改变机器审计结论。
