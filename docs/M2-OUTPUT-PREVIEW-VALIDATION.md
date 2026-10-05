# M2 参考输出预览验收

2026-10-04，实施基线5105cf6、feat/m2-accounts，单元VERIFIED（2026-10-04实际验收，2026-10-05整理）。完整M2保持IN_PROGRESS。[设计](M2-OUTPUT-PREVIEW-DESIGN.md)。

V13保留V1–V12，不重写历史结果。snapshot/job不可变用途VALIDATE或OUTPUT_PREVIEW由复合外键约束；只有VALIDATE可产生双结果/PASSED。新输出表仅Worker SELECT/INSERT，API仅SELECT；确认receipt仅API SELECT/INSERT，Worker无权限。预览使用原内容队列、额度、attempt/lease/重试和ACK，仅运行冻结参考，不执行题解，不创建Submission。整组成功输出同终态事务保存；任何用户失败无部分输出。最多100组、每输出1MiB、输入加生成输出16MiB，严格UTF-8且禁止NUL，原资源上限仍生效。

局部验证：

- 19:08:33+08 API预览4项、V12→V13迁移1项、送审5项、双验证3项，共13项零失败/错误/跳过。真实HTTP/MySQL owner/no-store/CAS/用途请求隔离、额度、失败/过期/归档/待审不可确认、校验失败无修改、确认并发单receipt/单版本、重放不覆盖后续修改、数据库用途CHECK/复合绑定与权限通过。该HTTP lifecycle的结果用可丢弃DB控制，不冒充真实Worker生成。迁移逐列保留旧27表，包括旧双PASSED和待审记录；新增kind默认VALIDATE，新增两表空，重复migrate0且validate成功。
- 初次API13项失败：不可变执行表SELECT FOR UPDATE要求API UPDATE权限，产生503。未扩权；改为quota→draft写锁和READ_COMMITTED预览创建/确认，执行/确认记录只读，重跑13通过。原验证CRUD保持其权限边界。
- 19:10:23+08 Worker实际执行/状态机13项加原沙箱13项，共26项零失败/错误/跳过。实际两组参考输出忽略错误旧答案且只运行参考；broker终态后ACK/重投无重复输出，运行中第二例失败、非法UTF8、NUL、超限和编译失败均无输出；过期lease不能写输出，新attempt真正生成后成功；模拟异常第二文件使第一INSERT/终态全回滚。测试broker销毁后的关闭警告不充当业务失败或零ERROR日志声明。
- 前端全部61项/13 suites，类型/lint/格式/构建通过。新5项覆盖显式确认、失败/过期/归档禁用、不确定POST和后续GET都复用requestId、身份变化丢弃迟到私有输出、确认不确定可重放且409不自动覆盖。初次分页多语句在格式化后模板解析失败，改为命名分页函数；测试最初误用了未安装库，改为仓库原生Vue/DOM方式，没有添加依赖；补齐强类型mock与固定数组fixture后全部检查通过。
- 最初编译使用系统Maven默认mirror/新缓存导致重下载，主动停止后核实ForgeOJ原缓存C:/Users/Lenovo/.m2/repository；随后编译发现TestPair需sequence字段，修复后上述API/Worker实际测试编译通过。未修改全局Maven配置或清空缓存。

全新固定 Linux All `target/forgeoj-linux-20261004-191729-3f25e7ce` 后端于2026-10-04T11:33:49Z完成 root clean verify：API **174/34 suites**、Worker **124/22 suites**，共 **298**，零失败/错误/跳过；前端 **61/13 suites** 和类型/lint/格式/生产构建全部通过。JAR 检查无 DirectMySQLContainer、FaultWorker 或 testinfra。最终清单由 All 的前端复制阶段重新写入，SHA-256为 `0bad5cf8b0b34e86dad5683ad3d9f10c33f5fdc2d67cf6e2fb5f80f5e95f4e39`；2026-10-05再次核对全部280冻结执行/测试/合约/验证工具输入一致。API JAR `2938cea81f39878b7cbfc760d92cc70ea927adb588e1db85d01c9a64f2a19463`；Worker JAR `8b46e0e9def81427baed3246241e3833469618569288307f97f1f58116f41ff0`。见 [构件事实](evidence/m2-output-preview/fixed-linux.json)和[输入核对](evidence/m2-output-preview/input-check.json)。

