# M4 Redis第5步证据

2026-10-10，VERIFIED（仅第5步）。以[verification.json](verification.json)为机器验收入口，[最终验收](../../M4-REDIS-VALIDATION.md)解释复现和限制。API289+Worker133、前端124；Redis HTTP108、2个API、真实AC2、失败失效事件重放2、SQL拒绝4、7队列空、拥有者资源全0。

source-inputs/test-suites关联390业务输入、82前端挂载/实际提供输入、JAR摘要及22个实际依赖。redis-runtime关联真实服务与边界；redis-http/guard/formal/assignment/outbox/pending/cache/queues/cleanup为实际事实。浏览器6张PNG、5份实际DOM，不伪造缺失的健康页文本；空异常表无选中详情，故障页面证明匿名公共题库，HTTP独立证明详情/官方列表。

审计器初次执行发现SQL标量published实际数值1，修正其类型判断后退出0。redis-tool-inputs保留原冻结输入，redis-evidence-auditor-at-replay保留原脚本，auditor-correction记录原/现摘要。只有事后审计器改动，运行辅助/业务源码不变。所有敏感私有客户端状态仍只在忽略的target，未导出。日志只保留经过敏感字段检查的gzip。

依赖许可POM/JAR/告知摘要见redis-dependencies，开发opt-in见compose-opt-in。Redis默认关闭，故障期间只承诺各实例保守预算。完整M4/E-04均IN_PROGRESS，ES/监控、生产/容量/集群/Release仍待后续。
