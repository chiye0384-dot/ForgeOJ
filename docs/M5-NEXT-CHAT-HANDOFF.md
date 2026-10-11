# ForgeOJ详细交接：M4完成，下一阶段M5

交接日期：2026-10-11，Asia/Shanghai。当前启动入口：[M5-NEW-CHAT-START](M5-NEW-CHAT-START.md)。本文件区分已经完成的事实、下一阶段建议与尚需外部条件的门禁。

## 1. 接手目标和权限

用户希望换一个新对话，能直接知道读什么、完成了什么、下一步及完成标准。本次授权是整理交接和创建对话；新对话第一轮只读接手，报告M5建议，**等用户明确“继续”才写实现或启动长验证**。普通已批准工程细节自主处理，不重复确认ES/Grafana许可，不自动购买云资源、部署公网、发送邀请或创建Release。

先进入`D:/Java项目/ForgeOJ`。桌面保存项目“简历Java项目”根在父目录`D:/Java项目`，父目录不是Git仓库，不能在那里误执行本项目Git/Maven。

权威顺序：实际Git/文件/当前运行事实→本文件及AGENTS顶部→最新M4最终证据→需求/路线/已接受决策。旧文档的日期、未完成状态和“下一步”是历史。`docs/NEXT-CHAT-HANDOFF.md`、M3交接、`M4-NEXT-CHAT-HANDOFF.md`、`M4-STEP4-NEW-CHAT-START.md`不再指导重新实施第3～7步。受保护M2交接禁止打开。旧native/失败检查点未变成最终通过。

## 2. 项目与Git基线

ForgeOJ是面向Java学习者和小型教学班级的在线判题平台。核心是可运行、合法复用、能解释的可靠异步判题、权限隔离、内容治理、班级作业及有界故障恢复；不自行扩展微服务、Kubernetes、论坛、多语言等范围。

| 项目 | 当前事实 |
|---|---|
| 仓库 | `D:/Java项目/ForgeOJ` |
| 分支 | `feat/m2-accounts`，长期功能分支名，不代表只完成M2 |
| 完成M4实现提交 | `30b7f53619f9c1aaf930ea1df64f82f5d42271be` |
| 提交说明 | `feat(monitoring): add private metrics and verify M4 recovery gates` |
| origin | `https://github.com/chiye0384-dot/ForgeOJ.git` |
| 推送 | 实现提交已推送并以git ls-remote核对远端相同；本交接另有后续文档提交，准确HEAD以Git为准 |
| 交接前工作树 | 业务/测试/部署/工具均提交；只有受保护`docs/M2-NEXT-CHAT-HANDOFF.md`未跟踪，`.smtp.qq.local`ignored |
| 总体状态 | M-1/M0/M1/M2/M3/M4均VERIFIED；M5/V1.1 PLANNED；无PR/main合并/tag/Release/RESUME_READY |
| 技术 | Java21、Spring Boot4.1.1、Maven3.9.14、原生MyBatis4.1.0、Vue；MySQL/RabbitMQ/Redis/ES/独立监控 |

根`pom.xml`聚合API和Worker。`forgeoj-api`负责业务、权限、迁移；`forgeoj-judge-worker`独立消费和Docker沙箱；`frontend`为现有Vue界面；`deploy/compose.dev.yml`是开发基础设施；`tools/validation`为隔离验收；`deploy/monitoring`为M4最小监控配置。

当前数据库版本迁移V1～V24，位于`forgeoj-api/src/main/resources/db/migration/`，已应用版本不可重写，变更追加迁移。`db/devdata`仅dev/test；真实部署不得启用dev种子或公开测试密码。API无隐藏测试/Docker权限，Worker无user/班级/账号隐私权限。

## 3. 已经完成的里程碑

### M-1/M0：工程起点和真实纵向判题

已建立需求、决策、许可/归属与工程结构，API/Worker分离；Submission/JudgeTask/Outbox原子接受、真实Java21单文件Main标准输入输出判题。MySQL为最终事实源，自有代码Apache-2.0，上游能力不称自研。

### M1：可靠异步判题与安全边界

事务Outbox、publisher confirm、至少一次投递、幂等消费、attempt/lease/heartbeat/fencing、有限自动重试/死信、数据库配额、queued取消、owner通知/轮询恢复、独立Docker沙箱约束完成。八种结果、真实Worker故障/恶意程序/日志/权限/清理有证据：[M1总审计](M1-GATE-AUDIT.md)。普通Docker只适合明确受控范围，不能当绝对安全隔离。

