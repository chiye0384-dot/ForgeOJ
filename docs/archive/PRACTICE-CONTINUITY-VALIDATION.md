# 做题与复盘闭环验收

2026-10-30，本地范围 **VERIFIED**。用户批准的六步见 [计划](PRACTICE-CONTINUITY-PLAN.md)，机器证据见 [verification.json](evidence/practice-continuity/20261030/verification.json) 和 [页面验证](evidence/practice-continuity/20261030/browser-verification.json)。服务保持运行：`http://127.0.0.1:8088/problems`。

- 左侧第五项为“提交历史”，按当前题分页查看本人的正式提交、当时源码和判题结果。读取不会更改编辑区；载入前提示确认并先保存当前修改，草稿冲突或保存失败时阻止覆盖。学习记录入口仍保留，并可查看历史源码。
- 回执丢失或请求超时后保留当时源码和同一个请求标识，提供“重试原提交／核对提交历史”。后来编辑的代码不会混入原请求；离开页面前提示未确认请求。只有明确放弃追查后才能发起新请求。
- 题库使用独立、禁止共享缓存的个人接口，展示当前版本“未尝试／已尝试／已通过”，在分页前过滤进度。匿名公共接口不增加个人字段；全文搜索及标题降级保留。恢复登录会话后再读个人进度，提供官方入门题单与继续最近草稿入口。
- 判题结果增加中文名称与建议，技术编号折叠，历史时间按本地时区显示。编辑器只在浏览器保存宽度、自动换行和字号三个偏好。
- 明确完整 Main 程序及标准输入输出要求；旧种子题标题改为“整数相加（A + B）”。20道原创入门题增加步骤、示例、正确性说明、易错点、复杂度，并展开原算法代码排版。共21份题解可用。

| 验证 | 本轮结果 |
| --- | --- |
| 固定 Linux API | 58项／4 suites，失败、错误、跳过均0；覆盖学习、题库、创建提交和账号HTTP |
| 完整 Windows 前端 | 159项／30 suites；lint、格式、类型检查及构建通过 |
| 回执不确定回归 | 模拟首个响应丢失、随后编辑、原键原源码重试，禁止普通重复提交；明确400不保留未知状态 |
| 真实请求重试 | 两次相同请求返回同一提交；数据库只有1条Submission、1条task、1条Outbox事件，实际AC |
| 历史代码 | 本人200且no-store；他人404、匿名401；编辑草稿不影响旧源码，归档题仍能读本人的旧记录 |
| 个人题库 | 专用账号实际1已通过、1已尝试、19未尝试；每页1条仍返回完整筛选总数；FULL_TEXT八个候选的进度过滤正确 |
| 题解 | 20份新排版程序逐题取得实际Worker AC，HTTP逐份核对新思路与源码摘要 |
| 页面 | 1440×900双栏、历史详情与载入提示；375×760第五标签换行且页面不横向溢出；偏好刷新保留、官方题单和继续草稿可用 |
| 构件 | 342个API及前端输入摘要记录；API编译输入与固定Linux快照匹配；37个提供资源匹配最终dist，首页按明确的本机演示标记注入转换核对 |

执行入口：`tools/validation/Verify-FixedLinux.ps1 -Scope Api -TestFilter 'LearningIntegrationTests,ProblemLibraryIntegrationTests,SubmissionCreationIntegrationTests,AccountHttpIntegrationTests'`；前端 `npm run verify`；真实流程 `tools/demo/verify-practice-continuity.mjs`。最终日志为 `target/practice-api-first.log`、`target/practice-frontend-session-accepted.log`、`target/practice-continuity-http.log`、`target/practice-evidence-accepted.log`。

当前API JAR：`6f79f700c2d4c17843172c1f4aeb66a25ad7f44a57016ba7b75b706e56b1be52`，来自 `target/forgeoj-linux-20261030-170406-9b32ab4c`。Worker保持原字节 `2be34c53d39560ddd955e761fcad7fb06a6cd19d8ea2720b36b3d8990d7a5761`。更新仅重建原演示API及其邮件桥；原MySQL容器与数据挂载未变，保留原构件和state回退收据。API完整套件、Worker完整套件及本轮Linux前端未重跑，不将历史336／139／140当本轮结果。

内容维护仅针对明确归属的本地ProductDemo：绑定原发布/审核回执、原题解摘要、原判题版本和20条真实AC，再在事务中修改展示内容。审核及其不可变快照、原提交与草稿的事务前后摘要相同；不修改判题版本、测试数据、历史源码或成绩，不伪造PASSED/AC。日常learner没有被用来提交或改草稿。原审核仍对应原版内容，新版教学维护收据另存，不声称重新走过生产审核。

保留首轮测试适配、格式检查和收集器首页原始字节比对失败日志；首页差异源于已存在的本机标记/脚本注入，最终按服务器精确转换后比对，未放宽其他静态资源检查。浏览器绑定失效只重置工具会话；没有关闭、重启Codex或浏览器标签。

边界：未确认请求只在当前页面保留，刷新/离开有提示，跨页面后需查历史；没有跨重启的离线提交队列。题解程序通过不等于人工教学质量评价或学习者掌握。未引入新依赖、外部题目或许可。保护文件只核SHA且未变，未提交推送；完整M5、Release、真实SMTP与公网部署状态不提升，新源码部署前需重打包。
