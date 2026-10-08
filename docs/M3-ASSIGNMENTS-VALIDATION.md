# M3 作业单元验收

2026-10-08，`feat/m2-accounts`，实现起点 `05d63b08f51df9ca5c719ce3ade3a0757a813ed8`。**本作业单元 VERIFIED**，最终 [机器关联检查](evidence/m3-assignments/verification.json) 为 `allPassed:true`。完整 M3 保持 IN_PROGRESS，教师查询和四项总门禁留后续。

## 范围与产品规则

V17 仅追加作业关系和既有 submission 的复合唯一索引，不重写 V1–V16 或旧数据。草稿、立即/定时发布、永久参与快照、手动追加后加入者、真实 PRECOMPLETED、冻结判题版本、本人正式尝试/成绩、独立自测、延期、取消、归档停止和复制均属本单元。教师目前只能看自己的成绩，不具备学生全部成绩或源码查询入口。

用户已确认：服务器接受时间严格早于截止、最终真实 AC 就是按时，判题完成可晚于截止；后续失败或排队不撤销完成。LEFT/REMOVED 保留本人历史摘要、次数和结果，隐藏私有题面/题解并禁止新作业执行；重新成为有效成员恢复原关系，仍受作业截止和取消约束。

同一用户、同一数据库题目 ID 的各项已开始作业共同约束题解；自由练习、提前确认及大小写 slug 别名不能旁路。多项策略需全部满足，取消的作业不再限制。原课堂自由练习个人提醒/再次确认规则保留。

## 自动检查与构件

| 实际运行 | 结果与用途 |
|---|---|
| `target/forgeoj-linux-20261007-175240-94e4a90e`，固定 Linux Backend | API217 + Worker133 通过；API 后续有修复，旧 API 不用于最终验收。Worker/POM/共享迁移全部输入与最终 API 快照一致，使用本轮 Worker JAR。 |
| `target/forgeoj-linux-20261007-182252-49e69c3a`，固定 Linux Api | API218，失败/错误/跳过均0，Maven BUILD SUCCESS；14 项作业集成测试及 V16→V17 保字节升级测试在内。 |
| `target/forgeoj-linux-20261007-183101-76bd5301`，固定 Linux Frontend | 17 suites / 92 tests；typecheck、lint、format、production build 全通过。 |

API SHA256：`f0cafb66e576b053fe7c593ee01f5709025053bafe6c536737b0e6a7b0aafde5`。

Worker SHA256：`7d2b412815ee25edc049d3ce2e7f80775b0dde974df16e63ec75088a6ee90118`。

最终 Api 日志含 Surefire fork 在 System.exit(0) 后超过30秒被结束的提示；最终 XML 为218项零失败/错误/跳过，构建成功并产出以上 JAR。这不是一次 All driver 全绿的记录，Worker 使用同轮早先独立完成且输入逐字节相同的报告。

## 失败与修复记录

- 原生聚焦测试遇到 JsonPath 泛型重载、测试帐号交叉污染、错误假定 `judge.language` 列；改正测试类型和独立帐号/真实 schema 后复验通过，保留失败日志。
- 前端初次受受限临时目录 EPERM 阻断；获准在本项目测试目录运行后，修正 Vue 模板多语句解析错误及返回同一可变对象的 mock。最终92项全部通过，不删除旧断言。
- 定时发布后手动立即开始的 `startsAt` 曾保留旧未来时间；修正为实际开始时间并增加集成回归。
- 真实红测试复现公共题 slug 大小写别名能越过作业题解锁（期望 LOCKED、实际 AC）。以授权后解析出的数据库 problem ID 检查策略，绿测试及最终218项通过；候选集合也拒绝同一题 ID 的大小写重复。
- `20261007-184349-0f99234d` 回放先做页面第二题 AC，污染旧学习回归“1题完成”的前提而失败。该轮不计验收，保留原始报告，清理其专属栈后重建；新轮先做学习回归再做真实页面，不改断言和成绩。

## 重放命令与证据关联

主回放 `target/forgeoj-e2e-20261008-074908-5182263f`：旧公共回归11次正式提交（10终态、1取消）、11 published Outbox、38实际SQL拒绝、正常WebSocket/轮询回退页面AC和7空队列通过。作业独立审计161项HTTP、6次真实正式尝试（5AC/1WA）、2个真实SUCCESS自测、2条真实PRECOMPLETED、17实际SQL拒绝、冻结资源/复合关系、commit-before-ACK和日志无凭证/源码哨兵通过。