独立 Replay `target/forgeoj-e2e-20261004-193539-8f12e033` 显式核对这两个 JAR，V1–V13迁移后删除bootstrap；实际页面62256/62257使用本人原创可丢弃材料。v3两组输入1 2/7 8、原答案wrong：参考编译失败，确认禁用；v4修正参考后实际生成3/15，保存答案仍wrong。修改本地未保存题面、代码、思路再明确确认：保存答案3/15、版本5，三种本地编辑均保留；[确认事实](evidence/m2-output-preview/output-confirm-preserved.json)。v5新预览排队后实际故障恢复，成功后保存内容到v6使该预览失效/确认禁用。v6真正独立双程序PASSED，实际两轮送审/撤回，待审保存和新预览禁用；原六类待审写入409、旧撤回不影响新PENDING、跨Origin403，加新预览HTTP409。撤回第二次、归档v7后，3个预览、确认receipt和2个WITHDRAWN冻结送审历史可读且只读。见 [页面事实](evidence/m2-output-preview/output-browser.json)、[真实截图](evidence/m2-output-preview/output-history-viewport.png)、[实际DOM](evidence/m2-output-preview/output-archived-history-dom.txt)。全页失败截图也确实捕获；一次clip截图裁剪异常未用作布局证据，没有生成图片代替真实页面。

新生产 Worker 实际 SIGKILL：`b4e66e93-5439-4353-8410-49c7e7182199` 第一次RUNNING且有有效lease/运行沙箱时于11:41:59Z强杀，随后启动原Worker；无手工改lease或终态。11:42:33Z已自然恢复 FINISHED/参考AC，validationStatus/solutionResult仍NULL，attempts为LEASE_EXPIRED/SUCCEEDED、lease清空、旧沙箱删除；2条真实发布内容Outbox，正式Submission始终9条。见 [故障事实](evidence/m2-output-preview/output-crash.json)。本轮Worker代码已改变，明确使用新SIGKILL事实，不沿用上轮字节等同证明。

实际顺序 Library/Matrix/WorkerStop/Permissions/Learning/WorkerStart；真实作者操作后 ReviewLock/新输出待审HTTP探针/Content/Review/Output；正常较大值页面QUEUED→FINISHED/AC；暂停Worker后回退sum页面QUEUED/v0，恢复后轮询FINISHED/AC。11:51:50Z Audit通过：11正式提交、10完成/1取消/11正式发布Outbox；1双验证/1内容Outbox，3输出预览/4内容Outbox（恢复多一次），共4内容job/5内容Outbox；仅一条确认receipt、4个成功输出行、失败预览零行。输出字节/SHA、原请求/用途隔离、owner/匿名、归档后原receipt重放仍不覆盖v7通过；日志关联新建202、确认afterCommit200、Outbox发布、最终attempt提交先于ACK。六队列ready/unacked均0；API无Docker/socket/Worker/migrator配置；20基础拒绝加25补充拒绝，共45项真实MySQL权限拒绝。见 [审计](evidence/m2-output-preview/audit.json)、[输出事件链](evidence/m2-output-preview/output-events-audit.json)、[HTTP](evidence/m2-output-preview/output-http.json)、[权限](evidence/m2-output-preview/additional-grant-denials.json)。正常启动时API重建曾产生短暂代理连接错误，回退端口刻意拒绝WebSocket；不宣称运行日志全无错误。

11:52:56Z活跃Docker29.8.0核对本轮owned容器/卷/网络/Worker镜像、managed沙箱、Testcontainers和builder容器为0，关闭本人tab7/8；不清理无关资源。跨日引擎停止，2026-10-05从实际本机安装目录恢复Docker，再精确核对`forgeoj-linux-20261004-191729-3f25e7ce-backend-image`不存在，补录于[清理事实](evidence/m2-output-preview/cleanup.json)。先前辅助查询使用了错误镜像名，该零值不单独充当确切builder证据。证据采集辅助SQL最初误用j.request_id，核对schema后改为client_request_id，后续关联/HTTP/审计均通过；业务代码和冻结构件未因采集问题改变。安全证据仅白名单平台事实/本人原创临时页面，未保存Cookie/CSRF/JWT/密码、真实私有材料或原始日志。未新增依赖/第三方内容；用户未跟踪交接原字节保留；无PR/main/tag/Release。

下一单元独立自测；真实SMTP仍需服务商与授权收件箱配置；M4受控审核/发布不提前，未发布或创建PR。