### M2：账号、学习与出题

普通注册/邮件激活、JWT/refresh独立会话、撤销/改密/找回/旧邮箱绑定，公开题库/官方题单、个人题单/草稿/历史/进度、AC或显式确认后的官方题解完成。作者草稿、独立参考/题解真实双通过、不可变验证/送审/撤回、仅参考程序生成输出及明确确认、独立自测生命周期完成。[M2总审计](M2-GATE-AUDIT.md)为完整结论。

对应历史单元有受限真实QQ邮件投递证据，但不代表未来生产邮件已配置；部署需用户另行提供合法配置，不能读`.smtp.qq.local`取凭据。

### M3：班级与作业

OWNER/ASSISTANT/MEMBER、邀请码/退出/移除/恢复/转让/归档，私有题、永久作业快照、按服务器接受时间判断截止、旧AC预完成、本人提交/自测/成绩、教学全员记录及只读正式作业源码完成。当前权限逐次复核；离班保留本人摘要而关闭私有内容，教师不因此得到私人练习/旧预完成源码/自测。[M3总审计](M3-GATE-AUDIT.md)及四个M3子单元验收有证据。

### M4：七步全部完成

| 单元 | 已完成 | 证据入口 |
|---|---|---|
| 1/2 管理身份设计/实现 | 独立管理域、三角色、初始化/恢复CLI、强制改密、会话撤销、SUPER维护、持久审计 | [身份验收](M4-ADMIN-IDENTITY-VALIDATION.md)、[CLI](M4-ADMIN-CLI.md) |
| 3 公共审核治理 | 不可变审核/重验/批准/驳回、原作者TEXT/关联CORRECTION修订、下架/恢复/作废、反馈案件 | [公共审核](M4-PUBLIC-REVIEW-VALIDATION.md) |
| 4 运维恢复 | 正式/内容验证/输出预览/自测异常元数据查询；原任务有界恢复、原Outbox恢复、CAS/幂等/理由审计 | [运维验收](M4-OPERATIONS-VALIDATION.md) |
| 5 Redis与降级 | 公共Cache-Aside、可靠失效Outbox、共享限流/本机保守预算、安全身份辅助、故障回MySQL | [Redis验收](M4-REDIS-VALIDATION.md) |
| 6 公共搜索 | 官方ES全文、版本Outbox、MySQL公開复核、有界失败/死信、标题降级、清空与整个索引删除审计重建 | [搜索验收](M4-SEARCH-VALIDATION.md) |
| 7 私有监控/总门禁 | API/Worker固定标签、只读快照/陈旧状态、专用Bearer抓取、Prometheus/Grafana14面板、三故障和完整关联 | [监控验收](M4-MONITORING-VALIDATION.md)、[总审计](M4-GATE-AUDIT.md) |

5/5总门禁PASS：普通账号后台403；后台角色越权拒绝；Redis/ES/Rabbit故障恢复；整个搜索索引从MySQL重建；管理动作能关联管理员、对象、时间和理由。

## 4. 最新准确构件和验收证据

当前机器事实：`docs/evidence/m4-monitoring/verification.json`，status/fullM4为VERIFIED、M5 PLANNED、release/resumeReady false。说明见`M4-MONITORING-VALIDATION.md`顶部和`M4-GATE-AUDIT.md`。

| 事实 | 已接受值 |
|---|---|
| 固定Linux构建目录 | `target/forgeoj-linux-20261011-092954-14188847` |
| API | 335测试/59 suites，零失败/错误/跳过 |
| Worker | 139测试/26 suites，零失败/错误/跳过 |
| 前端 | 129测试/25文件；type-check/lint/format/test/build通过 |
| 业务/test/pom/前端输入 | 428项逐文件SHA匹配接受构建 |
| 实际只读挂载且提供前端 | 84项逐文件匹配 |
| API JAR SHA256 | `f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e` |
| Worker JAR SHA256 | `ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f` |
| 完整新回放 | `target/forgeoj-e2e-20261011-125213-3681478c`，容器/数据库已删除 |
| 监控HTTP | 164项，含token/JWT边界和三依赖中断/恢复 |
| Grafana | 14面板/23表达式/30秒刷新，真实Viewer只读、匿名拒绝、登录退出、JPEG/观察文本 |
| 本轮执行恢复 | 5个非浏览器原任务attempt3→4；不冒充第六个旧浏览器任务的新重试 |
| Rabbit新故障 | 唯一提交/任务/原事件，故障attempt0/quota1；原事件5失败后OPS重送，真实attempt1/AC/quota0、持久化完成先于ACK |
| 死信 | 6唯一原事件/6份消息，逐条ID/四字段payload/摘要/路由/持久性匹配，取证前后数量不变 |
| 清理 | 拥有者容器/卷/网络/专用镜像、builder、Testcontainers、受管沙箱均0；共享镜像保留 |

