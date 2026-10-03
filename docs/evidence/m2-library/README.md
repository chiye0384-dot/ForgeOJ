# M2 题库与 SMTP 适配证据

> 2026-10-03。题库与 SMTP 适配单元 VERIFIED，完整 M2 保持 IN_PROGRESS。只保留原创隔离 fixture 的脱敏结果、构件元数据和截图，没有真实凭据或真实用户数据。

SMTP 固定依赖元数据见 `smtp-dependencies.md`；其中本地 SMTP/TLS 测试不等于真实服务商投递。

- [验收说明](../../M2-LIBRARY-SMTP-VALIDATION.md)：229 后端/24 前端、构件哈希、失败记录与范围。
- `library.json`：匿名真实 HTTP；`matrix.json`、`permissions.json`、`audit.json`：判题/权限/日志和队列审计。
- `browser.json`、`browser-topic-check.json`：两个浏览器提交及实际 DB 题目版本核对。
- `cleanup.json`：daemon 确认可用后逐命令退出码核查，精确资源零残留。
- `input-check.json`、`post-replay-input-check.json`：构建输入及唯一 Replay 工具修正的明确记录。
- `library-filtered.jpg`、`library-selected-ac.jpg`、`library-legacy-fallback-ac.jpg`：真实浏览器观察；代码是本项目原创一次性验收程序。
