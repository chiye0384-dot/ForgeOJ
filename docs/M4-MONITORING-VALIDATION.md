# M4第7步与完整M4最终验收

2026-10-11当前验收：M4第7步及完整M4 **VERIFIED**，五项总门禁全部PASS。独立Grafana OSS许可已批准；固定Linux API335/59 suites、Worker139/26 suites、前端129/25零失败/错误/跳过，428业务/测试/前端输入、84实际提供前端与准确JAR一致。同JAR新回放覆盖Redis/ES/RabbitMQ中断恢复、真实AC、原事件受审计重送、整个索引删除重建、实际promtool及Grafana14面板/23表达式/只读浏览器登录退出。6个唯一原死信事件对应6份实测消息，每一份均匹配原事件ID和四字段契约；诊断前后数量不变，没有清空死信。本轮拥有者资源与沙箱清理全0。详见[M4总门禁](M4-GATE-AUDIT.md)、[监控验收](M4-MONITORING-VALIDATION.md)及[最终机器证据](evidence/m4-monitoring/verification.json)。M5仍PLANNED，未PR/main/tag/Release/RESUME_READY；按授权交付feat/m2-accounts后停止。

最终只读审计时间：2026-10-11 13:18:46（Asia/Shanghai）。[最终机器记录](evidence/m4-monitoring/verification.json)为当前状态依据；历史IN_PROGRESS检查点保留原结论，不改写为通过。

接受构建：`target/forgeoj-linux-20261011-092954-14188847`；本轮回放：`target/forgeoj-e2e-20261011-125213-3681478c`。复用当天09:29固定Linux已全量通过的构件，本轮重新核428输入/JAR不变后重新执行完整现场；没有冒充再次编译。API SHA256 `f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e`；Worker SHA256 `ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f`。API335/59、Worker139/26、前端129/25及静态/构建门禁零失败/错误/跳过。[源码/测试摘要](evidence/m4-monitoring/linux-source-inputs.json)保留428项与原始JUnit报告摘要；84个只读挂载/实际提供前端逐字节一致。

运行辅助与接受构建辅助有五项明确差异，以及新消息诊断辅助和运行配置；[运行工具历史](evidence/m4-monitoring/runtime-helper-history.json)逐项保留构建/当前摘要及每阶段记录。变化仅为有界异常轮询、正常HTTP重新登录/自然限流窗口、严格消息身份取证与只读证据关联，未改Java/pom/前端，不宣称全部辅助逐字节匹配旧构建。

本轮重新执行：八种真实verdict与终态通知；匿名/owner/跨域/退出/排队取消；班级和私有题；公共审核；五次原任务执行恢复（attempt3→4、终身额度保留）；双API Redis共享限流/故障保守预算与撤权边界；ES故障MySQL标题降级、整个受管索引删除404/新索引审计重建且公开行数相同；Rabbit原事件5次失败后的OPS资格/CAS/幂等/理由审计重送及真实Worker AC。原身份CLI和业务页面沿用各子单元注明日期的证据，本轮没有重新运行第六个浏览器运维夹具，不能描述为新CLI/后台页面验收。

Rabbit故障期间唯一提交/任务/原Outbox事件保留，attempt0/quota1；恢复后同一事件确认发布，真实attempt1/AC/quota0，冻结源码摘要不变，Worker持久化完成日志先于ACK。Worker只在broker故障观测后暂停并等原事件确认后恢复，避免超时缓冲先执行导致终态正确409。最终9队列unacked均0，六个执行/重试/搜索队列ready0；三个死信队列保留6个唯一已发布原事件和6份消息。每份message_id、四字段payload、摘要、持久性、exchange/routing均对应原事件；诊断前后每队列ready/unacked完全一致。[消息关联](evidence/m4-monitoring/monitoring-dead-letter-copies.json)不是清空队列或恰好一次承诺。

Prometheus实际promtool配置与三份指标语法通过，四target可抓取；私有token/JWT边界、三故障/恢复阶段共164项监控HTTP通过。Grafana13.2.3实际14面板/23表达式、Viewer只读、匿名拒绝、登录/退出清会话及30秒刷新由本轮真实浏览器/JPEG/观察文本记录。[浏览器记录](evidence/m4-monitoring/monitoring-browser.json)与[实际面板截图](evidence/m4-monitoring/monitoring-browser-dashboard.jpg)。tab5保留about:blank并markDeliverable，未关闭标签页或Codex/ChatGPT。