原始JUnit XML、backend/frontend日志、准确JAR在上述ignored构建目录。安全摘要在`docs/evidence/m4-monitoring/linux-source-inputs.json`，运行HTTP/SQL/消息/浏览器证据在同目录。运行辅助有五项明确构建后差异，加新诊断/运行配置，见`runtime-helper-history.json`；不是Java/pom/前端构件变化，不声称全部辅助匹配旧构建。

最新回放复用同日已经全量通过的JAR，重新核源码/构件并重新跑现场，没有“又编译一次”的说法。其他子单元CLI/多角色业务页面保留原日期；本轮新增浏览器为Grafana，未重跑全部旧CLI/页面。交接新增Markdown不影响428编译输入。

必须保留历史结论：

- `failed-20261011-085452/`：首轮SQL unsigned聚合下溢，失败JAR被替代，不能当最终通过。
- `checkpoint-20261011-100648/`：6事件/12消息，未逐条采集ID，仍IN_PROGRESS；不能把本轮6份消息倒填旧现场。
- `native-checkpoint.json`等2026-10-10本机专项仅历史，不运行生成器覆盖当前结果。

旧前端/Grafana URL均失效，没有当前运行中的验收组。`target`可能保存自有临时随机密钥/cookie，不能打印、复制、提交或当生产会话。测试结果可核对，不代表服务仍运行。

## 5. 已接受决策，不能为部署改变的边界

- D-045：正式/VALIDATE/OUTPUT_PREVIEW/SELF_TEST每任务终身最多人工追加一次执行，仅自动预算耗尽的临时平台失败，最大执行预算10。保留原任务/冻结依据/用途/接受时间/旧attempt/失败/审计，不恢复用户verdict、损坏快照、活动租约。作业按原accepted_at计算。
- 原未发布失败Outbox事件终身最多恢复投递一次，ID/payload/旧失败次数不重置；OPS/SUPER需要当前资格、expectedVersion、clientRequestId、理由，权限/CAS/凭证/Outbox/审计原子提交。
- MySQL为事实与权限源；Redis缓存/ES候选/WebSocket不能绕过当前授权。Redis故障只承诺各实例保守预算，不宣称仍有全局共享额度。
- 至少一次可能有同事件多份消息；必须核对幂等业务副作用。死信保留不等于活动队列不空，不为漂亮数字清队列。临时诊断取出后全部重入队会改变redelivery/顺序，不是纯GET或产品任意消息浏览API。
- 迁移append-only、最小授权、API无Docker/Worker无隐私权限；不得改源码/判定、跳过配额/lease/fencing、ACK提前或关闭认证来通过验收。

许可已明确批准，不重复询问：ForgeOJ自有代码/面板Apache-2.0；D-047官方ES9.5.3免费Basic/ELv2仅内部题库搜索，保留告知，无托管ES/付费/改OpenSearch；D-048官方Grafana OSS13.2.3/AGPLv3独立未修改免费服务，标准HTTP连接，保留原告知/源码出处，无Cloud/外部插件/源码合并；Prometheus3.15.0/Apache-2.0独立服务。

固定监控digest：Prometheus`efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e`；Grafana`b28bae15e219c998fb0e0424ed724930cc61b1f61fb404d47c862f9a23f9e572`。对应镜像/版本/revision/告知清单在`upstream-images.json`及`docs/licenses/`；无latest，告知不得改字节。新上游若改变费用/许可/服务边界需实际研究和授权；批准现有选择不授权自动购买云资源。

## 6. M5要做的事情与当前缺口

M5证明项目可在全新实际Linux主机按文档部署、安全受控运行。Windows Docker Desktop中固定Linux构建/回放已经通过，不等于云/主机部署、生产安全、备份/回滚或性能验收。

权威范围来自Roadmap9、Requirements14.4/14.5/15、Performance-Test-Plan、Resume-Evidence-Matrix E-06/E-07：

