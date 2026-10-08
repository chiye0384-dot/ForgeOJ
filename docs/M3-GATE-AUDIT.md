# M3 完整门禁审计

2026-10-08，feat/m2-accounts，实现基线4afce92。本轮 **VERIFIED，4/4 PASS**，完整M3提升为VERIFIED。补充两项真实MySQL直接回归、固定Linux全量API、运行构件/输入、历史证据和现场清理已完成最终机器关联。仅测试、只读审计工具与文档变化；产品、迁移、grants、依赖、前端及Worker实现没有修改。

## 1. 范围与证据时间

按 [路线图 M3](ForgeOJ-Roadmap.md#7-m3班级与作业) 四门禁核对 Requirements 9.1–9.3、私有题作用域及D-014/D-015/D-042、L-016。四个单元的实测证据分别保留，不把历史操作写成本轮重新执行：

- 班级成员：2026-10-06，62HTTP、三账号、加入/邀请/退出/移除/转让/归档、单一OWNER和SQL边界，见 [验收](M3-CLASSROOM-VALIDATION.md)。
- 班级私有题：2026-10-06，155HTTP、3个真实双PASSED验证、WA/AC/AC、2自测、个人提醒/再次确认及撤销访问，见 [验收](M3-PRIVATE-PROBLEMS-VALIDATION.md)。
- 作业：2026-10-08，161HTTP、6真实正式判题、2预完成/2自测、真实接受在截止前而AC在截止后、三账号页面和本人离班摘要，见 [验收及分轮次输入记录](M3-ASSIGNMENTS-VALIDATION.md)。
- 教学记录：2026-10-08，151HTTP、5真实正式判题、2预完成/1自测、OWNER/ASSISTANT/MEMBER及降级清空/归档LEFT历史页面，见 [验收](M3-TEACHER-RECORDS-VALIDATION.md)。此单元最后的真实回放是本轮运行实现关联的依据。

原作业Surefire退出警告、补充运行输入采集、各单元首轮失败继续保留；不合并为一次All driver成功。当前187个运行时生产输入（含POM/合约）和68个前端输入与最后接受的运行版本相同；新API的333个运行类/资源/依赖条目也逐字节一致。本轮新增终态fixture仅验证真实MySQL关系/权限，不冒充真实Worker判题。

## 2. 四项门禁与直接覆盖

| 门禁 | 可核对的实现及证据 | 当前结论 |
|---|---|---|
| G1 班级作业端到端 | 私有题冻结验证/发布；公共及本班私有题作业选择、草稿/定时/立即启动、永久快照、正式Submission/Task/Outbox/真实Worker、本人/教学成绩及共同题解策略。`formalReplayLinksExactlyOneSnapshotAndHalfwayFailureRollsBack`、`acceptedBeforeDeadlineAcFinishedAfterDeadlineIsOnTimeAndHardCutoffRejectsNew`、`currentVersionChangeRequiresDraftReviewAndStartedFormalUsesFrozenVersion`、`assignmentSelfTestIsScopedAndNeverCreatesFormalGradeOrAttempt`；各单元真实页面、源摘要、commit-before-ACK、七空队列。 | PASS |
| G2 完整权限矩阵 | 班级治理和教学权限区分；当前账号/session、MySQL角色、班级/作业/正式关联逐次复核。新增`allTeachingReadRoutesAndAssignmentWritesFollowCurrentRoleAcrossTransfer`直接覆盖三个教学GET的OWNER200/ASSISTANT200/MEMBER403/无关404/匿名401，负责人转让后旧负责人ASSISTANT仍可读、再降级403、离班404、停用401；同时验证MEMBER不能创建/修改/发布/复制/取消/加参与者，ASSISTANT可以。原班级、私有题隔离、session事务复核、grants和CSRF测试保留。 | PASS |
| G3 后加入/退出/移除/归档 | `scheduledStartSnapshotsOnlyCurrentMembersAndAddsLateMemberExplicitly`、`exitKeepsOnlyOwnSummaryAndRejoinPreservesOriginalParticipant`、新增`removedParticipantKeepsMaskedOwnHistoryAndOwnerRestoreReusesParticipation`、`archiveStopsScheduledAndEndsActiveWithoutResurrectionAndCopyGetsFreshDraft`、并发转让/接受/退出保持单一OWNER。新测试断言REMOVED本人完成/次数/首次AC不变、题面和描述隐藏、原本人结果可读、新执行/题解/自测/凭邀请恢复拒绝，OWNER恢复后原参与关系仍只有1条。 | PASS |
| G4 教师不能读取无关私人代码 | `teacherGradesAndAttemptsRequireExplicitFormalScopeWithoutPrivateOrPrecompletedLeak`、`precompletedIsRealOwnerVersionProofAndDoesNotCreateFakeAttemptOrTeacherAccess`、`teacherCurrentRoleAndSessionAreRecheckedForEverySourceRead`及私有自测隔离；同题自由练习、原私人AC/PRECOMPLETED、自测、其他作业/班级、非法/缺失ID统一拒绝。真实页面与SQL证明只读对应正式原源码，普通owner-only结果GET不扩大；成绩页和尝试列表不批量返回源码/原私人提交ID。 | PASS |

## 3. 当前角色矩阵

所有权限均限定本班资源；“参与者”指永久作业参与关系，教学身份本身不自动产生本人作业执行权。

| 当前身份/状态 | 本班治理 | 私有题及作业教学维护 | 本人作业执行 | 三个教学读取接口 |
|---|---|---|---|---|
| ACTIVE OWNER | 可任免/邀请/转让/归档 | 可维护 | 有参与关系且生命周期允许 | 200 |
| ACTIVE ASSISTANT | 不能任免/邀请/转让/归档 | 可维护 | 有参与关系且生命周期允许 | 200 |
| ACTIVE MEMBER | 无 | 无，有效请求403 | 有参与关系且生命周期允许 | 403 |
| 无关用户或其他班级角色 | 无，404 | 404 | 404 | 404 |
| LEFT / REMOVED | 无，404 | 私有内容404；本人历史只保留摘要/结果 | 新执行404 | 404 |
| 匿名、退出会话、停用账号 | 无，401 | 401 | 401 | 401 |
| ARCHIVED班级中有效教学身份 | 加入/发布/新执行停止 | 历史只读，恢复不复活旧作业 | 不允许新执行 | 200，历史只读 |

权限失败为空体；错误参数可先返回400，不能用缺字段请求证明角色403。前端源内容为只读纯文本，刷新/后续请求复核身份，401/403/404、账号或路由变化清空并丢弃晚到响应；无法追回此前已复制/显示的内容。

## 4. 本轮检查与失败记录

原生新增两项首次1/2通过；另一项测试发布请求漏了必需`startsAt`，返回400而不是预期角色403。修正测试请求、保留失败日志，未修改业务代码或删除断言，最终2/2零失败/错误/跳过。日志target/m3-gate-focused-first.log与m3-gate-focused.log。

本轮全量API日志仍有测试数据库结束后的连接/定时扫描警告，以及最后迁移测试在关闭连接池时的Surefire fork退出超时提示。Maven最终退出0、BUILD SUCCESS，实际XML为223/0/0/0；保留日志及其SHA，不把测试零失败写成日志零警告。该提示针对Maven自己的测试JVM，不是关闭Codex的操作；本轮没有修复这个测试退出问题。

固定Linux Api运行target/forgeoj-linux-20261008-122009-e2b2ab16实际BUILD SUCCESS，API223/42 suites，失败/错误/跳过0，全部41项M3用例通过。本轮新API JAR SHA256：02c4ef6034341e7c0c7681000a91c0c74b9db2c1f2c7f8cd6e1b05200968141c；其333个BOOT-INF/classes与BOOT-INF/lib运行条目和已接受真实回放API完全相同。已回放API SHA为1fce68604e3845fbf7e8b3ea3bebcad4d4abaa0c1fa6e0cbcf1f467751ef0f16，Worker为58da20d24421b4c7c28065b64d2b424212f1ffa98cac24de6d14096ab79510dd。新JAR未另开一次浏览器回放，包容器摘要的变化不冒称运行内容变化。

原Worker运行target/forgeoj-linux-20261008-110105-55b90a88：133/23 suites，零失败/错误/跳过；前端target/forgeoj-linux-20261008-112857-b4f26f08：18 suites/97项，类型/lint/格式/生产构建通过。本轮没有再执行这两个未变模块，而是核对112项Worker/共享输入、68项前端和实际容器/source只读清单、187项生产输入、254项最新后端输入和原报告。两个新测试仅改变AssignmentIntegrationTests.java，其余后端源与原构建相同。

[机器证据](evidence/m3-gate/verification.json)为allPassed:true，记录全部41个M3用例名、四门禁关联、各历史证据实际SHA与日期、当前运行条目相等和freshBrowserOrWorkerReplayThisAudit:false。只读清理采集2026-10-08T05:49:54.4853937+00:00确认Docker可用，builders/Testcontainers/managed沙箱及全部forgeoj-e2e容器/卷/网络/任务镜像均0；不删除或停止其他进程、服务、WSL或应用。证据汇总脚本在构建快照之后收尾，仅审计工具自身变化，不参与产品运行；结果记录当前实际脚本摘要。旧交接和SMTP字节未变，未暂存。

复现入口（全是一次性测试；Docker Desktop已开启）：

```powershell
./tools/validation/Verify-FixedLinux.ps1 -Scope Api
./tools/validation/Get-M3GateCleanup.ps1
python tools/validation/Verify-M3GateEvidence.py target/forgeoj-linux-YYYYMMDD-HHMMSS-ID --cleanup target/m3-gate-cleanup.json
```

本机实际Docker参数为 `-DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'`；Python使用 `C:/Users/Lenovo/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`。本轮API目录为上文记录的实际目录；原Worker/前端/回放产物必须仍在，汇总脚本才可重新核对。

Python汇总工具仅依赖标准库，读取真实产物、源码和脱敏证据，不发HTTP、不读SMTP配置、不改数据库。临时产物目录以实际运行输出为准，不伪造已清理数据库的历史状态。部署/生产运行必须应用V17；不改变M2的独立自测、旧版本和账号验收结论。

## 5. 保留边界

不核验真实教师身份；OWNER是班级管理者。单机MySQL/全局策略fence、有界人数/题数和分页、2秒定时扫描及停机后实际快照、固定历史数据库时区、手动教学记录刷新、私人题修订为独立新题而非完整关联纠错仍按 [限制](KNOWN_LIMITATIONS.md) 保留。没有吞吐/容量、分布式限流、高可用、生产HTTPS、管理员后台或新QQ邮件/Worker SIGKILL结论。

旧未跟踪M2交接及忽略SMTP文件保留，不纳入提交。Codex不关闭/重启/自动更新，也不关闭或复现最后内置标签页的崩溃触发；规避不代表客户端缺陷修复。四门禁已闭环，本轮只结束M3；下一步M4最小管理员认证与角色设计待用户继续，不在本轮实施M4、合并main、创建PR、tag、Release或RESUME_READY。
