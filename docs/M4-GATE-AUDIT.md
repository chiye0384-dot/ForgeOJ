# M4最终门禁审计

2026-10-11：**VERIFIED，5/5 PASS**。本轮准确JAR与全部当前证据关联时间2026-10-11 13:18:46（Asia/Shanghai）。[监控验收](M4-MONITORING-VALIDATION.md)、[最终机器证据](evidence/m4-monitoring/verification.json)和[源码/测试摘要](evidence/m4-monitoring/linux-source-inputs.json)共同限定验收范围。

| 门禁 | 已通过证据 | 状态 |
|---|---|---|
| 普通用户访问后台403 | admin-http普通拒绝、搜索/运维HTTP拒绝，API全量角色负向测试 | PASS |
| 三种后台角色越权被拒绝 | admin-http强制改密/角色边界，公共审核与OPS恢复拒绝，SUPER维护约束与原身份证据 | PASS |
| Redis/ES/RabbitMQ故障 | 双API真实暂停/恢复、共享预算/降级、MySQL可靠Outbox、真实AC；Rabbit原事件OPS重送且完成提交先于ACK | PASS |
| 整个索引删除可重建 | 受管索引所有权/UUID检查、DELETE后404、新索引/审计1queued+1terminal、公开行数不变及全文恢复 | PASS |
| 管理操作可追溯 | 持久管理员/对象/occurredAt/理由，搜索重建及原事件投递恢复，与冻结业务事实和原子审计关联 | PASS |

固定Linux API335/59、Worker139/26、前端129/25；428当前业务输入、84实际前端输入及准确JAR匹配。真实Grafana浏览器14面板/只读/匿名拒绝/退出、23表达式与promtool通过。6个原死信事件/6份消息逐条ID与四字段契约核对，诊断前后保留数量相同，六个执行/搜索队列为空、全9队列unacked0；不是全队列为空。五次本轮HTTP执行恢复，未冒充新浏览器/CLI恢复。原许可摘要与精确清理全0通过。

第2～6步原CLI/多角色页面及其他有日期证据保留其原构件，本轮在当前JAR上重新执行上述HTTP/真实业务/故障门禁，新增浏览器是Grafana。首轮SQLunsigned下溢和旧未关联死信身份检查点保留失败/IN_PROGRESS；本轮新现场关联后才完成技术验收。M5仍PLANNED，尚无Release或RESUME_READY。
