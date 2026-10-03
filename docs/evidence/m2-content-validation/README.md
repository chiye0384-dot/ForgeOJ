# M2 作者双程序验证安全证据

2026-10-04，本单元 VERIFIED，完整内容与 M2 仍 IN_PROGRESS；详细命令、合约、失败诊断和限制见 [验收记录](../../M2-CONTENT-VALIDATION-JOBS-VALIDATION.md)。实施基线59afa5a，固定 Linux283后端/50前端；相同JAR的真实页面、Worker SIGKILL自然恢复、独立审计和精确清理均通过。这里没有生产凭据、原始代码/隐藏测试、邮件令牌、完整运行日志、JAR或数据库转储。安全摘要不替代完整原始报告；本机target保留报告，验收库已销毁。

- `fixed-linux.json`、`input-check.json`、`final-input-check.json`：全量计数/固定产物摘要与输入事实。初次261全匹配；最终260未变，唯一Replay审计工具改动已在实际栈执行，所有后端/前端/合约输入一致。
- `content-browser.json` 与七张实际截图：版本3参考通过/题解WA、版本5双通过、编辑后旧版本失效、版本6中断恢复、版本7归档历史，及正常/兜底AC。没有制造观察到的中间状态；正常实际SUBMITTING→FINISHED/AC，兜底实际QUEUED→FINISHED/AC。
- `content-http.json`：真实页面receipt核对、跨用户/匿名隔离、分页/白名单、幂等、归档和引用删除拒绝；公共题仍2。
- `content-crash.json` 与 `content-crash-replay.ps1`：精确owned Worker真实SIGKILL、有效旧lease自然过期、旧attempt永久关闭/沙箱移除、新attempt双通过，正式Submission前后9。脚本为构建后创建并实际执行的验收外部控制，不是生产测试钩子；复用需新建独立Replay和真实页签任务，旧库已清理。
- `content-database.jsonl`、`content-outbox.jsonl`、`database.jsonl`、`outbox.jsonl`、`queues.json` 与 `audit.json`：3内容job/4已发布内容Outbox、11正式Submission/11正式Outbox、六队列空、关联链与版本/lease事实，不含源码或测试文本。
- `content-grants.json`：15基础+5附加实际数据库权限拒绝，无业务数据修改。
- `library.json`、`learning.json`、`matrix.json`、`permissions.json`、`browser.json`：已有公开题库、私有学习、八verdict、owner/Origin/撤销/取消、正常与兜底AC再次验收。
- `cleanup.json`：先因Docker引擎未运行失败，启动已安装Desktop后实际Server与owned容器/卷/网络/镜像/Testcontainers/沙箱0，精确清理且自建页签关闭，无全局prune。

截图包含可丢弃验收库中的原创公开题面及作者自建测试编辑区域，只证明这次实际交互，不代表已审核正式发布。双PASSED只验证当时冻结程序与测试，不替代完整题目正确性审查、安全发布、性能或整个M2门禁。下一步不可变送审/撤回，随后输出生成预览/自测；真实服务商SMTP仍需配置与授权收件箱。
