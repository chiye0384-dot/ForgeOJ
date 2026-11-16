# M2 最终门禁证据

2026-10-05；基线 `184ba300e8886ec63d216ad5aad1189977ea2ee1` 加两项测试及审计工具/文档。完整结论见 [最终审计](../../M2-GATE-AUDIT.md)。

- `verification.json`：本轮 Linux 真实 Surefire XML 汇总（API182/Worker133，36/23 suites，零失败/错误/跳过）、必需门禁用例名和新 JAR 哈希；本轮 300 个已有执行输入、原运行时 165 个生产输入（含文件清单）和原前端 56 个输入逐字节一致。新审计汇总工具在本轮构建冻结后添加，不参与产品运行，没有冒充为已在冻结清单中的输入。
- `cleanup.json`：本轮构建结束后只读查询实际 Docker 29.8.0，managed sandbox、Testcontainers、本次 builder/镜像均为零，无全局 prune、真实业务库修改。
- 既有浏览器、学习隔离、作者送审、输出预览、自测/SMTP 和权限证据以相对路径及 SHA-256 关联，保留各自验收时间。不是本轮新浏览器或邮件记录；私有用户邮箱、密码、令牌和完整原始日志均不提交。

原始新日志/清单/XML/JAR 在 `target/forgeoj-linux-20261005-192744-ecabee05`，不进入 Git。固定 Linux 完整命令和读证据复现工具 `tools/validation/Verify-M2GateEvidence.mjs` 见审计第 3 节。汇总脚本首次核对发现 XML 的 classname/name 匹配、文件列表对象过滤、ANSI 彩色摘要解析问题，均修正并实际重新执行通过；这些是证据解析失败，不是业务测试失败，不改变原 XML 或日志。此前已有 Surefire fork 退出警告仍如实保留。

VERIFIED 表示本阶段规定范围的验证通过；不代表 M4 管理/发布、M5 主机/性能、正式 Release 或 RESUME_READY。