1. 单机Compose部署Nginx、API、Worker、MySQL、Redis、RabbitMQ、ES和监控，真实HTTPS、健康/冒烟有效。
2. 对外业务仅HTTP/HTTPS，基础设施和监控不得直接暴露公网。服务器维护访问另有安全方案，不能不经授权关SSH或把SSH混为业务公开端口。
3. 密钥不进Git/镜像/日志/错误，Secure cookie、精确Origin/CSRF、可信代理和邮件URL与实际HTTPS一致。
4. Flyway空库安装/版本升级、迁移器/API/Worker最小授权，禁止dev种子/公开测试账号进入真实部署。
5. MySQL每日加密备份到服务器外，至少7日备份/4周备份；恢复到全新数据库并核业务/权限/Outbox/审计。
6. 应用镜像真实回滚；明确schema兼容、接受新写/在途任务/消息处理、备份恢复边界，不将应用回滚说成自动数据库downgrade。
7. 固定Linux性能报告，完整环境/数据集/commit/脚本/预热/并发/时长/原始结果/错误率/资源；Web req/s与判题submissions/min分开，不预写QPS/P95或优化百分比。
8. 至少一轮受控真实用户试用与问题记录；合成账号/脚本不能冒充真实试用。向别人邀请/发送邮件需明确授权或用户自行完成。
9. 安全边界及运维手册、生产监控持久化/访问/告知。

尚未完成或确认：实际Linux/ECS、云账号/规格/地区/预算、域名/DNS/正式TLS、公网发布授权、生产邮件、异地备份目的地/密钥托管、真实试用人员/授权、主机性能数据、生产部署文件、备份恢复及镜像回滚。新对话先核用户实际环境，不能编造已经有。

现有`deploy/compose.dev.yml`只为开发MySQL/Rabbit；`tools/validation/compose.*-replay.yml`及监控为临时验证，有测试常量、loopback随机端口/source挂载和临时库。不能直接重命名成生产配置。M4 Prometheus TSDB/Grafana数据库为内存文件系统，M5需明确持久化与访问。

## 7. 建议分组和完成条件

以下是接手建议，不是已接受的新M5设计或完成状态。第一轮先提出；用户继续后形成M5设计/实施计划，产品范围变化记Decision Log。

| 顺序 | 单元 | 具体工作 | 完成标准 |
|---|---|---|---|
| 1 | 设计与环境盘点 | 现有构建/镜像/网络/配置/迁移/权限盘点，拓扑、配置契约、资源/威胁/故障/回滚方案、待决输入 | 可审阅且逐项对应Requirements；不等于部署门禁 |
| 2 | 可复现部署包 | 独立Compose/应用镜像、Nginx静态前端/API/WebSocket、秘密注入、持久化、最小权限、健康/冒烟与固定版本 | 隔离环境实际检查，安全失败，无测试密码/dev种子/基础设施公网端口，许可齐全 |
| 3 | 全新Linux安装/升级 | 用户确认目标后按文档空库安装/升级、管理员初始化、HTTPS和真实业务/权限 | OS/内核/资源/commit/digest/端口/页面/日志实证，不能用Docker Desktop替代主机 |
| 4 | 加密备份恢复 | 每日/周保留、异地副本、密钥/访问、全新隔离库实际恢复对账 | 真实备份/恢复、摘要、业务/权限/会话/任务/审计一致，不覆盖原库 |
| 5 | 镜像回滚 | 两个明确兼容版本、切换方案、在途任务/消息/schema边界及实际演练 | 旧镜像可用、业务正确，无丢失/重复副作用，不虚构schema自动回退 |
| 6 | 固定环境性能/可靠性 | 脱敏数据集/负载/预热/并发/时长/采样，分链路基线、故障和按观测定位 | 原始CSV/JSON/日志+环境/错误率/P50/P95/P99/吞吐/资源/限制；无同条件对比不写提升 |
| 7 | 受控试用及总审计 | 用户授权真实试用，问题/修复/复测、部署/恢复/回滚/运维/许可/性能汇总 | M5五门禁实际PASS；之后另行处理PR/main/tag/Release/个人简历准入 |

缺主机/域名时，获得“继续”后仍可做设计、部署包和隔离验证；将实际主机/公网/异地/试用诚实保留未完成，不停掉所有独立工作，也不绕过外部授权硬部署。

## 8. 必读和按需查阅顺序

第一轮依次读：

