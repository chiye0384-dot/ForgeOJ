2026-10-11当前验收：M4第7步及完整M4 **VERIFIED**，五项总门禁全部PASS。独立Grafana OSS许可已批准；固定Linux API335/59 suites、Worker139/26 suites、前端129/25零失败/错误/跳过，428业务/测试/前端输入、84实际提供前端与准确JAR一致。同JAR新回放覆盖Redis/ES/RabbitMQ中断恢复、真实AC、原事件受审计重送、整个索引删除重建、实际promtool及Grafana14面板/23表达式/只读浏览器登录退出。6个唯一原死信事件对应6份实测消息，每一份均匹配原事件ID和四字段契约；诊断前后数量不变，没有清空死信。本轮拥有者资源与沙箱清理全0。详见[M4总门禁](M4-GATE-AUDIT.md)、[监控验收](M4-MONITORING-VALIDATION.md)及[最终机器证据](evidence/m4-monitoring/verification.json)。M5仍PLANNED，未PR/main/tag/Release/RESUME_READY；按授权交付feat/m2-accounts后停止。

> 以下旧日期的阶段、额度、待许可、未完成和“下一步”文字为历史快照；实时状态以上方当前验收与对应最终证据为准。

# M4 第7步：最小监控与最终门禁

2026-10-11许可决定已接受：用户在原选择题明确回复“按推荐，采用独立Grafana OSS”。采用上述固定Grafana OSS13.2.3/AGPLv3、独立未修改免费本地服务与许可告知边界；不再等待许可答复。下方2026-10-10评估及额度是历史。第7步/完整M4已VERIFIED，实际镜像摘要、面板与完整证据见[M4监控验收](M4-MONITORING-VALIDATION.md)；下方为原设计过程。

2026-10-11演练修正：监控SQL在减无符号重试数/水位前先CASE判断，真实MySQL覆盖未执行任务和非正差值。Rabbit暂停的发布确认超时不等于消息未送达；恢复时可能消费先前缓冲消息并完成，使终态人工恢复按原规则409。为确定性验证有限投递恢复，先观察Rabbit故障/Worker不就绪和零执行持久任务，再暂停本次精确Worker、恢复broker、按原OPS接口重送同一事件并取得真实确认，最后恢复Worker检查AC/提交后ACK/配额释放。所有原失败和确认记录保留，不重置任务/事件，不改变运行规则。页面30秒刷新，Prometheus15秒抓取。

2026-10-10：用户在第6步交付cd2be91后授权“继续”。本单元IN_PROGRESS，完整M4未VERIFIED。已确认范围来自Requirements14.2、Roadmap M4和七步计划；不启动M5、生产配置、云费用、PR/main/tag/Release。当前五小时额度已用61%，每组昂贵验证前重读，不足下一组时保留诚实检查点并停止。

## 指标与边界

只读聚合指标，不接受查询参数、任意SQL或重试命令，不含用户名/邮箱、任务/提交/题目ID、源码、隐藏测试、题解、请求路径/关键词、凭据或失败诊断。所有label为代码固定枚举，实例区分由Prometheus target标签承担，不以用户输入产生时间序列。

API和Worker各自保留进程内固定容量counter/histogram；不增加模块依赖、不让API控制Docker、不让Worker获得公共搜索/Redis/后台表授权。计数在已有成功返回或事务afterCommit之后记录，不因监控失败改变提交、权限、租约、ACK和sandbox清理语义。进程重启counter归零，SQL当前状态仍为权威；多API的数据库全局gauges在面板中取max而不是相加。

| 领域 | 数据来源 | 可观测指标 |
|---|---|---|
| 请求 | 原request.completed过滤器，固定业务分类及method/status class | 请求数、耗时固定bucket histogram；不收集raw URI/query |
| 缓存及降级 | 实际PublicCache/RedisSupport成功、缺失、拒绝/不可用路径；实际搜索FULL_TEXT/TITLE_FALLBACK返回 | 公共缓存命中/未命中/绕过、Redis成功/不可用、搜索模式计数；未配置与故障不混为一谈 |
| 任务与结果 | API已有最小权限SELECT，独立固定SQL聚合快照 | 固定FORMAL/VALIDATE/OUTPUT_PREVIEW/SELF_TEST状态数量、最老排队时间、判题耗时sum/count、固定verdict分布、累计attempt重试/死信、Outbox积压 |
| 搜索与失效 | API已有缓存/搜索表SELECT | 失效待交付/失败、搜索待同步/死信、公开水位差、重建状态 |
| 队列 | 已批准RabbitMQ自带Prometheus插件，私有15692 | broker up、ready/unacked、消费者；不新增第三方exporter或Worker读取管理接口 |
| Worker | 实际生命周期afterCommit及清理catch | claim/完成/ACK/恢复事件、执行耗时、两种容器清理失败计数；无业务ID label |

SQL聚合每15秒由单独任务采集，固定查询、每条最多2秒，读取只读快照，不锁业务行、不写schema、不改变grants。数据库故障或未完成迁移时collection_success=0；保留上次快照及last_success时间，面板明确显示陈旧，绝不以“0积压”冒充采集失败。抓取请求只读取内存，不能触发DB/Rabbit/ES/Redis访问。

## 私有抓取服务

