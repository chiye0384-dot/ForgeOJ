# M2 个人学习记录验收

> 2026-10-03，功能分支 `feat/m2-accounts`，基线 `31a40d7`。个人学习记录单元 VERIFIED，完整 M2 保持 IN_PROGRESS。脱敏事实见 [证据目录](evidence/m2-learning/README.md)。

## 范围

V8 追加个人私有题单/条目、官方只读题单/条目和每用户每题 Java21 草稿，以及必要的本人历史/当前版本 AC 查询索引；V1～V7 不改，生产不种题单或 AC。API 的新表授权按用途和列限制，Worker 不增加新表权限。正式提交及 MQ/Worker 契约未变。

个人题单支持创建、改名、删除、添加/移除及完整排列排序；principal 提供 owner，写事务先锁账号/会话、再锁本人题单并检查版本。排序在同一事务先移动到临时位置、再规范为 1..N，唯一约束一直启用。题单统计批量查询，详情标签批量查询；私有读取 no-store、分页最大 50，其他所有者/缺失/非法 UUID 一致空 404。

官方公开 GET 只有元数据和公共题项；本人进度经 `/me/official-problem-lists/{id}` 单独读取。进度 SQL 只计本人当前 judge_version 的 FINISHED/AC，重复 AC 不重复计算；归档与无当前版本保留只含 itemId/position/available 的占位。该公共谓词适用于当前 M0 公共题，未来班级私有题必须升级可见性，不把 ACTIVE 当作未来权限授权。

草稿允许空/不完整程序，UTF-8 最多 65,536 字节，拒绝 NUL、孤立 surrogate 和其他语言；GET 不建行，首次 INSERT 和后续 CAS 冲突均 409，安全整数上限与数据库 CHECK 一致。页面 1 秒防抖、单请求在途，旧保存响应不能替换新输入；冲突暂停自动保存，明确提供载入服务端、保留本页和按最新版本再次 CAS。退出/切题隔离迟到响应。历史仅本人正式提交，固定时间/UUID 倒序、白名单不含源码或隐藏数据；历史版本 AC 保留但不计算当前进度。

## 局部验证

- Windows JDK21 `mvnw.cmd --batch-mode -pl forgeoj-api -Dtest=LearningIntegrationTests,LearningMigrationIntegrationTests,AuthAndProblemIntegrationTests,AccountHttpIntegrationTests test`：10:50:59 完成，29 项零失败/错误/跳过（学习 HTTP 11、迁移 1、原详情 3、账号 14）。真实 MySQL/Tomcat 验证全部题单越权写入口、顺序/CAS/中途失败回滚、当前 AC/版本切换/占位、草稿两类竞争/UTF-8/整数/限额、所有者历史与权限实际拒绝、Origin/CSRF/退出/停用。会话撤销竞争以测试 spy 仅暂停敏感写入口，随后调用真实锁定方法；真实 logout 已提交后该写返回 401 且不存草稿，不加入生产故障钩子。
- V7→V8 对账号、quota、session、refresh/action token、题目/标签/版本/隐藏测试、Submission/Task/attempt/Outbox 共 13 个旧表逐列内容比较；重复 migrate 零变更，validate 通过，五个新表均无业务种子。
- 前端 `npm run verify`：10:50:51 开始，8 文件/35 项、类型/OxcLint/ESLint/Prettier/生产 Vite 均通过。新增草稿 7 项与题单页面 4 项；保留原工作台回归并补齐新增 GET/PUT fixture。

## 失败与修复

1. 未支持方法经默认 error dispatch 变成 401：包限定 advice 对没有选定 controller 的 405 不生效。移除 advice 包限定并直接空体 405/Allow/no-store，不放行 `/error` 或私有/写路由，真实 HTTP 和原认证回归通过。
2. 触发器故障注入需要 SUPER：改用一次性 CHECK 约束，让临时位置移动成功而规范位置写入失败，证明整个排序回滚；不扩大 migrator/API 权限。
3. 迁移测试误写历史 session/token 字段与 attempt 状态：按真实 V6/V3 schema 修正 fixture；不改历史迁移或约束，最终完整逐列比较通过。
4. 原工作台顺序 mock 被新增草稿请求移位：补充草稿 GET/PUT；生产草稿响应另有格式验证，格式错误不覆盖源码。
5. Vue 生产编译发现经格式化后的多语句 inline handler 无法解析：改成脚本中的明确事件函数，加入实际 LearningView 测试并验证生产构建。
6. 最后审计误把列级权限拒绝视为失败：MySQL 表级拒绝是 1142，禁止改 owner_id 的列级拒绝实际是 1143。只修改 Replay 对该固定 SQL 的精确错误码断言，重跑实际 Audit/Stop 通过；不扩大权限，不改变应用、测试或构建输入。

