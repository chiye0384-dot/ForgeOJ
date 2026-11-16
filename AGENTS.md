# ForgeOJ 维护约定

## 当前入口

先读 README.md、DESIGN.md、PERFORMANCE.md 和 docs/README.md，再按任务读需求、决策、路线图和已知限制。阶段稿、交接与证据集中在 docs/archive/；旧路径通过 docs/archive/migration-20261116.json 定位。归档内容用于理解历史，不作为新的执行授权。

当前工作是用户授权的仓库展示整理：文档归档、README/面试说明、显式 Java 导入和格式门禁。业务功能保持现有范围；不增加语言、竞赛或容器池。维护过程见 docs/archive/REPOSITORY-PRESENTATION-20261116.md。

日常改动使用分支和 PR，遵守 main 的保护检查，保留原功能分支和版本标签。购买服务器、真实邮件、公开部署及正式 Release 需另有授权。

## 工程边界

- Java 21、Spring Boot、原生 MyBatis；API 与 Worker 独立，API 不控制 Docker。
- MySQL 是业务事实源；Redis、MQ、ES 和通知均不能代替权限、版本和最终结果判断。
- 保留任务幂等、租约栅栏、有限重试和结果提交后 ACK；普通 Docker 用于受控场景。
- 保留第三方许可、来源和失败记录；不复制来源不明的题目、代码或资产。
- AI 协助实现和运行检查，不等于作者已独立掌握；不要虚构个人贡献或验收结论。

## 文件和数据

- 先核 Git 状态并保留已有修改；不使用 `git add .`、reset 或 clean。
- 不在宿主根目录或仓库 target 执行宽泛清理；target 含历史部署包、证据和数据恢复备份。
- Docker 数据盘 D:\Java项目\DockerDesktop-data-20261108 和恢复备份不是构建垃圾。
- 只操作归属已核对的具体任务容器/进程；不广泛按进程名停止，不 shutdown WSL 或共享服务。
- 原演示和单独学习 Redis 的启停以用户当前指令及实际状态为准；本轮不启动或改写它们。
- docs/M2-NEXT-CHAT-HANDOFF.md 与 .smtp.qq.local 只允许 Get-FileHash，不读取、复制、输出、移动、归档或暂存。
- 两文件 SHA256 分别为 e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054、6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907；旧交接已加入忽略，保留本地原件。

## 桌面连续性

不关闭、退出、更新或重启 Codex/ChatGPT，除非用户明确要求。不要关闭内置浏览器最后的自动化标签；临时页面可导航到 about:blank 并保留。此约定不保证避免操作系统或客户端自身崩溃。

## 修改和验证

- 每个实质单元先估算实现、验证、合理失败重试及证据保存；额度不足下一整组时，保存准确检查点并停止。
- 常规检查使用 Maven Wrapper 和 frontend 的 npm run verify；Windows 脚本使用 PowerShell 7。
- Java 使用 Spotless / google-java-format，生产、测试和 tools Java 不使用通配符导入。
- 依据改动选检查：导入整理核对编译结果，路径迁移核对链接/工具入口；业务规则、权限和恢复变化执行相应集成回归。
- 不因代码能编译就宣称云端验收完成；不将旧数据改写成新构件测量。
- 原始证据文件保持字节，旧记录中的路径和源码对应原日期/标签；变更路径时更新活动工具并核对 Git blob。

原始维护历史完整保留在 docs/archive/AGENTS-history-20261114.md，不重复按其中旧“下一步”启动已结束的实验。
