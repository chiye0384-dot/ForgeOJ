# 班级私有题可提交验收证据

2026-10-06；仅原创、可公开的一次性 fixture。完整结果见 [验收记录](../../M3-PRIVATE-PROBLEMS-VALIDATION.md)，范围见 [设计](../../M3-PRIVATE-PROBLEMS-DESIGN.md)。

- `verification.json`：读取实际测试报告、完整源码/测试清单、前端实际运行摘要、运行 JAR 和清理结果生成。
- `private-problems-http.json`、`private-problems-audit.json`：三账号155项、真实Worker双通过、正式WA/AC/AC、自测、快照摘要/资源/测试复制和日志链路。
- `private-*-facts.jsonl`、`private-early-views.jsonl`、`private-queues.tsv`、`private-privilege-denials.json`：实际只读SQL事实、七空队列和13项拒绝。
- `private-browser-*.txt/png`、`private-problems-browser.json`：真实负责人发布、普通成员无维护、未AC取消/确认、冻结自测11、正式AC、移除清空；取消SQL和负责人HTTP撤销凭证另存。
- `audit.json`、`browser*.txt/json`、`matrix.json`、`library.json`、`learning.json`、`permissions.json`、`privilege-denials.json`：公共回归、正常及回退页面AC与38项拒绝。
- `frontend-runtime.sha256` 与 `private-cleanup.json`：浏览器实际输入及live daemon精确清理事实。

不包含账号Cookie、Token、邀请码明文、邮箱、SMTP配置或原始API/Worker日志。原始报告留在 `target/forgeoj-e2e-20261006-190704-e70d4e52`，可提交证据不能冒充生产部署或性能验收。
