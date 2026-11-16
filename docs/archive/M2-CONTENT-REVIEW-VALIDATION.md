# M2 不可变送审与撤回验收

2026-10-04，实施基线d6393ce，分支feat/m2-accounts，当前VERIFIED；固定Linux、相同构件真实浏览器与独立审计已通过，完整M2保持IN_PROGRESS。[合约](M2-CONTENT-REVIEW-DESIGN.md)。

V12新增一张content_review表与两条复合唯一索引，不改既有V1–V11，冻结内容直接引用既有双PASSED snapshot/test。owner/requestId幂等、quota→draft锁顺序、当前读取pendingReview、唯一待审、reviewVersion0→1撤回和权威引用删除规则已实现。待审禁止编辑/逐条/ZIP/归档/删除/新验证；撤回旧记录不影响新待审。普通用户没有审核/发布接口，Worker没有review表权限。API无Docker、Outbox与正式Submission保持原边界。

Windows局部记录：

- 初始ContentReviewIntegrationTests4项通过，17:06:12+08；真实HTTP/MySQL owner/CAS/幂等/所有待审写拒绝、撤回/旧内容保留、历史/分页/no-store/白名单、唯一pending/复合绑定/列权限。
- ContentReviewIntegrationTests、ContentReviewMigrationIntegrationTests与ContentValidationIntegrationTests共8项通过，17:22:48+08；V11→V12升级逐列保留旧26表（包括旧immutable snapshot、压缩测试与job），新增review为空，重复migrate零执行且validate通过。用于HTTP状态机的PASSED由可丢弃测试数据库控制，不冒充实际Worker执行，浏览器验收必须补实际双PASSED。
- 最终ContentReviewIntegrationTests5项通过，17:25:19+08；增加不同requestId争同草稿只有202/409，以及五轮送审与内容编辑竞争，只能送审成功/编辑409或送审409/编辑成功，不能出现可修改的PENDING。加入afterCommit白名单生命周期日志，不作为持久管理审计。
- 前端npm run verify全部55项/12 suites通过，类型、lint、格式和生产构建通过。新5项覆盖当前双通过门槛、网络不确定及后续GET失败均复用requestId、409不自动覆盖、身份迟到隔离、归档冻结历史、撤回后读取权威草稿状态。最初因新mock缺类型参数在lint失败，补正确类型后全检查通过；不降低规则。

最终全新固定 Linux `target/forgeoj-linux-20261004-173714-5aba44b0` 于09:57:26Z完成 root clean verify：API **169/32 suites**、Worker **120/22 suites**，共 **289**，零失败/错误/跳过。前端 **56/12 suites**，类型、lint、格式、生产构建全过。实际 XML/日志导出见 [固定事实](evidence/m2-content-review/fixed-linux.json)。最终源码清单SHA-256为 `18cea1dedf34f0df72a9498a58ee1ce3171964b8278cd3ee1d4da1c4db6835c0`；全部270执行/测试/合约/验证工具输入匹配，文档在验收后更新。API JAR：`0f4fb439bd17ede296d4ba78bc209b7b014e290d82aa1e9c762070dce2490e39`；Worker JAR：`35455d50c29837592ca6e6ac84d5f1a51acfecedac1e4f5b083510ec1a893bec`。实际 JAR 无 DirectMySQLContainer、FaultWorker 或 testinfra；固定 Linux 的 opt-in 测试连接桥未进入生产。

独立 Replay `target/forgeoj-e2e-20261004-180055-e991fd13` 于18:00:55+08启动，显式核对上述两个 JAR，V1–V12迁移后删除bootstrap，再启动实际API/Worker。真实浏览器在57839/57840使用可丢弃 learner：v3实际参考AC/题解WA→FAILED；修正独立 BufferedReader 程序保存v4→双AC/PASSED→第一次送审PENDING，保存/测试/ZIP/新验证均禁用；读取第1冻结题面后撤回，修改题面保存v5，历史仍为v4原题面；v5重新实际双AC/PASSED→第二次PENDING。此时ReviewLock实际六类写入409、跨Origin撤回403、重放第1撤回200且第2仍PENDING。页面撤回第2并归档v6后，双历史WITHDRAWN和v4原冻结题面仍可读，新执行禁用。见 [浏览器事实](evidence/m2-content-review/review-browser.json)、[实际页面DOM](evidence/m2-content-review/review-history-dom.txt)和[HTTP](evidence/m2-content-review/review-http.json)。两次截图接口及可见浏览器截图均无法捕获画面，明确不提供图片或声称像素布局验收；实际操作/DOM/HTTP/数据库关联均已验证。

本轮3个内容job均自然完成、各1次SUCCEEDED/1次发布Outbox，无正式Submission。Worker生产代码未改；117个运行classes/resources/dependencies除build-info时间戳外逐字节等同上轮真实Worker SIGKILL验收，见 [比较](evidence/m2-content-review/worker-runtime-comparison.json)。本轮没有新SIGKILL，不把字节等同冒充新故障演练。

顺序实际命令：`Replay-FixedLinux.ps1 -Action Library / Matrix / WorkerStop / Permissions / Learning / WorkerStart`，真实作者页面后`ReviewLock / Content / Review`；正常页面较大值提交SUBMITTING→FINISHED/AC；暂停Worker后回退页sum为QUEUED/v0，恢复Worker后FINISHED/AC。最后`Audit / Stop`。10:19:43Z审计成功：11正式提交、10完成、1取消、11正式发布Outbox；3内容job/3内容Outbox；2送审记录准确绑定PASSED owner/draft/version/snapshot，旧题面摘要一致；提交/撤回afterCommit日志各1条关联正确request.completed，Worker提交先于ACK；六队列ready/unacked均0，API无Docker/Worker/migrator配置。实际20基础权限拒绝加10补充拒绝均过，含review全部不可变列、Worker无review读取/修改、API不能改job执行状态。见 [审计](evidence/m2-content-review/audit.json)与 [30项权限事实](evidence/m2-content-review/additional-grant-denials.json)。

10:21:33Z在活跃Docker29.8.0实际核对仅本轮owned容器/卷/网络/Worker镜像、builder/测试镜像、managed沙箱和Testcontainers为0；关闭本人新建tab5/6，未清理无关资源。安全事实不含Cookie/CSRF/JWT/密码、真实用户私有材料或原始日志；页面DOM只包含本人原创可丢弃演示代码和公开样例。用户未跟踪交接文件原字节保留；无PR/main/tag/Release。

页面复查补充：UNDER_REVIEW不改变内容版本，原ContentValidation组件把所有非DRAFT都标成失效；改为只有实际stale、版本不同或ARCHIVED才失效，待审仍保留当前双通过，但新验证按钮禁用。新增直接回归，局部6项通过（17:33:21+08）。最终固定 Linux 前端全量56已通过，不复用早先55作为最终结果。

首次固定Linux `target/forgeoj-linux-20261004-172949-b82a6ed0` 于09:36:15Z失败，未进入测试：Maven Central的`org.testcontainers:testcontainers:2.0.5` JAR传输提前终止，Content-Length预期17,789,146字节、实际7,733,248。临时builder/image已清理，该次不作为验收。因依赖传输失败重新使用全新目录/缓存执行All，不自动重试测试、不使用旧成功结果、镜像/依赖版本/测试断言未放宽；页面修正进入新冻结输入。

下一单元为参考输出生成预览、独立自测，真实SMTP仍需服务商/授权收件箱配置；M4受控审核身份与发布不提前实施。没有新依赖或外部授权内容，没有Release、main合并、tag或PR。
