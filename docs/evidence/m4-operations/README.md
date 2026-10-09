# M4 step4 operations evidence

2026-10-09，VERIFIED，仅覆盖M4第4步；完整M4 IN_PROGRESS。所有账号、任务、页面和程序来自原创一次性fixture，没有真实数据库、用户凭据或SMTP配置内容。

- `verification.json`：最终门禁、535项测试、122项HTTP、6原任务执行恢复、1同事件投递恢复、接受时间、提交后ACK、14权限拒绝和零残留。
- `source-inputs.json`、`test-suites.json`、`backend.log.gz`、`frontend.log.gz`：最终Linux构建的完整输入摘要、49+23 suites和压缩原始日志；frontend-runtime与served-runtime摘要再关联实际服务副本。
- `operations-http.json`：真实故障耗尽、恢复、幂等/冲突/拒绝及最终正确结果；`operations-browser.json`、真实DOM和`browser-ops-after.png`：独立的实际UI身份、恢复、旧历史和撤权清空证据。
- `operations-frozen-*.jsonl`、task/attempt/receipt/outbox/audit facts：只读SQL导出，冻结内容只保存指纹；唯一凭证保留旧失败/预算，运行没有用SQL合成结果。
- `operations-api.log`、`operations-worker.log`：真实发布和各成功尝试的持久完成→ACK顺序；`operations-queues.tsv`：4活动队列空、7队列无unacked，6旧死信保留。不是“7个空队列”。
- runtime、jar-runtime、delivery-ownership：只读挂载、同JAR和投递恢复未重建API；denials：14拒绝与API边界；cleanup：精确拥有者清理后全部0；protected-files：仅既定两文件摘要。
- 带 first/second/third/public-assignment 或 checkpoint 的记录为历史失败/中途快照。`first-replay-incomplete.json`及其HTTP/cleanup记录证明第一栈不作为最终完整证据；auth-throttle记录真实429及等待，不是关闭限流的成功。
- 两份历史失败txt仅规范展示末尾空白；新增同名txt.gz保存原始字节，`historical-report-provenance.json`记录原始/展示摘要，没有删除或修改测试断言。

当前接受构建 `target/forgeoj-linux-20261009-112325-efec5cc0`，重放 `target/forgeoj-e2e-20261009-140259-41c05bc4`。当前最终verifier摘要在verification中，可离线重核原始报告和仓库输入；不要复用已清理的URL或运行旧session。详见 [验收记录](../../M4-OPERATIONS-VALIDATION.md)。没有Release、性能数字或RESUME_READY结论。
