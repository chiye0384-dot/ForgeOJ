# ForgeOJ 最小监控

ForgeOJ自有指标、14项dashboard和provisioning属于Apache-2.0；Prometheus3.15.0/Apache-2.0与Grafana OSS13.2.3/AGPLv3作为独立未修改服务。固定镜像与原许可见[上游记录](../../docs/UPSTREAM-AND-LICENSE.md)和[镜像证据](../../docs/evidence/m4-monitoring/upstream-images.json)。镜像内自带的数据源插件属于上游发行内容；没有安装外部插件。Prometheus TSDB及Grafana数据库在本单元均为临时内存文件系统，不是生产持久化方案。

默认业务进程不启动监控。显式开启`FORGEOJ_MONITORING_ENABLED=true`后，使用专用32～128字符随机`FORGEOJ_MONITORING_TOKEN`，默认127.0.0.1，API9091/Worker9092。容器网络抓取需显式`FORGEOJ_MONITORING_BIND=0.0.0.0`与`FORGEOJ_MONITORING_ALLOW_PRIVATE_NETWORK_BIND=true`；不发布这些端口、不经过前端代理。普通/后台JWT不能替代监控token。私有路由为GET `/metrics`、`/health/live`、`/health/ready`，无查询参数。

此目录配置针对验证工具建立的独立临时网络。`tools/validation/compose.monitoring-replay.yml`通过专用overlay接入两API/Worker与Rabbit原生`rabbitmq_prometheus`插件；所有基础设施和指标端口不发布，Grafana仅loopback临时端口、禁匿名/注册/遥测/更新/外部插件自动安装。工具每次生成私有随机凭据到ignored target并限制Windows目录ACL，容器只读挂载；禁止打印展开后的Compose配置、环境值或将私有文件作为交付证据。

运行顺序：固定Linux全量通过→`Replay-FixedLinux.ps1 -Action Start -EnableRedis -EnableSearch`（必须提供新构件准确SHA）→管理员bootstrap→`Verify-M4MonitoringReplay.ps1 -Action Setup -RunDirectory <准确目录>`→管理员HTTP身份准备→普通流程/八种结果/权限→Classroom/PrivateProblems→Redis Setup/公共审核/运维故障恢复/搜索 Setup→Redis Healthy/GuardFailure。Classroom夹具固定普通用户3，必须早于Redis夹具插入。Setup一次性创建凭据并重新创建同JAR业务容器；后续使用Check，不重复Setup。若首次配置失败，用Start继续已有凭据，不重新生成。Grafana viewer为独立的公开临时验收账号，与ForgeOJ管理角色无关系；管理员凭据随机且不交付。

长回放可能超过访问JWT或限流窗口有效期。Redis暂停前执行PrepareOutage：先等原实测窗口自然到期（以healthy记录时间加310秒留余量），避免新用户名仍共享旧IP预算；再通过正常HTTP重新登录原账号，以新测试用户名实测双API共享10次预算，观察故障期间各实例保守预算。不重建管理员、不清零计数。最终Warm在长Rabbit演练后正常登录当前普通/降级账号，旧撤销凭据仍保留。新增阶段分别追加到实际HTTP事实，不能冒充旧会话一直有效。

最终Audit在本任务内部Rabbit管理接口使用有界`ack_requeue_true`诊断读取三个死信队列（每队列最多100份、每份1024字节），消息全部重新入队。它会改变临时投递/重入队标记，并非纯GET；不删除消息或修改MySQL业务事实。输出仅保留已经严格验证的原事件ID和四字段标识契约，不含源码/凭据。审计要求每个已发布原事件至少有一份匹配副本、所有副本均能匹配原事件、诊断前后ready/unacked一致，执行/搜索队列为空。唯一事件数与消息副本数分开记录，不能把至少一次语义写成恰好一次。协议来源为[RabbitMQ HTTP API](https://www.rabbitmq.com/docs/http-api-reference#post-apiqueuesvhostnameget)。

清理前运行`node tools/validation/Verify-M4MonitoringEvidence.mjs <准确构建目录> <准确回放目录> --preflight`。它检查全部现场证据而不生成VERIFIED文件；通过后再Stop、CleanupSnapshot、索引删除Verify，并不带`--preflight`执行最终审计。先预检后清理，保留所有未通过记录。

Rabbit演练顺序为PauseRabbit→Business/rabbit-accept→RabbitPending→Probe/rabbit-outage→PauseWorker→ResumeRabbit→Business/rabbit-rearm→ResumeWorker→Business/rabbit-recover→Probe/rabbit-recovered。原运维列表只显示异常，accept会等待原有限发布预算耗尽，不扩大列表权限；accept检查点保留原幂等键，脚本中断也不重复创建任务。Worker只在broker故障已观测后暂停，并保持暂停到同一原事件真实发布确认。这样避免确认超时留下的缓冲消息先执行、使终态任务正确拒绝人工恢复的竞态；仍由既有OPS接口重新检查资格/CAS/审计，失败次数保留，最终由真实Worker执行及ACK。暂停/恢复均检查确切本次容器所有权。

面板的全局数据库库存取max，采集失败/45秒陈旧时隐藏旧库存，另外显示采集成功与新鲜标志。请求分位数为固定桶估计；进程counter重启归零；Rabbit连通性检测有心跳延迟。面板不是容量/SLA/安全隔离结论，不声称P95性能提升。完整验收状态见[验证记录](../../docs/M4-MONITORING-VALIDATION.md)。M5部署、生产TLS/网络与持久化未纳入本单元。

Prometheus每15秒抓取，页面使用Grafana支持的30秒刷新。无事件/没有成功采集时可显示No data；不能以填0掩盖缺失数据。重试数和水位差在SQL中用CASE先判断再减无符号列，不能用GREATEST保护已经发生的下溢。
