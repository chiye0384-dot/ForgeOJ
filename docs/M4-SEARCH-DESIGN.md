# M4 第6步：公共搜索、可靠同步与故障降级

2026-10-10，IN_PROGRESS（设计及上游评估，尚无搜索实现/验收）。用户已授权推送第5步并接着完成下一步。第5步实现3abd1d9、检查点70042f87b6c79ff3a73c7d9c0b3032afb9ff22be已推送到chiye0384-dot/ForgeOJ的feat/m2-accounts并核验一致。原推送授权阻断已解除。

需求依据：Requirements 6.1/6.6/13.2，D-008/D-023，M4七步计划第6步。MySQL权威、ES只返回候选ID/排序；第7步监控、M5、Release、真实数据库/SMTP/管理员初始化不在本单元。设计参数为待实现工程规则，不是验收事实。

## 已确认的外部组件与许可

用户已批准官方Elasticsearch 9.5.3默认发行镜像，以免费Basic功能实现搜索；不复制其源码、不引入Kibana/付费功能/云服务。先采用JDK21 HttpClient和现有Jackson3的有限REST适配，不增加Maven ES客户端依赖，避免Boot4/Jackson3与客户端JSON栈额外装配。协议、分词和索引属于Elastic；事务、版本、重建、复核、降级和测试为ForgeOJ自有实现。

