# ForgeOJ 本地改进验收

实施：2026-10-29；收尾：2026-10-30。**本地改进范围 VERIFIED**；完整 M5 仍 IN_PROGRESS，没有正式 Release 或 RESUME_READY。

## 已交付行为

- `打开ForgeOJ.cmd` 启动既有明确归属的演示并在默认浏览器打开固定本机入口；`ForgeOJ.ps1` 提供 Start、Status、Stop。停止不删除容器或卷，不初始化空库覆盖历史。
- 首页进入题库。所有题使用中性 Main 骨架。编辑区有行号、基础 Java 文本高亮、Tab 缩进、换行缩进及窄屏导航；后台与教学页按路由加载，没有新增前端依赖。
- 草稿在离开页面前等待在途保存并保存最后编辑；失败/冲突或超时需要明确确认放弃，刷新/关闭有未保存提示。没有向共享浏览器持久存放源码。真实浏览器检查为快速离开/返回和提交 AC，未关闭标签页或复现客户端关闭缺陷。
- 新增20道原创入门题，总题库21题，具备题面、约束、样例、128组测试、两份实现和题解；使用真实作者草稿→Worker双程序验证→送审→批准发布。难度/标签及官方题单通过明确归属的本地元数据工具添加，当前没有对应的管理API。
- 全文搜索的候选标签由逐题查询改为每批读取，保留 MySQL 版本/公开状态复核、事务快照与降级；未宣称提速比例。
- README 已整理，原内容保存在 docs/history；当前入口集中到 CURRENT-STATUS.md。

## 验证

| 范围 | 结果 |
|---|---|
| 固定 Linux API | 336项 / 59 suites，失败/错误/跳过均0 |
| 固定 Linux Worker | 139项 / 26 suites，失败/错误/跳过均0 |
| Windows 与固定 Linux 前端 | 各140项 / 27 suites；类型、lint、格式、构建通过 |
| 新题本机程序校验 | 20题、两份实现、256次执行全部匹配 |
| 平台出题与审核 | 20个真实双通过验证任务、20次批准发布 |
| 正式判题 | 20题逐题AC，20次同键幂等、20次题解解锁；另1WA、自测输出、跨账号404通过 |
| 浏览器 | 21题、选题、中性模板、编辑/快速离开/返回恢复、真实AC、窄屏导航均观察通过 |
| 运行来源 | 423个模块/前端所选输入与新验证快照一致；实际API/Worker JAR摘要与验证产物一致 |
| 停启 | 84条题目/提交/草稿/审核/题单事实一致，MySQL数据挂载一致，重启后再次真实AC |

自动提交均属于 `verification-learner`，普通演示 `learner` 的正式提交历史未被自动验收填满。程序测试与AI辅助内容不代表学习者已掌握或人工教学质量评估。

脱敏机器证据：[verification.json](evidence/product-improvements/20261029/verification.json)、[所选输入摘要](evidence/product-improvements/20261029/source-inputs.json)。页面：[最终题库](evidence/product-improvements/20261029/library-final-20261030.jpg)、[编辑区](evidence/product-improvements/20261029/editor.jpg)。

## 构件与复核入口

- 后端：`target/forgeoj-linux-20261029-193838-e6b7415e`，日志 `target/product-backend-20261029-193838.log`。
- 最终前端Linux：`target/forgeoj-linux-20261030-091357-1dfa127f`；Windows：`target/product-frontend-20261030-verification.log`。
- 运行项目：`forgeoj-e2e-20261029-201735-604f52a7`，回执在同名 target 目录。
- API SHA256：`c2b2e407df458865e5f95ff4cba40020dd8ee5807242c2999a64da614ba2c664`。
- Worker SHA256：`2be34c53d39560ddd955e761fcad7fb06a6cd19d8ea2720b36b3d8990d7a5761`。
- 日常使用：`.\ForgeOJ.ps1 -Action Start|Status|Stop`（实际执行时选择其中一个动作）。复核证据可用 tools/demo/Collect-ProductEvidence.ps1，需明确传入本次运行目录、前端构件、原生日志与Docker路径。

## 失败与中断保留

首次前端沙箱临时文件权限拒绝、路由测试上下文/类型/lint修正、未完成注释测试数据误含结束符等早期失败不当作通过；此前各轮日志和target目录保留。元数据工具首次 `id` 歧义导致事务失败，先核对用户仍1/题单0/新题未分类且20发布保留，保存 `starter-metadata.failed-ambiguous-id.sql` 后修正；接受收据为 `starter-metadata.json`。HTTP工具的两次路径错误在正式提交前停止，索引重建事实保留，最终复用已有索引并取得完整接受结果，没有重复判题来掩盖原失败。

2026-10-29额度耗尽使停启验证的自动审批未能完成，该动作当时未执行，不是安全性拒绝。2026-10-30用户继续后额度已恢复，完成上述停启和重启后判题验证。终端颜色编码一度使汇总正则未匹配，通过规范化日志读取修正，没有重跑测试或弱化验收条件。

## 当前边界

当前提供固定本机网址，不是公网服务。真实服务器/HTTPS/SMTP、每日异地备份、跨业务版本回退、长稳与受控真实用户试用仍未验收。新业务源码已经改变，历史独立生产部署包继续保留为旧构件；上线新版本前需重新生成并核验部署包。没有购买、公网发布、真实邮件、邀请、PR/main/tag/Release；变更保留于工作区，未混入原有未提交内容提交或推送。旧LAN容器继续退出保留，无关Redis未操作。
