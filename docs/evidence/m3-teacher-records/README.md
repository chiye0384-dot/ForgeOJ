# M3 教学作业记录脱敏验收证据

2026-10-08，本单元VERIFIED，完整M3仍IN_PROGRESS。实现基线82afd00；最终后端target/forgeoj-linux-20261008-110105-55b90a88，最终前端target/forgeoj-linux-20261008-112857-b4f26f08，最终回放target/forgeoj-e2e-20261008-113310-17b0a2af。详情及复现顺序见 [验收](../../M3-TEACHER-RECORDS-VALIDATION.md)。

- verification.json：最终机器关联allPassed，API221/Worker133、前端97、254后端/68前端输入、JAR摘要、真实HTTP/浏览器/清理。backend-source-files.sha256与frontend-source-files.sha256来自对应固定Linux构建；frontend-runtime.sha256在清理前从实际容器只读/source直接采集，teacher-runtime.json保留时间和挂载证据。teacher-tool-inputs.sha256是Setup执行前的3个实际回放脚本摘要。
- teacher-http.json：Setup/Revoke/Archive共151个真实HTTP状态检查与原正式源码SHA，仅ID/路径/结果，不保存请求源码、cookies或token。private-problems-http.json是本轮前置154项私有题检查。teacher-attempt-facts.jsonl记录5正式尝试WA/AC/AC/AC/AC及owner/version/resources/lease/Outbox；participant/pre/self三个facts文件关联9永久参与者（3LEFT）/2真实预完成/1自测。
- teacher-worker-events.jsonl：从实际Worker JSON日志限定抽取5项正式attempt.finished与delivery.ack_sent事件字段，保留原时间和IDs；teacher-audit.json确认真实提交后ACK、源摘要和绑定。teacher-denials.json为8项实际Worker SQL拒绝和API边界；teacher-queues.tsv为7队列ready/unacked=0。原始API/Worker日志不公开。
- teacher-browser.json及owner/member/assistant/revoked/archived的txt和png：同一内置浏览器真实三账号依次操作。OWNER/ASSISTANT读取同一正式源码，MEMBER拒绝，显示源码时降级后刷新清空，归档后LEFT学生历史2/2及源码可只读。页面仅展示原创一次性fixture；不包含真实用户数据。
- audit.json、matrix.json、permissions.json、learning.json、library.json、privilege-denials.json：原公共回归11次提交/10完成/1取消、八判型、38权限拒绝和空队列。browser.json与public-*-queued/final.txt是实际正常/回退页面QUEUED→AC观察；公共Audit先于教学fixture，原数量断言未变。
- teacher-cleanup.json：最终11:48:51 +08现场Docker资源零残留；first-replay-cleanup.json：首轮11:33:08 +08零残留。首轮真实页面先观察到RUNNING而非初始QUEUED，未作验收证据；保留失败历史并重建，原断言未削弱。最终Worker暂停只为捕捉真实QUEUED，恢复后正常执行。

所有代码、题面与账号都是专属一次性原创开发fixture。凭据、SMTP配置、数据库/Compose state、原始日志与本机Codex诊断不进入公开证据。截图不做伪造或覆盖状态修改。浏览器最后导航about:blank并markDeliverable保留，Codex未关闭/更新/重启；这不表示客户端缺陷已修复。没有新增Worker SIGKILL、外部QQ邮件、性能压测、Release或RESUME_READY证据。
