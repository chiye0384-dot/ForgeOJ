# M2 官方题解访问验证

2026-10-03，基线 c6cf100，功能分支 feat/m2-accounts，题解访问单元 VERIFIED。完整 M2 仍 IN_PROGRESS，正式内容沙箱验证/送审/发布尚待实施。设计见 [访问设计](M2-SOLUTION-ACCESS-DESIGN.md)，原始源码与脱敏运行事实见 [证据目录](evidence/m2-solution-access/README.md)。

V10 追加独立官方题解快照和个人首次提前查看记录，V1–V9 不变，无 seed/普通用户发布路由。GET 正文受本人当前版本真实 FINISHED/AC 或明确确认记录控制；无快照/未解锁 solution=null。严格版本确认、事务会话复核、重复/并发幂等与首次时间、旧版本和跨用户隔离、no-store、正文文本渲染及卸载/换身份迟到响应隔离已实现。API 只有题解 SELECT 与记录 SELECT/INSERT，Worker 无两表权限。普通作者的参考程序和题解保存不会自动生成官方快照。

## 最终检查与精确产物

- Windows：`./mvnw.cmd --batch-mode -pl forgeoj-api -am '-Dtest=SolutionIntegrationTests,SolutionMigrationIntegrationTests' '-Dsurefire.failIfNoSpecifiedTests=false' test`，21:05:54+08 完成，8 项，零失败/错误/跳过。无 direct-DB 环境开关的本机检查保留原映射端口路线。
- 最终固定 Linux `target/forgeoj-linux-20261003-210444-d5adac10`，root clean verify：API 158（28 suites）+ Worker 111（21 suites）=269，零失败/错误/跳过，13:18:57Z 完成。frontend 45/10 文件，类型/lint/格式/生产构建全通过。六项新前端测试覆盖确认/取消、服务端重读、409、身份/卸载隔离、通知与读取竞争、非法 DTO/HTML 文本。
- 真实 MySQL/Tomcat 新 HTTP 7 项：权限/响应白名单、本人当前版本真实 AC、幂等/并发/首次时间/用户版本隔离、旧版本确认、CSRF/Origin、撤销/停用、实际授权拒绝及撤销 INSERT 后 503/回滚、旧 slug 兼容。V9→V10 迁移 1 项逐列保留原 20 表（含个人与私有作者事实），新 2 表为空、重复 migrate=0、validate 成功。
- API SHA-256 `6d06c4dd2f4203ded3402ef155fc084c3bf7ba82d531daa291581a30dd905464`；Worker `3383676e8c56236871b47a1c7ea23d693afa9e37ed65ec93a722ad22ad647cda`。13:41:19.533Z 重放清理后 243 个输入仍全部匹配，manifest SHA-256 `5a0ef9046e8d11846350cbb25ef9d1458dd510f72336cda26c0ebdd0b45db92b`。后续仅改文档/证据。

## 实际重放

精确构件独立栈 `forgeoj-e2e-20261003-212227-d3049408` 通过 Library/八 verdict/Permissions/Learning。仅该可丢弃库插入一份原创较大值题解快照，不是审核发布。真实浏览器 other-learner 首读 LOCKED；提示后取消，实际记录数 0；明确确认后 EARLY_VIEW，只有本人一条记录。退出换 learner 首读仍 LOCKED；原创源码正式提交 `fd0c420c-06fa-41af-a842-f6a8901db826` 实际 QUEUED→FINISHED/AC，已打开的题解自动由服务端重读解锁。数据库确认源码字节与独立快照一致、learner 提前记录=0。降级入口 A+B 提交 `68ae7dfc-5bbb-461b-987a-97798bd114a1` 实际 QUEUED→FINISHED/AC，但无快照仍 UNAVAILABLE，AC 源码没有自动成为官方题解。

