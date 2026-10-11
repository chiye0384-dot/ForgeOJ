2026-10-11当前验收：M4第7步及完整M4 **VERIFIED**，五项总门禁全部PASS。独立Grafana OSS许可已批准；固定Linux API335/59 suites、Worker139/26 suites、前端129/25零失败/错误/跳过，428业务/测试/前端输入、84实际提供前端与准确JAR一致。同JAR新回放覆盖Redis/ES/RabbitMQ中断恢复、真实AC、原事件受审计重送、整个索引删除重建、实际promtool及Grafana14面板/23表达式/只读浏览器登录退出。6个唯一原死信事件对应6份实测消息，每一份均匹配原事件ID和四字段契约；诊断前后数量不变，没有清空死信。本轮拥有者资源与沙箱清理全0。详见[M4总门禁](M4-GATE-AUDIT.md)、[监控验收](M4-MONITORING-VALIDATION.md)及[最终机器证据](evidence/m4-monitoring/verification.json)。M5仍PLANNED，未PR/main/tag/Release/RESUME_READY；按授权交付feat/m2-accounts后停止。

> 以下旧日期的阶段、额度、待许可、未完成和“下一步”文字为历史快照；实时状态以上方当前验收与对应最终证据为准。

# M4 实施步骤

2026-10-11 09:30当前：第7步及完整M4仍 **IN_PROGRESS**。Grafana OSS许可已明确批准，固定独立镜像/告知与14面板已落地；第一轮固定Linux335 API/139 Worker/129前端通过，真实Redis/ES故障恢复、整索引删除重建和Grafana只读登录/退出已验证，但Rabbit故障发现unsigned attempt_count=0聚合下溢。已以CASE先判断修复重试数及水位差，真实MySQL回归通过，正在以当前修复源码重新执行固定Linux全量；第一轮JAR/截图是[被替代的失败运行记录](evidence/m4-monitoring/failed-20261011-085452/checkpoint.json)，不可当最终通过。首轮全部拥有者资源清理0，未提交/推送。当前恢复入口为[M4监控验证](M4-MONITORING-VALIDATION.md)；下方旧日期/待许可/额度文字均为历史。按既有授权完成现有功能分支后停止，M5/PR/main/tag/Release/RESUME_READY不启动。

2026-10-10 22:26最新检查点：用户继续后第7步监控已 **IN_PROGRESS**。自有聚合指标、只读快照/陈旧状态、默认关闭的私有抓取及Worker接线已加入；API45项真实Redis/ES/SQL回归、最终格式7项和Worker17项通过，合并64唯一用例，428最终输入冻结、84前端未变。见[监控检查点](M4-MONITORING-VALIDATION.md)和[本机机器记录](evidence/m4-monitoring/native-checkpoint.json)。这是Windows/JDK21专项，不是当前固定Linux/JAR/Prometheus/Grafana/浏览器/三故障/M4总门禁。Grafana OSS13.2.3新AGPL许可待实际用户答复，未引入镜像；当前额度78%已用，不足下一组长验收，按用户规则停。工作树未暂存/提交/推送，HEAD为已交付搜索cd2be91；完整M4/E-04仍IN_PROGRESS，第6步历史VERIFIED保持，M5/PR/main/tag/Release未开始。下方旧日期中的“第7步未开始/不启动”仅为历史，不能覆盖本次继续授权。

2026-10-10 20:30最终验收：M4第6步公共搜索 **VERIFIED**，见[M4搜索验收](M4-SEARCH-VALIDATION.md)与[机器证据](evidence/m4-search/verification.json)。固定Linux API321/54 suites、Worker133/23 suites均零失败/错误/跳过，前端129/25及全部检查通过；412输入及84个实际提供的前端输入匹配准确构件。同JAR HTTPS/Basic、73项搜索HTTP、107项Redis回归、真实判题/权限/浏览器、有限死信及内容清空重建通过；另一个同JAR临时环境完成整个索引删除、标题降级和受审计重建（恢复阶段11项HTTP）。两个环境拥有者资源、builder、Testcontainers、沙箱全0。完整M4与E-04仍IN_PROGRESS，第7步/M5/Release未开始。按已授权交付feat/m2-accounts后停。下方旧日期记录只表示历史，当前状态以本段及最终机器证据为准。

2026-10-10 历史第5步检查点：M4第5步 Redis 与故障降级已 **[VERIFIED](M4-REDIS-VALIDATION.md)**。固定Linux API289+Worker133=422、前端124项及全部检查通过；同JAR双API、真实Redis故障/清空、108项HTTP、两次真实AC、浏览器撤权/降级、数据库事实及精确清理通过。完整M4仍IN_PROGRESS，第6步搜索已进入设计/上游评估，第7步监控未开始。下方带日期记录为历史，以本段和最终机器证据为准。