许可已经用户明确批准；独立未修改Prometheus3.15.0和Grafana OSS13.2.3固定digest/revision及原LICENSE/NOTICE/129项第三方告知摘要复核通过，见[原镜像记录](evidence/m4-monitoring/upstream-images.json)。ForgeOJ自有代码/面板Apache-2.0，无外部插件/云/付费功能/上游源码合并。

本轮PrepareOutage第一次紧接Healthy测量，共享IP原窗口未过期，收到正确429而预期401；保留[失败事实](evidence/m4-monitoring/monitoring-fixture-failures.json)，随后等待healthy时间+310秒自然到期，再正常HTTP登录/新实测用户名验证。未重启业务、清计数、扩大权限或弱化断言。其他旧失败见下方历史及[首轮失败](evidence/m4-monitoring/failed-20261011-085452/checkpoint.json)、[旧死信身份未闭环检查点](evidence/m4-monitoring/checkpoint-20261011-100648/checkpoint.json)。旧检查点6事件/12消息仍仅IN_PROGRESS；最终消息数使用本轮现场实际值。

先完整预检，再精确停止本项目拥有者容器并导出清理，最终审计才写VERIFIED。拥有者容器/卷/网络/镜像、builder、Testcontainers和受管沙箱均0；共享缓存镜像保留，原URL已失效。保护文件只核SHA且不变，不含任何凭据/cookie/私有源码/JUnit原始属性的交付文件。临时私网/内存监控库及就绪/心跳/P95限制见[L-045](KNOWN_LIMITATIONS.md#l-045监控完成有限本地验收生产部署限制仍存在)。

本轮关键审计命令（Node为D:/Node.js/node.exe，Docker为本机DockerDesktop准确路径，run/build必须为上述准确目录）：

~~~powershell
& 'D:/Node.js/node.exe' tools/validation/Verify-M4MonitoringEvidence.mjs 'target/forgeoj-linux-20261011-092954-14188847' 'target/forgeoj-e2e-20261011-125213-3681478c' --preflight
# 输出PREFLIGHT_PASSED，仅检查，不生成VERIFIED
& tools/validation/Replay-FixedLinux.ps1 -Action Stop -RunDirectory 'target/forgeoj-e2e-20261011-125213-3681478c' -DockerCommand '<准确Docker路径>'
& tools/validation/Verify-M4MonitoringReplay.ps1 -Action CleanupSnapshot -RunDirectory 'target/forgeoj-e2e-20261011-125213-3681478c' -DockerCommand '<准确Docker路径>'
& tools/validation/Verify-M4SearchReplay.ps1 -Action CleanupSnapshot -RunDirectory 'target/forgeoj-e2e-20261011-125213-3681478c' -DockerCommand '<准确Docker路径>'
& tools/validation/Verify-M4SearchIndexDeletion.ps1 -Action Verify -RunDirectory 'target/forgeoj-e2e-20261011-125213-3681478c' -DockerCommand '<准确Docker路径>'
& 'D:/Node.js/node.exe' tools/validation/Verify-M4MonitoringEvidence.mjs 'target/forgeoj-linux-20261011-092954-14188847' 'target/forgeoj-e2e-20261011-125213-3681478c'
# 最终输出VERIFIED；API335、Worker139、428输入、14面板
~~~

搭建/故障/恢复完整顺序见[监控运行说明](../deploy/monitoring/README.md)，原始本轮阶段时间保留在ignored回放目录monitoring-replay-progress.json。重复完整验收须新独立数据库/拥有者项目及准确构件；不能复用已经删除的数据库或私有会话。交付只限feat/m2-accounts，M5/PR/main/tag/Release/RESUME_READY没有启动。

## 历史阶段记录（不得作为当前恢复指令）

# M4 第7步当前验收与恢复入口

2026-10-11 10:35当前检查点：第7步与完整M4仍 **IN_PROGRESS**，未提交/推送。用户已批准独立Grafana OSS；当前修复源码固定Linux API335/59 suites、Worker139/26 suites、前端129/25全部通过，428业务/测试/前端输入及84实际提供前端与准确JAR匹配。同JAR三依赖暂停/恢复、真实AC、原事件人工重送、整索引删除重建、实际promtool及Grafana14面板/23表达式/只读登录退出均通过。但最终证据审计发现6个原死信事件对应12份队列消息，尚未采集消息ID逐条关联，不能以唯一事件数冒充消息份数。双API至少一次发布可能重复，需实际取证而非删除断言、清空死信或改为恰好一次。当前拥有者容器/卷/网络/镜像、builder、Testcontainers与沙箱全0；浏览器tab4保留about:blank/deliverable。额度已用89%，按用户规则不再开新完整演练。恢复入口为[本轮检查点](evidence/m4-monitoring/checkpoint-20261011-100648/checkpoint.json)及[M4监控验证](M4-MONITORING-VALIDATION.md)；下方日期段落均为历史。M5/PR/main/tag/Release/RESUME_READY未启动。

接受构建：`target/forgeoj-linux-20261011-092954-14188847`。API SHA256 `f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e`，Worker SHA256 `ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f`。当前回放 `target/forgeoj-e2e-20261011-100648-94b8da0a` 已清理，URL已失效。不能复用旧私有会话或恢复已删除的数据库。

完整构建 API335/59、Worker139/26、前端129/25，零失败/错误/跳过；业务/test/frontend428及实际提供84输入不变。运行辅助与构建辅助之间有四项明确差异，保留原摘要、最终摘要与各阶段历史：最终只读证据关联、Rabbit异常列表有界轮询、Redis普通HTTP重新登录与新实测限流窗口、Redis辅助入口。不得宣称所有构建辅助与当前源码逐字节一致。运行辅助最终清单已冻结并通过当前摘要核对。

本次真实回放覆盖八种verdict/终态通知、owner/匿名/跨域/退出、班级和私有题、公共审核、五次非浏览器执行恢复、Redis/ES故障时AC及恢复、整个索引DELETE后404/受审计重建、Rabbit原事件失败五次后OPS人工重送和真实一次AC/提交后ACK。第六个浏览器运维夹具没有新浏览器重试，仍保留原失败；不将旧第4步页面/CLI恢复描述为本次重新运行。

Rabbit最终原事件发布次数6、唯一任务/提交/事件各1、执行attempt1、quota0，冻结源码摘要一致。所有9队列unacked=0，6个执行/重试/搜索队列ready=0；三个死信队列实际ready为正式6/内容4/自测2，数据库已发布唯一原死信为3/2/1。12消息不能写成6消息或全部空队列。Outbox发布器允许多实例同时发送同一事件、仅持久化更新CAS取胜，因此重复可能符合至少一次语义；本轮只记录事实和推断，消息ID与四字段payload关联仍待新现场核验。原事件和消息不应为通过断言而删除。

本轮失败记录：Redis Outage及最终Warm均因测试JWT过期正确401，随后通过正常HTTP登录现有身份恢复；限流使用新测试用户名实测共享10次预算，不重置旧计数/角色。初次只读SQL诊断引用不存在的next_publish_at，改用实际存在字段后确认FINISHED/attempt1/published。最终证据工具仍在死信消息数断言处失败，没有生成VERIFIED文件。[安全检查点目录](evidence/m4-monitoring/checkpoint-20261011-100648/)仅保留安全元数据/摘要/真实JPEG与观察文本，不含cookie、随机监控密钥、原始JUnit属性或私有源码。

下一次接续：

1. 先核Git/额度/保护hash和428输入/JAR；当前HEAD cd2be91，分支feat/m2-accounts，监控与本轮工具/文档均未提交。没有Java业务修改时可复用此准确固定Linux构建；不是复用首轮失败JAR。
2. 在新拥有者同JAR环境完成双API与运维故障现场，先导出每个原死信消息的message_id与四字段契约/原事件关联，再清理。读取应使用保留/重入队模式，不能清空队列；不导出私有源码或凭据。按实际副本关联完善证据审计，不强求至少一次传输的消息数等于唯一事件数，也不无条件放宽数字断言。
3. 最终证据工具前半部分和当前安全检查点已经复核源码/构件/测试/三故障/Grafana/ACK/清理；其死信断言及后续最终全门禁仍未闭环。变更工具后保留新运行历史和摘要，完成严格审计、文档同步后才提交推送已授权功能分支，随后停于M4。

首轮unsigned下溢的失败运行与其旧JAR已明确被本构建替代，见[首轮失败记录](evidence/m4-monitoring/failed-20261011-085452/checkpoint.json)。2026-10-10本机native检查点仅为历史，不运行其生成器改写为当前完成状态。保护文件原hash未变。

## 以下为之前日期的实施与检查记录

# M4 第7步监控检查点

2026-10-11 09:30当前：第7步及完整M4仍 **IN_PROGRESS**。Grafana OSS许可已明确批准，固定独立镜像/告知与14面板已落地；第一轮固定Linux335 API/139 Worker/129前端通过，真实Redis/ES故障恢复、整索引删除重建和Grafana只读登录/退出已验证，但Rabbit故障发现unsigned attempt_count=0聚合下溢。已以CASE先判断修复重试数及水位差，真实MySQL回归通过，正在以当前修复源码重新执行固定Linux全量；第一轮JAR/截图是[被替代的失败运行记录](evidence/m4-monitoring/failed-20261011-085452/checkpoint.json)，不可当最终通过。首轮全部拥有者资源清理0，未提交/推送。当前恢复入口为[M4监控验证](M4-MONITORING-VALIDATION.md)；下方旧日期/待许可/额度文字均为历史。按既有授权完成现有功能分支后停止，M5/PR/main/tag/Release/RESUME_READY不启动。

2026-10-11恢复：用户对原选择题明确答复“按推荐，采用独立Grafana OSS”，许可已批准。已引入固定版本独立镜像及原告知，自有14面板、私有抓取和故障验证脚本已写好；当前新Linux全量运行中，尚未作为完整验收通过。下方2026-10-10额度78%/待许可/native64项是历史，不再要求同一许可答复。镜像出处见[记录](evidence/m4-monitoring/upstream-images.json)。

2026-10-10 22:26，本单元及完整M4均 **IN_PROGRESS**。第6步cd2be91已推送并验收；本次在其上增加监控采集基础，尚未提交/暂存/推送。已完成本机专项及真实组件回归，不等同固定Linux、准确JAR、Prometheus/Grafana面板、完整三依赖故障与M4总门禁验收。当前[本机机器记录](evidence/m4-monitoring/native-checkpoint.json)与[设计](M4-MONITORING-DESIGN.md)是恢复入口。

## 已实现并本机检查

- API请求固定分类与延迟histogram，公共缓存正/负命中/未命中/绕过，Redis成功/不可用/熔断/禁用，搜索FULL_TEXT/标题降级计数；全部标签固定，无ID、用户名、关键词、raw URI、源码、题解、隐藏测试或凭据。
- API既有最小SELECT权限、2秒只读事务的任务/结果/排队时间/完成时间/重试、Outbox、失效与搜索积压/水位/重建聚合快照。每15秒独立单线程，抓取仅读内存；采集失败保留旧事实但明确success=0和最后成功时间，45秒陈旧不就绪。连接池获取等待不在SQL的2秒限制内，未阻塞业务调度线程。
- Worker原生命周期事件的进程counter、正式判题runner耗时（含完成持久化、ACK前）、实际执行/恢复清理失败。事务事件沿用原afterCommit路径；不改租约、判题、配额或ACK。数据库当前库存用gauge，不能误称永久单调counter，双API全局库存须取max而不是sum。
- API/Worker独立JDK21私有抓取服务，默认关闭；显式开启时需32～128字符专用凭据，默认loopback，0.0.0.0另需明确opt-in。GET精确路径/方法、无查询参数扩展、401/404/405、空错误/no-store、响应256KiB、固定两线程/8队列；无API业务认证放行、无Actuator、无新Maven依赖、无Worker Web应用或权限扩展。
- API ready为Spring接受流量与成功新鲜MySQL快照，Rabbit故障时仍可接受可靠Outbox。Worker ready为Spring、原judge_task SELECT的无行探测及所有已配置消费者有效连接；连接观测有心跳检测延迟。Redis/ES不是权威数据源，故障不会把可降级平台误判为不可用。

## 实际命令及结果

所有Maven命令使用D:/JDK21、D:/Maven/apache-maven-3.9.14/bin/mvn.cmd、-o与-Dmaven.repo.local=D:/Maven/apache-maven-3.9.14/mvn_repo，不请求新依赖。测试夹具采用已缓存的固定MySQL/Redis/ES/Rabbit镜像，Spring测试实际业务端口及私有抓取端口均独立关闭。

| 命令参数（其余参数如上） | 结果 | 原始本机日志 |
|---|---|---|
| -pl forgeoj-api -Dtest=Monitoring*Tests,RequestCorrelationFilterTests,RedisIntegrationTests,ElasticSearchIntegrationTests test | 45项，零失败/错误/跳过，BUILD SUCCESS | target/monitoring-api-regression.log |
| -pl forgeoj-api -Dtest=MonitoringMetricsTests,MonitoringSnapshotTests test | 7项，零失败/错误/跳过，BUILD SUCCESS | target/monitoring-api-format.log |
| -pl forgeoj-judge-worker -Dtest=Monitoring*Tests,JudgingEventsTests,JudgeTaskListenerLoggingTests,SandboxRecoveryCleanerTests test | 17项，零失败/错误/跳过，BUILD SUCCESS | target/monitoring-worker-regression.log |

7项格式检查与前组有5项重合，合并最新JUnit用例为64个唯一用例，不把45+7+17直接当唯一总数。真实MySQL测试使用独立普通夹具用户和终态元数据，证明仅聚合/不改变旧任务次数与私有源码/诊断、Worker无缓存表权限；夹具直接造元数据不冒充真实判题AC或Rabbit故障重放。就绪测试H2仅验证Worker探测及HTTP条件，不冒充Worker生产MySQL授权验收。

回归后保存421个非隐藏输入的实际摘要；随后只调整两个API渲染类的同族连续分组及对应两个格式测试，7项直接复核通过。最终完整输入清单包含隐藏前端配置，源码总数与四项差异由证据工具实际生成；全部84前端输入仍匹配第6步证据，本轮未重新执行前端或浏览器。未将两次测试期间的不同渲染代码混写为同一个运行JAR。

## 失败与未完成项

- target/monitoring-api-snapshot.log首次MySQL专项将真实种子sum-two-integers误写sum-two，查询EmptyResult；按实际固定公开版本查询，创建专用普通夹具用户，不更改产品或旧种子。monitoring-api-snapshot-fixed.log随后15项通过，最终45项回归再次通过；原失败日志保留。
- target/monitoring-worker-runtime.log首次新测试try-with-resources写成方法调用表达式，JDK21编译拒绝；改为具名资源变量，随后17项通过，最后含HELP元数据的17项再次通过。未删除断言。
- 原第6步412输入/准确JAR证据保留为cd2be91历史；新增监控已改变API与Worker源码，必须新固定Linux构建，不沿用旧JAR证明当前实现通过。
- Grafana OSS13.2.3 AGPLv3是新许可待确认项，已向用户给出推荐与选择题，当前尚无答复；未拉取/运行其镜像或复制其代码。Prometheus3.15.0及两镜像immutable digest/告知记录尚未引入，只有版本/官方来源评估；真实promtool和面板未验证。
- 当前无Grafana浏览器、告警/面板截图、Rabbit暂停/恢复、当前JAR三依赖故障或M4五门禁汇总；不能标第7步或完整M4 VERIFIED、不能新增Release/RESUME_READY。

本机结束后Docker实时检查Testcontainers=0、managed sandbox=0；保护文件只核对SHA且不变。没有打开/关闭内置浏览器、关闭/重启/更新Codex/ChatGPT，保留之前tab3的about:blank/deliverable状态。当前额度已用78%，剩约22%；下一组完整Linux与面板/故障重放预估超出该余量，按用户规则先停，不启动新长组。

## 恢复顺序

1. 读取当前AGENTS、本记录及机器检查点，核验实际Git、保护文件摘要和额度；工作树全为本轮自有监控及文档变化，旧用户交接仍须保护。
2. 获取Grafana许可待确认问题的实际答复，不把时间经过或再次一般继续当作AGPL答复；若明确暂缓Grafana，标记该面板范围延期，完整M4仍IN_PROGRESS。
3. 沿设计完成固定摘要、原告知、私有Prometheus/Grafana/Rabbit插件配置和ForgeOJ自有最小面板；不引入插件/云/付费功能，不写真实生产配置。
4. 当前源码全量固定Linux，再准确JAR/全部回归/真实promtool+Grafana浏览器及三依赖故障，逐条关联M4五门禁与管理员审计。
5. 精确清理，最终输入/构件/SQL/消息/页面/日志复核通过后再更新VERIFIED并提交推送feat/m2-accounts；不到M5/PR/main/tag/Release。

本机证据重新校验命令：

~~~powershell
& 'D:/Node.js/node.exe' tools/validation/Verify-M4MonitoringNativeEvidence.mjs
~~~

该命令只校验本机现存报告/日志摘要、源码阶段差异、前端未变及已经导出的清理快照；不替代重读Docker现场或完整Linux，不把没有运行的验收标成通过。
