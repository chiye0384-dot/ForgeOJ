# 作业单元可提交验收证据

2026-10-08；只含本项目原创、可公开、一次性fixture。详细范围、失败轮次、构件和真实命令见 [验收](../../M3-ASSIGNMENTS-VALIDATION.md)，[设计](../../M3-ASSIGNMENTS-DESIGN.md)说明策略与限制。

- `verification.json`：读取实际42个API suites/218项、23个Worker suites/133项、前端17 suites/92项，检查251后端/65前端输入、JAR和两个独立栈清理。最终allPassed=true。
- `assignments-http.json`、`assignments-audit.json`：161HTTP、真实正式5AC/1WA、2SUCCESS自测、2PRECOMPLETED、跨截止AC、参与/归档/政策、日志提交后ACK和17SQL拒绝。
- `assignment-*-facts.jsonl`、`assignment-queues.tsv`、`assignment-denials.json`：只读数据库事实、7空队列和实际grants拒绝。
- `assignment-browser*.json/txt/png`：真实负责人取消/确认/发布、普通成员题解锁定/自测/正式AC/自动成绩、LEFT本人历史及结果。页面题解和代码仅是原创验收fixture，非用户私有题。
- `audit.json`、`matrix.json`、`browser.json`、`public-browser-*.txt/png`、`library.json`、`learning.json`、`permissions.json`、`privilege-denials.json`：原公共判题、学习、正常与回退页面以及38项SQL拒绝回归。
- `backend-source-files.sha256`、`worker-source-files.sha256`、`frontend-source-files.sha256`：各实际构建快照清单；里面其他历史文档哈希不意味着这些文档都属本次业务测试输入。
- `frontend-runtime.sha256`、`runtime-browser-proof.json`、`runtime-browser-ac.txt/png`、`runtime-cleanup.json`：**另一次补充运行**的实际只读挂载摘要、页面AC和清理。主回放漏存清单后不伪造历史采集，最终verification明确区分runtimeProof目录和时间。
- `assignment-cleanup.json`：主栈零残留；补充栈最终也零残留。

不含Cookie、Token、邀请码明文、邮箱、SMTP配置、Codex诊断日志或原始API/Worker日志。主回放原始输出位于`target/forgeoj-e2e-20261008-074908-5182263f`，补充输入核对位于`target/forgeoj-e2e-20261008-085454-c51b6eaf`。不代表生产上线、性能、多节点或完整M3完成。