## 目标环境验收

固定 Linux 全量目录 `target/forgeoj-linux-20261003-105405-f52683eb`：API 130 + Worker 111 = 241 项，零失败/错误/跳过；前端 8 文件/35 项及类型、lint、格式与生产构建全通过。后端于 03:05:04Z 完成，前端于 03:05:21Z 开始并通过；Linux/amd64 Maven 3.9.14/JDK 21.0.10 构建，用户代码使用独立固定 Temurin 21.0.12 沙箱。构建副本不包含缓存、凭据或本机私有配置。

构件 SHA-256：API `fce3dd5ce830792e53f954a3351b0a4499a337a08a7d820dcd3ab2c4b4060775`；Worker `fd64831f71c99489eb1a943ca33b27d044e61bb136b9b50bd9b346ac8553be53`。03:05:53.255Z 最终 217 个输入全匹配，清单 SHA-256 `990c3a1e955d61a3f9ff5bb716169c8693a31a791718ec663b40a611ca4465e3`。审计后的核对为 204 个业务/测试/前端/契约输入全部不变，217 个总输入中 216 个不变；唯一 Replay 断言修复的前后哈希见 `post-replay-input-check.json`，不把该修复称作已经进入原构建副本。

同一精确产物的新隔离栈 `forgeoj-e2e-20261003-110553-7ea77fa8` 已执行 Library/Matrix/Permissions/Learning。真实 HTTP 验证题单所有越权入口拒绝、题单/首次草稿/已有草稿竞争均恰好 200/409、匿名官方信息不含个人进度、历史白名单与当前 AC。八类判题、实际通知与断线轮询保留。隔离 fixture 是原创可丢弃数据，不是已审核的正式题目或官方题单。

真实浏览器两页复现草稿 409，验证保留本页暂停、显式按最新版本 CAS 保存以及载入服务端版本；随后实际选题提交 `14898158-30f3-4342-904c-4304d852a3dc` 和兼容 `/` 提交 `ec7b696f-3ebd-4ee8-929b-0d8e40a3942c` 均观察 QUEUED→FINISHED/AC。正常入口实际转发 WebSocket，兼容入口故意阻断 WebSocket 后经 GET 轮询完成。个人/官方题单本人当前版本进度均 2/2；本人历史显示 11 条并能查看实际 AC；页面创建、改名、添加两题、上移排序与移除一题成功。提交后草稿更新到 version 5，数据库事实证明正式提交仍是点击时的 page B 快照，未包含后续草稿代码。

最后独立审计 06:40:26.490Z：11 条正式提交，10 FINISHED + 1 CANCELLED，11 已发布 Outbox，4 个队列为空；任务/attempt/ACK 日志链一致且没有源码、草稿哨兵、凭据或隐藏数据泄漏。实际 API/Worker 数据库权限拒绝与 API 无 Docker/Worker/migrator 配置边界通过。只删除该精确项目的容器、卷、网络和专用 Worker 镜像；06:42:02.667Z 在可读 Docker 29.8.0 上复核均为 0，managed sandbox 也为 0。每个查询检查退出码，不能把 Docker 不可读当成零残留。

复现入口：先执行 `tools/validation/Verify-FixedLinux.ps1` 取得新目录/哈希，再显式传入 `Replay-FixedLinux.ps1 -Action Start` 的 BuildDirectory/ApiSha256/WorkerSha256；按 Library→Matrix→WorkerStop→Permissions→Learning→真实浏览器草稿/正常与兜底提交（按需 WorkerStart/WorkerStop）→Audit→Stop 顺序。`browser.json` 只能由实际 UI 观察建立；不得用协议模拟结果代替浏览器证据。配置参数见脚本及历史 [题库验收](M2-LIBRARY-SMTP-VALIDATION.md)。

真实 SMTP 服务商配置和投递、题目内容/参考程序/审核版本、自测、官方题解仍未完成。该学习记录单元不能替代完整 M2 门禁、生产上线、性能或简历发布准入。