1. `M5-NEW-CHAT-START.md`、仓库`AGENTS.md`顶部、本文件。
2. `M4-GATE-AUDIT.md`、`M4-MONITORING-VALIDATION.md`顶部、`evidence/m4-monitoring/verification.json`。
3. `ForgeOJ-Roadmap.md`9/10、`ForgeOJ-Requirements.md`14.4/14.5/15。
4. `ForgeOJ-Decision-Log.md`D-045～D-048、`KNOWN_LIMITATIONS.md`相关安全/监控/故障条目、`UPSTREAM-AND-LICENSE.md`。
5. `Performance-Test-Plan.md`、`Resume-Evidence-Matrix.md`准入/E-06/E-07。
6. 根与模块pom、application.properties、frontend/package.json、deploy/README.md/compose.dev.yml、deploy/monitoring及README、Verify-FixedLinux/Replay-FixedLinux和replay overlays。不要展开带凭据的实际Compose config。

按变更再读管理CLI、SMTP、Redis、搜索、监控设计及源码/迁移，不必先读几十份历史日志。用rg核实际包和文件，API auth/admin/operations/cache/search/observability、Worker messaging/sandbox/observability为相关边界；架构/威胁/归属文档按实际文件查，缺项记计划，不编造现成文件。

## 9. Windows工具与可复核命令

交接核存在：JDK`D:/JDK21`；Maven`D:/Maven/apache-maven-3.9.14/bin/mvn.cmd`，repo.local`D:/Maven/apache-maven-3.9.14/mvn_repo`；Node`D:/Node.js/node.exe`；Docker`C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe`。Docker Server只读返回29.8.0，旧专用项目/managed sandbox/Testcontainers标签均无容器；全清理证据是2026-10-11 13:18快照。接手时状态会变，需重新核。

第一轮只读可执行：

```powershell
Set-Location -LiteralPath 'D:/Java项目/ForgeOJ'
git branch --show-current
git rev-parse HEAD
git status --short
git log -3 --oneline
git ls-remote origin refs/heads/feat/m2-accounts
Get-FileHash -LiteralPath 'docs/M2-NEXT-CHAT-HANDOFF.md','.smtp.qq.local' -Algorithm SHA256
Get-Content -LiteralPath 'docs/evidence/m4-monitoring/verification.json' -Raw | ConvertFrom-Json | Select-Object status,fullM4,apiTests,workerTests,frontendTests,m5,release,resumeReady
& 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe' version --format '{{.Server.Version}}'
```

额度通过工具读取，不根据旧24%快照假定仍够。网络失败先判环境/权限，不称代码失败，不无限请求。Docker命名管道/Node真实路径受sandbox限制时用正规执行授权机制；不关闭Docker/WSL/Codex绕过。

用户已授权实施后，固定Linux全量真实入口：

```powershell
& tools/validation/Verify-FixedLinux.ps1 -Scope All -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
```

没有`Build-FixedLinux.ps1`。前端完整检查`npm run verify`；`lint`/`format`会改文件，不是只读核对。局部Maven检查实际JDK21/模块/POM/repo.local，java.version不能证明运行JDK。Linux工具会启动builder/Testcontainers/沙箱，有并发/残留前置条件，第一轮不跑。

旧证据可保存事实预检，但无需为交接重复：

```powershell
& 'D:/Node.js/node.exe' tools/validation/Verify-M4MonitoringEvidence.mjs 'target/forgeoj-linux-20261011-092954-14188847' 'target/forgeoj-e2e-20261011-125213-3681478c' --preflight
```

这不是新故障或M5验收；源码/工具改变或target删除时诚实记录不能匹配，不改旧hash。正常最终生成器会复制/写证据时间，第一轮不跑；native生成器是历史。`target/Continue-MonitoringReplay.ps1`等ignored编排/存档只作本轮辅助，不是M5生产接口。

## 10. 文件保护、应用连续性和清理

| 文件 | 允许动作 | 保持SHA256 |
|---|---|---|
| `docs/M2-NEXT-CHAT-HANDOFF.md` | 仅元数据/哈希；禁止读/打印/复制/编辑/暂存/提交/删除 | `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054` |
| `.smtp.qq.local` | 同上，ignored也不能打开 | `6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907` |

不自主关闭、退出、重启、终止或更新Codex/ChatGPT。禁止广泛按进程名kill、WSL shutdown或服务停机影响客户端；只停核对所有权的本任务准确PID/容器，不删无关数据/共享镜像。