2026-10-09：第4步运维与有限人工恢复 **[VERIFIED](M4-OPERATIONS-VALIDATION.md)**，D-045已接受。第1步设计完成，第2/3/4步均已验收；固定Linux278+133/124、真实恢复/浏览器/审计/ACK/资源清理和当前输入关联通过。本轮完成第4步功能分支交付后停止，第5～7步尚未开始，完整M4仍IN_PROGRESS。下方2026-10-08接续文字是历史。

2026-10-08，状态 `IN_PROGRESS`，第1步设计、第2步管理身份与第3步公共题审核治理已完成，第2/3步均 VERIFIED（见[身份验收](M4-ADMIN-IDENTITY-VALIDATION.md)、[审核验收](M4-PUBLIC-REVIEW-VALIDATION.md)）。用户已确认 D-043/D-044。第3步交付 `c60b75d60bfe210944a9cefde17cade92bb8a71f`，分支 `feat/m2-accounts`；原 M4 实施起点 `0e213e1adc3ae04de7d9160622ea8442940dac28` 不是当前 HEAD。下一单元第4步尚未开始，新对话按[启动说明](M4-STEP4-NEW-CHAT-START.md)先只读准备、待用户继续。M-1～M3 VERIFIED；完整M4未验收，M5/V1.1不启动。

依据 [Requirements 4.2/12/13/14](ForgeOJ-Requirements.md)、[Roadmap M4](ForgeOJ-Roadmap.md#8-m4管理后台搜索与降级)、D-016/D-022/D-023及E-04/E-05，按依赖拆分：

| 步骤 | 交付范围 | 该步完成条件 |
|---|---|---|
| 1 管理身份设计 | 独立账号、角色矩阵、一次性初始化、会话、撤销、管理员维护与持久审计；核对现有认证和数据库授权 | [首单元设计](M4-ADMIN-IDENTITY-DESIGN.md)可供实现，重要待选规则得到用户回答；不以文档代替验收 |
| 2 管理身份实现与验收（VERIFIED） | V18追加迁移、初始化/恢复CLI、独立后台认证、本人改密与退出、SUPER_ADMIN账号维护、审计查询、后台最小页面 | 真实MySQL迁移/最小grants/事务/权限/并发测试，固定Linux全量检查及实际多身份页面；旧普通账号和M3流程无回归；无默认密码 |
| 3 公共题审核治理（VERIFIED） | 不可变案件阅读/重验、批准/驳回、原作者TEXT/关联CORRECTION、下架/恢复/作废和反馈案件 | [最终验收](M4-PUBLIC-REVIEW-VALIDATION.md)：关联版本权限、最新真实双PASSED、CAS/幂等、公开复核、敏感审计、旧事实不变及真实多角色页面通过 |
| 4 运维闭环（VERIFIED） | 正式判题、内容验证、自测的异常任务/attempt/死信查询，按用途限定的人工幂等重试与理由审计 | CONTENT_REVIEWER拒绝，OPS/SUPER允许；终态与重试资格复核、并发/重复/响应丢失、Outbox/ACK故障检查；不得改用户源码或把WA改AC |
| 5 Redis与降级（VERIFIED） | 公共题/官方题单Cache-Aside、可靠失效Outbox、普通和管理会话安全缓存、共享限流与短期幂等辅助 | 清空/故障回退MySQL，撤销与降级均不越权，保守单机限流及数据库配额保留，事件重放/TTL抖动/热点单重建和恢复验证 |
| 6 公共搜索（VERIFIED） | Elasticsearch公开题标题/题面纯文本/难度/标签检索、版本化Outbox同步、有界重试/死信、管理重建、MySQL回退 | 不索引私有题/源码/隐藏测试/题解；旧事件不覆盖新版本；候选ID经MySQL公开状态复核；清索引后重建和故障降级通过 |
| 7 监控与M4最终门禁（VERIFIED） | 最小Prometheus/Grafana面板、异常/队列/重试/失效/搜索积压和降级状态，Redis/ES/RabbitMQ故障演练及最终证据关联 | Roadmap五项总门禁全部PASS，当前源码/实际构件/浏览器/数据与证据关联；完整M4才可升VERIFIED，未等同M5上线或Release |

每个实现单元都先确定请求/事务/权限合约，执行直接回归、真实运行与精确清理后更新证据；发现范围变化先记录。完整M4不是把各模块“启动成功”相加。

V1.1评论/讨论不提前实现：Redis评论限流与社区举报只在其功能存在时接入，本轮先实现V1公共题反馈案件。监控和任务查询只展示白名单元数据，不借后台权限提供任意SQL、任意源码或隐藏测试浏览。

用户在第1步设计确认后授权“完成下一步”，第2步已完成并停止；之后要求继续，现已进入第3步，见[M4公共题审核设计](M4-PUBLIC-REVIEW-DESIGN.md)。第3步已VERIFIED，见[M4公共审核验收](M4-PUBLIC-REVIEW-VALIDATION.md)；修订权限D-044已确认并实现，下一单元第4步运维，须等用户再次继续，后续步骤不提前启动。不执行真实管理员初始化、不修改现有数据库或SMTP配置，不关闭、更新、重启Codex或关闭其内置自动化标签页。