已只读核验[官方镜像](https://www.docker.elastic.co/r/elasticsearch/elasticsearch:9.5.3)的registry元数据：

- 镜像：docker.elastic.co/elasticsearch/elasticsearch:9.5.3。
- 多平台摘要：sha256:f456578fc2a620a8a4f4c21d070fff1f6070345adb2be5e5626b65be72aea350。
- linux/amd64摘要：sha256:7b69d47de433699d6df59ef2d14d82d1b0f5a14ed48cf0244a6b20cc4222484e。
- linux/arm64摘要：sha256:39ab4eab40cae7031ce82cdf7919fef0c425a5019d28e7385a2aa77440b87dd8。
- 只执行imagetools inspect；许可采用已获用户明确确认，尚未拉取/运行镜像。9.3.2旧候选已放弃，最终候选以本节9.5.3为准；首次引入仍须保留实际镜像LICENSE/NOTICE和版本事实。

官方默认发行版采用Elastic License 2.0（ELv2），并非Apache-2.0。官方FAQ说明可在自己的应用中免费使用，但不得把ES本身作为托管搜索服务提供、绕过许可限制或删除告知。ForgeOJ自有源码保持Apache-2.0；用户只访问题库搜索，不访问ES管理API。本次不分发镜像归档或派生服务端，首次Release仍需完整分发审计。依据：[官方ELv2 FAQ](https://www.elastic.co/licensing/elastic-license/faq/)、[v9.5.3原始许可记录](https://github.com/elastic/elasticsearch/blob/v9.5.3/LICENSE.txt)。

| 选项 | 对ForgeOJ的影响 | 建议 |
|---|---|---|
| 官方ES9.5.3，ELv2，免费Basic | 符合已批准ES架构；保留告知，只作题库内部搜索服务；无新付费服务 | 已确认采用；首次引入仍须核验实际告知与版本 |
| 改用Apache-2.0的OpenSearch | 需变更D-023/需求中的ES选择，重新核验协议、服务端及客户端，不是直接替换版本号 | 仅在用户坚持外部搜索服务也必须Apache-2.0时另行评估，本轮未选择 |

2026-10-10许可确认：在解释Elasticsearch/OpenSearch及ELv2影响后，用户明确回复“按推荐来”，已批准官方Elasticsearch9.5.3默认发行ELv2、免费Basic、仅ForgeOJ内部公共题搜索、保留原许可告知；ForgeOJ自有源码保持Apache-2.0。D-047已接受，不再询问该选择。许可阻断解除，尚未拉取/运行镜像、增加业务代码或V24。最新五小时额度已用85%（剩约15%），按照用户额度不足及时停止规则，停在已批准设计检查点；不是新的自动审批拒绝。下一次额度足够且用户继续时直接按M4-SEARCH-DESIGN的实施顺序，从V24公开版本/Outbox与真实MySQL合约测试开始，只完成第6步后交付并停。既有第5步VERIFIED、完整M4/E-04 IN_PROGRESS、第7步/M5/Release未开始。

## 数据与同步合约

### 公开投影与版本

V24拟增加独立public_search_version（problem_id、data_version）和public_search_outbox；不改V1～V23。每个已存在PUBLIC题有一条版本，包括归档题；班级私有题/草稿永不初始化版本或进入消息。版本上限沿用9007199254740991，初始1，治理更改事务锁该题版本、增加一次、追加一个唯一事件；version、业务变更、现有缓存失效、审计一起提交或回滚。

发布/文本修订/更正新题/归档/恢复/作废需要调用searchChanged(problemId)。当前接点在PublicReviewService.decide中已确定发布ID后，以及state完成公共状态变更后。不以普通详情访问或搜索请求补事件，不在授权前查询私有正文。官方题单不进入ES；题目的难度/标签维护SQL必须同连接事务更新搜索版本及追加事件，并按现有规约更新cache_epoch。任意直接SQL不承诺自动一致。

ES文档仅含problemId、dataVersion、active、title、statementText、difficulty、tags。strict mapping，title/statementText使用内置cjk分词，difficulty/tags精确keyword；版本/id为安全整数，active为boolean。排除输入输出样例、隐藏测试、参考程序、官方题解、用户源码、作者身份、班级、个人学习进度和凭据。不安装分词插件或复制上游代码；中文分词能力限于[内置CJK](https://www.elastic.co/docs/reference/text-analysis/analysis-lang-analyzer#cjk-analyzer)。

### Outbox、队列与有限重试

独立Rabbit交换机forgeoj.search.direct、路由public.search、持久队列forgeoj.search.public及独立死信forgeoj.search.public.dlq。消息白名单eventId/problemId/dataVersion/type，不携带标题/正文/私有payload。API内独立发布器复用既有confirm/return边界；Judge Worker不订阅、不新增ES凭据或Docker/API权限。

public_search_outbox保留publish_attempts、next_publish_at、published_at、failed_at及固定错误码。发布最多5次，退避1/2/4/8/16秒；达到上限保留失败事件，不能伪造published。消费者手动ACK，DB记录独立搜索投递状态/attempt、owner token、租约与终态；每事件最多5次索引尝试，ACK只在MySQL终态提交后。失败达到上限时追加独立死信Outbox，确认死信发布后ACK，保证进程故障可重投；不通过自动requeue形成无限紧循环。不删除失败事实或重置计数来制造通过。

消费时重新从MySQL同一快照读取题目当前scope/status、公开白名单和当前版本，旧事件允许收敛到当前版本，但不得以旧payload恢复旧文本。使用严格external版本写入；重复/旧版本409只有核验ES当前_version不小于投影版本才视为幂等。等版本必须产生确定相同投影，不用external_gte让相同版本的不同正文相互覆盖。版本规则依据[官方Index API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-index)。

归档/作废使用active=false的最小墓碑，删除旧title/statement/difficulty/tags，保留problemId与dataVersion；不物理删除该文档。ES物理DELETE的版本保留有限（index.gc_deletes），仅靠删除会使很晚的旧事件重新插入；墓碑拒绝迟到旧版本。依据[官方Delete API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-delete)。查询必须filter active=true。

## 搜索请求与MySQL复核

原GET /api/v1/problems和空关键词路径保留。新GET /api/v1/problems/search用于非空关键词全文检索，参数keyword（1～100码点）、difficulty、tag、page、size遵循原难度/标签/页大小上限50。前端题库有关键词时用新接口，无关键词继续原接口；标签选项仍从MySQL获取。

新响应items/page/size/total加mode=FULL_TEXT或TITLE_FALLBACK、降级固定提示、公开高亮片段。ES返回候选ID与排序/命中文本定位，API永不直接转发_source、ES原始错误、未经验证的total或HTML片段。复核全部候选当前PUBLIC/ACTIVE、版本及公开正文摘要；元数据始终取当前MySQL。归档、私有、未知ID或版本不匹配时丢弃整次ES候选结果并回退现有MySQL筛选，以免返回未经复核的计数/旧高亮。

采用有界候选全集，最多1000个匹配ID；EStrack_total_hits为精确有限计数。返回超过1000、计数与候选数不一致、重复/非法ID、分片失败或查询超时，整体回退标题搜索，不把截断的total当准确总数。候选分批50个查MySQL，在同一REPEATABLE_READ快照复核并分页，保留_score降序/id升序的稳定排序。该上限是V1安全/资源约束，不是全文容量或性能验收；页面明确提示正文搜索暂不可用且仍能筛选标题。全文大结果集游标属于后续设计，不悄悄扩大1000上限。

关键字、难度和标签以结构化Query DSL构造，不接受调用者DSL/索引名/任意ES URL。高亮仅返回从当前MySQL公开标题/正文生成的纯文本segments（text/matched），每题最多2个片段、每片段160码点；Vue按文本节点与mark显示，不用v-html。不得利用高亮输出索引中旧版本或私有内容。

使用现有cache_epoch.public作为读请求的公开内容代数。在ES候选查询前后以及MySQL快照中比较代数，内容变更则回退；记录搜索同步可读水位，只在该代数内全部事件已成功索引且refresh完成时前进。水位落后、dead-letter未解决、索引UUID变更/丢失、重建中均回退MySQL。即使缓存或ES声称健康，也不能跳过当前公开状态/版本复核。

## 管理重建

OPS_ADMIN/SUPER_ADMIN允许；CONTENT_REVIEWER/普通账号拒绝，仍复用AdminService.operationsWork、fence、当前版本/角色/首次改密检查、Origin/CSRF及既有管理预算。GET /api/v1/admin/search/status展示白名单水位/积压/固定错误码；GET /rebuilds分页元数据。POST /rebuilds只收expectedVersion/clientRequestId/reason，返回原唯一作业回执；同键重放不再重建，同键不同参数409，同时只允许一个活跃重建。

持久重建记录QUEUED/RUNNING/READY/SUCCEEDED/FAILED，有限attempt及租约token；管理写只排队并原子审计，不在HTTP请求/管理fence锁内执行长ES操作。新索引名称由作业UUID生成，调用者不能指定。按MySQL公开ID键集分页每批100投影到新代数索引；墓碑同样投影，防止并发旧事件复活已归档题。扫描期间继续记录增量，切换前追赶到明确数据库代数，并核验该代数无待处理/死信、文档版本、mapping、refresh完成及目标UUID。失败保持旧索引/回退，不把半索引标记READY。

切换使用[官方原子alias操作](https://www.elastic.co/docs/manage-data/data-store/aliases)，当前target/切换意图必须先持久化，ES切换后才CAS完成MySQL状态。若在两者之间崩溃，恢复扫描查询实际alias UUID并完成同一作业，不用猜测或创建新作业。查询只有MySQL记录UUID与alias实际UUID一致时才FULL_TEXT。该协议保护跨DB/ES非原子窗口，不宣称两个系统共同事务。

旧索引只保留带ForgeOJ明确作业身份的元数据；本单元不提供任意DELETE API或自动历史清理。重建可恢复已删除/清空的本项目搜索索引，但不修改公共题或判题事实。后台不展示正文/源码/隐藏测试。生产索引清理另行确认，不借验收删除真实数据。

## 连接、安全与故障边界

默认FORGEOJ_SEARCH_ENABLED=false。连接250ms、单请求1s超时、故障冷却2s/一次恢复探测；最大4个并发外部请求、无无界任务队列、请求/响应JSON各有上限（请求1MiB，候选响应256KiB；重建逐文档/有界批次）。超过限制按故障回退，HTTP远端错误不记录正文/key/认证头。默认禁redirect，拒绝空用户名/密码，生产HTTPS验证证书；URL来自受控配置而非HTTP参数。

验证/开发单节点只绑定loopback9200或隔离Docker网络、认证开启、Basic不启trial、堆512MiB/容器1GiB、无公网ES管理接口、无Docker socket。具体Compose凭据为唯一fixture，禁止读取既有.smtp.qq.local。不为ES调整全机服务/WSL或关闭Codex；宿主资源不足时停止并保存检查点，不自行扩大内存。TLS/角色最小授权及实际镜像LICENSE/NOTICE在首次接入验证，不能把设计当通过。

## 实施与验收顺序

1. ELv2采用已确认（D-047）；首次拉取时保存实际LICENSE/NOTICE/来源、运行版本，核验本节镜像摘要及免费Basic/no-plugin边界。
2. V24公开版本/Outbox/消费状态/重建表和最小grants；真实MySQL验证公共/私有隔离、原子回滚、并发版本与幂等、Worker/API SQL拒绝。
3. 有界REST适配及真实固定ES：strict白名单、中文正文检索、排序/难度/标签、高亮安全、重复/乱序/墓碑、暂停/恢复/清空、超时/损坏/候选注入/计数复核。
4. 管理重建/死信可观测与真实Rabbit确认/有限尝试/manual ACK/进程故障/lease恢复；切换窗口崩溃、角色撤销、并发重建、删除索引后的完整重建。
5. 最小前端完整检查；全部固定Linux门禁；相同JAR真实Worker/已验收账户、课堂/作业/审核/运维及Redis回归；多身份浏览器和降级提示。
6. 源码/JAR/实际运行输入/DB/消息与队列/日志/截图事实关联，精确清理本次资源，更新证据与限制，再提交推送现有功能分支后停止第6步。

当前没有V24文件、ES容器、搜索实现、测试或VERIFIED证据。第一次业务实现必须保持旧Redis单元证据为历史已验收，不沿用其422/124证明新源码通过。完整M4和E-04仍IN_PROGRESS；完整最终门禁、第7步监控及M5/Release不提前实现。

## 本次停止检查点

2026-10-10许可确认：在解释Elasticsearch/OpenSearch及ELv2影响后，用户明确回复“按推荐来”，已批准官方Elasticsearch9.5.3默认发行ELv2、免费Basic、仅ForgeOJ内部公共题搜索、保留原许可告知；ForgeOJ自有源码保持Apache-2.0。D-047已接受，不再询问该选择。许可阻断解除，尚未拉取/运行镜像、增加业务代码或V24。最新五小时额度已用85%（剩约15%），按照用户额度不足及时停止规则，停在已批准设计检查点；不是新的自动审批拒绝。下一次额度足够且用户继续时直接按M4-SEARCH-DESIGN的实施顺序，从V24公开版本/Outbox与真实MySQL合约测试开始，只完成第6步后交付并停。既有第5步VERIFIED、完整M4/E-04 IN_PROGRESS、第7步/M5/Release未开始。

第5步推送已成功核验。当前无V24/搜索实现或新测试，不能将已批准设计标为VERIFIED。恢复时保留保护文件、重新核验现场Git/额度和实际Docker可用性，沿用已确认ELv2，不重问。