历史两次浏览器进程崩溃与关闭最后一个内置自动化标签相关，未验证修复前禁止tab.close()/UI关标签/复现。临时页面导航about:blank并markDeliverable；markHandoff仅真正未完成工作。tab5曾保留，跨会话重新查清单/ID，不能盲用；没有标签可以新建。此规则不保证阻止OS/客户端自身崩溃。

禁止git add .、git clean、reset --hard、强推；显式审阅/暂存，保留用户untracked。真实密钥/cookie/JWT/私有源码/JUnit原始属性不打印、不写交接或截图；实际.Config.Env/Compose config仅可提取必要安全字段。隔离公开测试常量不能变生产凭据。

## 11. 故障教训与已解决方式

- MySQL unsigned在GREATEST保护前已下溢，当前构件用CASE先判断attempt和水位再减，真实MySQLattempt0/非正水位差回归通过，不恢复旧写法。
- 长回放JWT过期正确401；正常HTTP重登现有身份，不重建账号/改权/关认证。
- 新用户名仍共享IP预算；Healthy后立即PrepareOutage正确429，最终等healthy+310秒自然到期，再正常登录/双API测10次预算，保留失败，不清计数/重启API。
- Rabbit确认超时缓冲可能在恢复后先执行，终态人工恢复409正确。验收在故障观测后暂停准确Worker，等同原事件OPS资格/CAS/幂等/审计重送确认再恢复Worker，不能硬改终态。
- 双API至少一次竞态旧6事件/12消息不能只凭推断通过。新现场每份ID/payload核对、全部重入队/核数量。清理前preflight，避免丢现场取证机会。
- Classroom fixture固定普通用户3，要早于Redis新fixture插入；仅隔离验收编排规则。
- 整索引删除使用实际Action Run，清理后Verify，按受管UUID/元数据核所有权，不猜不存在action。PowerShell浅JSON显示警告需读实际保存文件核对，不能凭显示判原文件截断。
- 网络、npm ECONNRESET、Docker权限问题按层诊断；用户曾忘记联网，恢复后继续，不能无限网络重试或重启桌面应用。
- Git EOL警告不等于构件漂移；上游原告知用.gitattributes -text且比较暂存原字节，不为格式改许可证。

## 12. 每组验收、M5完成及发布规则

每个已授权单元明确范围/合约/权限/事务/故障，做相应测试和真实运行，将当前源码/测试/准确JAR或镜像/前端/SQL/消息/页面/日志关联；保留失败/旧事实/原告知；精确清理并同步需求/决策/路线/限制/证据。配置要验证实际行为，不写镜像实现的无意义测试，不删断言。

改源码后旧JAR不能证明新实现。无编译输入变化可严格hash复用准确构件，但说明复用及新执行范围；工具代际独立记录。真实MySQL、普通fixture身份、多角色浏览器和实际权限/并发/故障是依据，不用root冒充业务账号、H2替代MySQL授权、模拟截图替代真实页面。

M5须五门禁全部真实PASS才VERIFIED：全新Linux按文档部署；备份恢复到新库；镜像回滚；可复现性能报告；受控真实试用/问题记录。外部依赖未完成就明确IMPLEMENTED/IN_PROGRESS/PLANNED，不能写整个完成。M0～M5通过才讨论V1.0 Release，PR/main/tag/Release仍需实际授权；个人脱稿解释/小修改复测等RESUME_READY准入不自动完成。

现有功能分支普通已验收提交/推送已有授权，M4已经推送。M5沿用分支还是另开、PR/合并/发布时机应在计划说明，不能自动创建工作树/PR。逐项暂存、查秘密/告知/保护hash，推送后核远端SHA。完成有限单元或额度不足时交代结果/下一步，不无限跨组推进。

用户偏好中文清楚解释事实/推荐/选项利弊；普通工程选择主动完成不反复确认；额度不足下一组及时停并留准确检查点。用户说“停”立即停止编辑/编译/运行，不自动恢复。

## 13. 第一轮给用户的结果

报告已完成只读接手：实际路径/分支/HEAD/工作树、已读入口、M-1～M4已VERIFIED、M5真实缺口和建议首个设计/环境盘点单元、所需主机/域名/备份/试用外部信息。不要报告旧M4还没完成，不跑新长演练，不购买/公网部署；等待用户“继续”后按已理解的有限计划推进。
