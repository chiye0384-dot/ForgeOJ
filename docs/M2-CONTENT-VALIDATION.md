# M2 作者内容草稿阶段验证

2026-10-03，基线 `3d0c17c`，功能分支 `feat/m2-accounts`。作者内容草稿阶段 VERIFIED；完整内容单元和完整 M2 均未完成。设计见 [内容设计](M2-CONTENT-DESIGN.md)，脱敏事实见 [证据目录](evidence/m2-content-drafts/README.md)。学习报告中的 241/35 和两个 AC 属于先前独立源码/构件，本阶段使用以下新产物和独立重放。

V9 只追加两张私有作者表，V1–V8 不改，无生产内容种子，Worker 不新增权限。作者草稿与公共 problem 分离，全部 DTO/SQL 绑定 principal owner；参考程序和题解代码分列保存。CRUD/CAS、草稿归档、测试逐条与 ZIP 全量替换已实现，所有写事务先复核当前账号/会话再锁本人草稿。测试 gzip 压缩、原始字节数和 SHA-256 可核对。ZIP 文件名从不用于解压路径，不执行脚本，实际字节/CRC/UTF-8/文件数/总量均有界；作者 JSON 在 Jackson 物化前限 32 MiB，ZIP 限 8 MiB，未知长度也通过计数流限制。已声明过大的请求返回空 413/no-store；未知长度超限解析失败返回空 400。私有内容完整性或数据库故障空 503。

`/authoring` 已提供列表分页、创建、题面/样例/来源/资源限制、独立参考程序/题解编辑、逐条测试、ZIP 替换、版本冲突和草稿归档。测试保存/ZIP 成功只刷新 server version 和测试，不覆盖尚未保存的内容编辑；代码保存复制点击快照；迟到读取在卸载后不再读取测试或写界面。页面尚无正式验证、送审、发布或官方题解阅读操作。

## 局部结果

- 15:01:56 Windows `mvnw.cmd --batch-mode -pl forgeoj-api "-Dtest=ContentIntegrationTests,TestDatasetArchiveTests,ContentMigrationIntegrationTests,LearningIntegrationTests,LearningMigrationIntegrationTests,AuthAndProblemIntegrationTests" test`：32 项，零失败/错误/跳过。包括新内容 HTTP 7、迁移 1、ZIP 9，原学习 HTTP 11/迁移 1、原公开详情 3。
- 新请求限制加入后 15:11:35 重跑 `"-Dtest=ContentIntegrationTests,TestDatasetArchiveTests,ContentRequestSizeFilterTests,ContentMigrationIntegrationTests"`：20 项全部通过。真实 MySQL/Tomcat 验证本人代码和测试、越权入口、200/409 竞争、不混合数据、失败插入回滚已删除的旧测试与版本、归档只读、退出前 Cookie/JWT 真正撤销、实际角色读取/改 owner 拒绝。V8→V9 保留十八个旧表逐列事实，重复 migrate 零变更，validate 成功，两个新表为空。
- 15:08:01 前端 `npm run verify`：9 文件/39 项、类型/OxcLint/ESLint/Prettier/生产 Vite 全通过；新作者页 4 项覆盖快照、保留编辑、冲突及卸载。

失败修复：退出后的 fixture 最初被清空 Cookie，使匿名请求先收到 CSRF 403；改为保存退出前 Cookie 再发送，验证未过期 JWT 已撤销而返回 401，未修改生产认证。页面代码审查发现直接 structuredClone Vue Proxy 不可行，改为 JSON 文本快照，并用实际页面保存测试覆盖；测试数据更新最初会覆盖未保存内容，改为保留编辑器并使用新 server version。新增 mock 按仓库 lint 规则补齐类型参数。

## 目标环境

固定 Linux 全量目录 `target/forgeoj-linux-20261003-151211-8b51dfa6`：API 150（26 suites）+ Worker 111（21 suites）= 261 项，零失败/错误/跳过；前端 9 文件/39 项及全部类型/lint/格式/生产构建通过。后端 07:24:37Z 完成，前端测试 07:25:02Z 开始并通过。构建环境与既有固定 Linux 脚本保持一致，无新增依赖；少量测试 router fixture 未注册新导航 `/authoring` 的警告不影响生产路由，生产构建含实际作者路由。

API SHA-256 `5ebc731446e0864160f7c1a9fd938f675ef395d4f2fd421e9dfba22916de61db`；Worker `ed1231c816723045f3c9825ec98b2f56493af2d4fecbdf77bc259ab0cf717481`。重放后 11:28:43.829Z 全部 231 个业务/测试/前端/契约/工具输入仍匹配，清单 SHA-256 `4cfa669f81318390ac2eab8682c569ef9e5ccb499c0cb41d8f8fd0ab4a55e80f`。全量构建和重放期间只修改文档，未改变执行输入。

精确产物的新独立栈 `forgeoj-e2e-20261003-152705-1be5a9c7`：真实浏览器创建原创草稿，保存两份独立代码与题面/样例；真实文件选择器上传原创 ZIP，编号排序形成两组测试、version 2→3，未保存的参考程序编辑保留。含 `../001.in` 的非法 ZIP 返回 400，原两组测试/version 3 不变；另一页旧 version 2 保存显示冲突并保留本页题面，显式载入服务端后恢复编辑。逐条加入第三组、保存到 version 4、归档到 version 5，再次读取保留三组测试和两份代码，编辑全部禁用。截图与观察事实见证据目录。

数据库事实确认该作者草稿 ID `be0008b6-5304-41f8-a634-a65d17f927c4` 未进入公共 problem，两份代码分开，未提交的本页代码不在数据库；作者流程结束时正式提交仍只有原矩阵/取消的 9 条，没有伪造验证或 AC。实际 Worker 读作者草稿/测试均拒绝 1142，API 改 owner 列拒绝 1143。

相同产物的 Library/Matrix/Permissions/Learning 重放全部通过；原正常选题提交 `53b7d81e-970b-485a-aa62-ca192fc3581b`、兼容 `/` 轮询提交 `a1c4e80f-07db-4f5c-87a3-4fd0f449c637` 均实际观察 QUEUED→FINISHED/AC。独立审计确认 11 正式提交（10 完成/1 取消）、11 已发布 Outbox、4 个空队列、任务/attempt/ACK 日志链一致、源码与凭据哨兵不泄漏，API 无 Docker/Worker/migrator 配置或 socket。仅删除该精确项目的容器/卷/网络/专用镜像；11:28:36.483Z 在可读 Docker 29.8.0 上全部复核为 0，managed sandbox 也为 0。每个查询检查退出码。

复现：`Verify-FixedLinux.ps1` 取得新产物/哈希，再显式 `Replay-FixedLinux.ps1 -Action Start`，按 Library/Matrix/WorkerStop/Permissions/Learning、真实作者页面与两条正式提交（WorkerStart）、Audit、Stop 完成；ZIP 只用原创可丢弃数据。实际新表拒绝与数据库脱敏事实另经固定 SQL 查询核对，见 JSON。不得把该阶段称作已完成正式沙箱参考/题解验证、送审快照/撤回、生成输出预览、官方解锁、真实 SMTP、完整 M2 或生产发布。