审计通过：11 正式提交（10 完成/1 取消）、11 published Outbox、四空队列、任务/attempt/ACK 链一致、源码/草稿/凭据哨兵未进入日志。正常入口一次 WS 转发且有结果 GET，降级无 WS 转发且实际阻断与多次 GET；browser.json 的 port 是容器代理 5173/5174，hostPort 为本轮 52963/52962。API 无 Docker/Worker/migrator 配置或 socket。额外七个真实授权查询证明 Worker 无题解/提前记录 SELECT，API 无官方快照 INSERT/UPDATE/DELETE 或提前记录 UPDATE/DELETE（1142）。13:41:19.329Z Docker Server 29.8.0 实际可读，精确拥有的容器/卷/网络/镜像和 managed sandbox 均为 0。每个 Docker/SQL 查询检查退出码，未 prune。

复现按 Verify-FixedLinux 全量→显式新构件/哈希 Replay Start→Library/Matrix→WorkerStop/Permissions/Learning→原创单版本题解 fixture→实际上述两账号/两入口操作→Audit/额外权限→Stop/input check。测试 fixture 与普通作者代码不是正式受控发布；无真实 SMTP 或云部署证据。

## 失败、诊断与修正

首次 Windows Maven 点号参数被 PowerShell 拆分，引用完整参数后执行；前端 mock 类型与响应式父组件 fixture 修正后通过，没有弱化权限/断言。最初 slug 新正则会拒绝旧公开接口支持的合法值，移除格式限制并新增 legacy--fixture- 实际回归。

首次 Linux 195400-20c0f627 为旧 slug 版本 268/45，不能代替最终源码。201218-fc4258b0、202633-1de4e0bb、204154-2fcc6e2b 三次全量 API 158 中各一个 ERROR，分别在新题解 seed、既有内容 prepare、既有学习 seed 建立 JDBC 连接时 SocketTimeout，Worker 被 Reactor 跳过；均不接受。这些失败发生在业务断言前。Spring Test 7.0.9 字节码确认默认缓存 32/系统属性后，API 测试缓存限 1 及时关闭旧 Hikari，但仍有超时，不能断言它是唯一根因。100 次 host.docker.internal HTTP 连接通过不等于 JDBC 稳定；默认网桥网关 172.17.0.1 实际 3 次失败即停止，未采用。确认所有容器为空后正常 docker desktop restart，也未消除 JDBC 映射路线问题。

十次真正兄弟容器 IP 的数据库路线探针成功。新增仅测试源码的 DirectMySQLContainer，通过 inspect 本人的 Testcontainers MySQL 取得 bridge IPv4，仅 `FORGEOJ_TEST_DIRECT_DB=1` 改 JDBC 内部路线；不开启时继承原连接 URL。固定 Linux driver 显式开启，不猜网关、不自动重试测试、不改生产连接/断言/权限。17 既有 API 和 5 Worker 测试只替换构造器；两模块各自 testinfra 无生产依赖，检查两个实际 JAR 均不含它。原测试完整跑完并通过 269/45。

初始下载还遇到临时仓库 TLS/HTTP 等待；独立 settings 将构建仓库统一到官方 Central，无新增依赖、版本变更、host .m2 导入或 TLS 关闭；用法依据 [Maven 官方 mirror 文档](https://maven.apache.org/guides/mini/guide-mirror-settings.html)。

本轮 Learning 首次误在 Permissions 前运行，history 只有 8 而断言 9；未降低断言。停止 Worker、完成真实取消，确认仅探针留下的 owner=1/list id=8e6cf792-276d-4d3f-a8d3-b737a57f7785/title=隔离学习验收/version=6 与 user=1/problem=100/JAVA_21/draft version=2 后，仅清理这组独立库测试记录，按顺序重跑通过。初次浏览器事实误把宿主端口写作代理端口，审计失败；核对实际日志后分别标注并重新通过，无源码/断言变化。浏览器 waitFor 一次返回超时，但诊断及随后的真实 AX 已显示终态/解锁，保留截图和 DB 事实，不以工具报错视为业务成功。

完整 M2、正式参考/独立题解沙箱验证、不可变送审/撤回、输出生成预览、自测短期任务及真实 SMTP 仍待完成。M3 私有题可见性必须另加权威权限，不能直接沿用当前公共 ACTIVE 模型。