跨截止提交 `e7d33d34-3660-40eb-b839-8a275c27d7c0` 在08:00:17.765截止前接受，停止Worker保持QUEUED，到实际截止后才恢复执行，真实FINISHED/AC仍ON_TIME_AC；原请求重放不新增尝试，新请求409，明确延期后重新ACTIVE。全程不修改服务器成绩。

真实三个账号页面：负责人选择公共及本班私有题、保存草稿、取消确认仍草稿、再次明确确认进入SCHEDULED；学生已有私人AC但本作业题解仍LOCKED，自测5+6输出11且正式次数0，正式AC后自动更新1/1与按时/次数1，再次读取题解开放；第三账号LEFT后只能看第1题/本人按时AC/次数1及本人FINISHED AC，无私有题面、编辑器、题解或新提交控件。原始DOM和截图均单列。

主回放收尾漏存前端运行输入清单，首次Evidence因缺文件而失败，不回填伪造清单。补充独立运行 `target/forgeoj-e2e-20261008-085454-c51b6eaf`，仍用上述JAR，在运行容器内捕获65个实际只读挂载输入、验证新页面真实AC，然后单独清理。最终Verifier明确记录 `runtimeProof` 和 `supplementalRuntime`，不把补充日期冒充主回放时刻。251个后端业务/测试输入均匹配最终快照；Worker/POM/共享迁移输入匹配先前Worker验收；65个前端输入与最终92项测试快照/补充运行完全一致。

主栈和补充栈的容器、卷、网络、Worker镜像、builder、Testcontainers及managed sandbox均为0；最后核对08:57:33 +08。应用和Docker Desktop保留运行，临时浏览器页转about:blank并保留。

```powershell
$dockerExe = 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
./tools/validation/Verify-FixedLinux.ps1 -Scope Backend -DockerCommand $dockerExe
./tools/validation/Verify-FixedLinux.ps1 -Scope Api -DockerCommand $dockerExe
./tools/validation/Verify-FixedLinux.ps1 -Scope Frontend -DockerCommand $dockerExe
./tools/validation/Replay-FixedLinux.ps1 -Action Start -BuildDirectory target/forgeoj-linux-20261007-182252-49e69c3a -ApiSha256 f0cafb66e576b053fe7c593ee01f5709025053bafe6c536737b0e6a7b0aafde5 -WorkerSha256 7d2b412815ee25edc049d3ce2e7f80775b0dde974df16e63ec75088a6ee90118 -DockerCommand $dockerExe
```

Start 输出新的专属目录；后续每条命令都必须指定该目录。顺序为 Matrix → WorkerStop → Permissions → WorkerStart → Library → Learning → 真实 normal/fallback 页面 AC → Audit → PrivateProblems → Verify-M3AssignmentReplay Setup → Queue → 等真实截止 → Finish → 三账号真实页面 → 作业 Audit → Replay Stop → CleanupSnapshot → Verify-M3AssignmentEvidence。

本次最终关联命令：

```powershell
node tools/validation/Verify-M3AssignmentEvidence.mjs target/forgeoj-linux-20261007-182252-49e69c3a target/forgeoj-linux-20261007-183101-76bd5301 target/forgeoj-e2e-20261008-074908-5182263f docs/evidence/m3-assignments target/forgeoj-linux-20261007-175240-94e4a90e target/forgeoj-e2e-20261008-085454-c51b6eaf
```

若一次回放完整保存实际运行清单，可省略最后一个独立运行参数。必须在清理前于实际frontend容器内遍历 `/source/frontend`（排除node_modules/dist/target/.git/.idea、`.local`、`.env*`），捕获各文件SHA256并确认 `/source` mount为只读，保存 `frontend-runtime.sha256`。不能复制构建清单冒充运行时采集。补充运行需额外真实页面AC/只读mount证据及自己的零残留记录。

聚焦集成测试中用于谓词和升级比较的 root 合成终态记录不作为真实 Worker 验收成绩。运行回放只用正常 HTTP 和真实 Worker，不写入虚假 AC。截图和 DOM 来自真实页面操作；白名单 SQL 只读验收事实和实际 grants 拒绝。完整日志、Cookie、测试库密码、原私密配置不提交。

## 有意保留的边界

全局作业策略 fence 串行化受控规模内的关键事务，未作跨班吞吐声明；每班1000永久作业、每作业20题/1000成员是有界事务限制。定时默认2秒/批50，以实际启动事务时刻冻结成员，服务停机后不伪造计划时刻的成员历史。新作业时间为UTC；旧提交 DATETIME 按既有数据库固定时区偏移换算，未实现跨时区历史迁移。已经看过的题解无法撤回；不宣称防复制或跨账号绝对保密。没有新生产 Worker SIGKILL 或外部 QQ SMTP 回放、Release、RESUME_READY。
