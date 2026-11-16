# 2026-11-07 源码与验收证据同步

用户明确要求把已完成但未推送的改进提交到 GitHub。本轮沿用 `feat/m2-accounts`，仓库为 [chiye0384-dot/ForgeOJ](https://github.com/chiye0384-dot/ForgeOJ/tree/feat/m2-accounts)。同步前远端开发分支与本地 HEAD 都是 `058f36c771f15735a1e987a21c55074b80f511a9`，没有分叉；`main` 仍是早期版本，查看最新功能应使用上述分支链接。

## 同步范围

- 独立部署模板与构建/预检工具、维护窗加密备份和独立恢复、受限镜像配置回滚、性能基线与只读热点诊断工具及已脱敏验收记录。
- 固定本机启动入口、20 道原创入门题与教学题解、批量标签与搜索读取改进、草稿离开保护和编辑器。
- 登录/注册与确认邮箱后自动登录、做题工作台可调分栏与窄屏布局、提交历史和本人历史源码、原请求异常重试、个人练习进度与继续草稿。
- 对应回归测试、维护工具、原创与上游许可记录、当前状态和已知限制。历史失败和当时的验收状态保留，不把旧记录中的“未提交”改写成当时已经推送。

## 本轮核验

| 项目 | 结果 |
|---|---|
| 远端同步前状态 | fetch 后本地/远端开发分支 ahead=0、behind=0 |
| 当前业务输入 | [最后一轮验收快照](evidence/practice-continuity/20261030/source-inputs.json)的 342 项文件 SHA256 全部一致 |
| 前端完整检查 | `npm run verify`：159 tests / 30 suites；类型、oxlint、eslint、Prettier、正式构建全部通过 |
| 工具静态检查 | 20 个 Python、21 个 Node 脚本、13 个 PowerShell 脚本语法检查通过 |
| Git 空白检查 | 自有变更通过；Nginx 上游 copyright 原文的首行空白按原字节保留 |
| 待提交内容 | 逐文件列举并审阅后按白名单暂存；未使用 `git add .` |
| 敏感信息检查 | 真实凭据常见格式、私钥、JWT 模式扫描无命中；较长密码字面量均为测试或明确的本机演示夹具 |
| 保护文件 | 两文件仅用 Get-FileHash 核对原 SHA256，不读取内容、不暂存 |

本轮初次前端运行在 Vitest 导入测试前遇到 Windows 沙箱临时文件重命名 `EPERM`，30 suites 尚未执行测试。保留日志 `target/github-publish-20261107-frontend.log`，随后以允许的本机方式运行相同完整命令，通过日志为 `target/github-publish-20261107-frontend-native.log`。没有为通过检查修改业务代码或放宽测试。另仅移除历史证据打包器 build-evidence.py 的两处行尾空格，AST 完全一致，原脚本另存本地，既有 ZIP 原件不变。

本轮为已验收源码的 Git 同步，不重新运行 API/Worker、真实数据库或判题负载；后端证据仍按原日期和构件解释。最后受影响 API 的固定 Linux 58 项／4 suites 来自 [做题复盘验收](PRACTICE-CONTINUITY-VALIDATION.md)，此前完整 API336／Worker139 不冒充本轮结果。

完整 M5、Release、RESUME_READY 和公网部署状态不提升；现存生产部署 ZIP 仍绑定旧构件，新业务版本正式上线前需要重新打包与验收。本轮不变更本地服务、数据库、GitHub 默认分支或 `main`，不创建 tag/Release。实际提交与远端状态以 Git 历史及 GitHub 分支为准。