采用现有JDK21的HttpServer提供最小只读抓取，API/Worker独立实现，不引入Actuator/Web到非Web Worker或新Maven依赖。固定Prometheus text0.0.4格式，自有渲染通过真实promtool检查；遵循[官方格式](https://prometheus.io/docs/instrumenting/exposition_formats/)及[JDK21 API](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html)。官方Actuator/Micrometer是通用替代，当前选择固定少量指标以保留非Web Worker边界；不实现通用监控框架。

默认forgeoj.monitoring.enabled=false，无监听。显式开启才读取专用至少32字符随机token；缺失/不合规启动失败，异常消息不包含secret。默认bind127.0.0.1、API9091/Worker9092，临时容器显式bind0.0.0.0但不发布抓取端口、不经前端代理；所有路由要求独立Bearer，普通/管理JWT不能替代。只允许GET /metrics、/health/live、/health/ready，其他路径404、方法405，无参数/子路径扩大、无CORS、no-store，不回显请求/异常。工作线程及队列有界，响应限256KiB，关闭只停止此bean拥有的server/executor。

live表示进程抓取服务存在。API ready要求Spring接受流量及最近成功的只读MySQL快照；Rabbit不可用时API仍可将已接受请求持久化到Outbox，不把依赖断连误等同立即丢失接受能力。Worker ready要求Spring接受流量、judge_task表既有SELECT权限的无行读取成功、所有实际配置消费者运行且连接有效，最近观测不足45秒。消费者连接状态受Rabbit心跳及客户端故障检测延迟影响，不能声称瞬时检测暂停；broker抓取up及队列曲线另作故障证据。Redis/ES故障可降级，不作为整个平台DOWN。采集SQL的2秒限制不覆盖连接池获取连接等待；独立单线程不会占用业务调度，超时/陈旧都会明确展示。主业务端口不开放抓取路径，API现有业务认证不改变。生产网络/TLS及持久化属于M5，本单元仅私有临时网络和本机loopback；不把临时token写Git或上传。

## 最小面板、版本及许可待确认点

上游只作为独立未修改服务；ForgeOJ实现指标、dashboard/provisioning、演练、关联审计，不复制Prometheus/Grafana源码。官方版本核对2026-10-10：

- [Prometheus3.15.0](https://github.com/prometheus/prometheus/releases/tag/v3.15.0)，[Apache-2.0 LICENSE](https://raw.githubusercontent.com/prometheus/prometheus/v3.15.0/LICENSE)。
- [Grafana OSS13.2.3](https://github.com/grafana/grafana/releases/tag/v13.2.3)，[AGPLv3 LICENSE](https://raw.githubusercontent.com/grafana/grafana/v13.2.3/LICENSE)，[官方许可说明](https://grafana.com/licensing/)。许可义务尚未在既有决定中批准；在确认前不拉取/运行该镜像、不复制其代码或宣称许可已接受。

推荐免费OSS独立容器、保留原告知/版本与对应源代码链接、不修改Grafana、不使用插件/Cloud/企业付费功能。依据许可对独立聚合的条款，自有通过标准HTTP连接的ForgeOJ代码继续Apache-2.0；不合并Grafana源码或将其改标Apache。若未来修改或分发Grafana本体，重新核对相应源码提供/告知义务。该边界是按独立程序组织的工程选择，不是授权任意混合代码。

两个版本实际镜像多平台/amd64摘要、revision、原LICENSE/NOTICE及第三方告知须在引入时取自固定镜像核对，不用latest浮动tag、不假造digest。Grafana仅本机loopback临时端口、必须登录、禁匿名/注册/外部插件/遥测、只读预置dashboard；Prometheus不发布端口、15秒scrape、5秒timeout、最多24小时临时数据。所有token/密码仅临时生成文件，权限最小；精确拥有者清理。

面板至少包含服务采集/陈旧、API请求量/延迟、broker深度、最老排队/执行时间、结果、重试/死信、缓存/Redis与搜索降级、失效/索引积压/水位、容器清理失败；禁止杜撰QPS/P95性能提升。

## 验收顺序与五项总门禁

1. 固定标签、无敏感数据、真实HTTP端口/token/方法/路径/关闭、SQL真实聚合/权限拒绝/陈旧状态专项；原业务测试保留。
2. 当前源码固定Linux全量、准确JAR与实际运行输入关联；不复用第6步旧JAR证明新源码。
3. 同JAR全部既有回归、真实Prometheus抓取/promtool及Grafana实际面板/浏览器证据，退出/撤权不暴露后台元数据；不开放公共metrics。
4. 真实Redis、ES、Rabbit暂停/恢复。Rabbit故障时请求若已接受，SQL Submission/task/Outbox事实保持，消息发布有限重试，不提前ACK、不丢失配额；恢复后同事件审计确认并真实Worker完成。超过原有限发布预算的事件必须按已有OPS/SUPER受审计恢复，不能悄悄重置失败次数或伪造AC；原失败事实保留。
5. 逐项关联M4五门禁：普通后台403、三角色越权拒绝、三个依赖故障、整个索引删除可重建、管理员/对象/时间/原因追溯。历史单元证据明确各自日期；业务源码改变则补当前对应回归，不把没有执行admin-recover的环境称新CLI验收。
6. 精确资源清理、保护文件与许可摘要、当前源码/JAR/消息/SQL/页面/日志关联通过后才标第7步与完整M4 VERIFIED；E-04/E-05技术证据状态逐条更新，仍无Release或RESUME_READY。按授权交付现有功能分支并停止，不开始M5。

本设计的就绪、SQL指标和私有token规则是现有最小监控范围的工程选择；Grafana新AGPL许可确认单独保留，未批准时可继续ForgeOJ自有指标及测试，但不能把第7步完整验收写成已完成。
