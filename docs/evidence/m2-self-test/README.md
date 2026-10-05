# 独立自测与 QQ SMTP 脱敏证据

2026-10-05，基线 `2fc7e72`，feat/m2-accounts。本目录只保存平台 ID、状态、摘要、测试计数及原创 disposable 程序的实际 DOM/截图。无授权码、真实邮箱地址、会话/CSRF/JWT、邮件令牌、临时账号密码、原始日志或未审核隐藏程序。

- `verification.json`：实际 XML 套数/计数、JAR SHA、229 后端输入一致性，以及最终300输入中唯一实际执行的审计导出工具修复。
- `self-test-browser.json` / `self-test-http.json`：真实四次点击观察与只读 SQL 的摘要绑定、权限/字段/重放、零正式提交与零AC/无解锁。
- `self-test-crash.json`：真正 Worker SIGKILL、自然租约、旧沙箱清理和两次关闭 attempt。
- `self-test-*.dom.txt` / `self-test-frozen.png`：实际页面，未合成或修改图片。
- `audit.json` / `database.jsonl` / `outbox.jsonl` / `queues.json`：提交后ACK关联审计、11正式/4自测、七空队列。
- `boundaries.json`：实际29权限拒绝、API边界与精确清理；结论来自完整 Audit/Stop 的成功退出。
- `smtp-delivery.json`：用户确认实际收到及点击，数据库 ACTIVE/emailVerified，不披露邮箱身份。

完整命令、失败诊断和范围见 [验收记录](../../M2-SELF-TEST-VALIDATION.md)。完整 M2 暂不提升，等待最终门禁汇总；M4 受控审核发布不提前实现。
