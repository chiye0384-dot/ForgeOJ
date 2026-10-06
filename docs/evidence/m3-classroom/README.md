# M3 班级成员首单元证据

2026-10-06首单元VERIFIED；完整M3 IN_PROGRESS。范围、原始日志路径、命令及全部失败轮次见 [验收](../../M3-CLASSROOM-VALIDATION.md)。

- `verification.json`：直接解析Surefire XML与构建日志，匹配最终API147/未变Worker110/前端59及实际浏览器59个输入、两个JAR、运行审计和真实daemon清理。API192/Worker133/前端75。不是一次All包装脚本全绿；Worker Maven成功后包装脚本失败的事实单独保留。
- `classroom-http.json`：独立三账号62个真实请求断言，邀请码原文不保存；摘要用于关联本地fixture。正式Submission前后数量相同。
- `classroom-browser.json`、`classroom-browser-dom.txt`、`classroom-browser.jpg`：实际页面操作/最终DOM/原始截图，最终唯一OWNER为账号3、ARCHIVED、版本15，与SQL一致。
- `classroom-database.jsonl`、`classroom-audit.json`：三班级唯一有效OWNER/owner_id一致、转让终态、成员状态和UI/HTTP事实关联；38实际权限拒绝，其中9新增班级拒绝，私有码未进API/Worker日志。
- `audit.json`、`browser.json`和四个判题DOM：八verdict、题库/学习、真实正常/回退AC、11正式/10完成/1取消、11已发布Outbox、7空队列和完整request/task/attempt/ACK链路。正常DOM曾观察RUNNING；最终DOM用于终态。
- `cleanup.json`：实际Docker daemon上两轮owned项目容器/卷/网络/Worker镜像与builder/Testcontainers/managed sandbox全部为0。仅清理本次fixture，原始target保留。

不提交API/Worker原始日志、明文邀请码哨兵、Cookie/Token/SMTP配置或凭证。不会把本轮未重新执行的生产SIGKILL、QQ邮件或完整M3门禁视作当前验收。没有Release或RESUME_READY结论。
