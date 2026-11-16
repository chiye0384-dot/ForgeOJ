# M5 SQL/API 只读热点诊断设计

2026-10-28 有限执行与证据核对 **VERIFIED**，见[实际报告](M5-PERFORMANCE-DIAGNOSTIC-REPORT.md)。SQL 摘要计数差异、采样深度及 GC/探针影响保留为限制；没有实施优化，完整计划/M5保持 IN_PROGRESS。

2026-10-28，接续 [原始基线](M5-PERFORMANCE-BASELINE-REPORT.md)，本单元只定位问题，不实施优化、加索引、扩容或修改生产代码/授权/限流。原 277633 次基线及其失败记录保持原样。完整性能计划/M5/E-07 不因诊断完成而通过。

## 隔离环境与范围

新建空库拥有者项目，复用原固定 API/Worker JAR、Node 客户端和同一合成数据集：1 用户、301 公共题、903 个用例、零提交。与基线相同的 CPU/内存约束，API/MySQL 各 2 CPU；不操作旧 LAN 演示或其他服务。只跑筛选列表和预热详情的并发 4/8，各一个 20 秒阶段。诊断额外存在 JFR/元数据采样开销，不能把本轮吞吐当作无探针基线或优化后结果。

## 观测计划

1. 用真实 API 账号对 Mapper 中的 COUNT、分页（首/末页）、标签批量查询和 cache_epoch 读取做 `EXPLAIN FORMAT=JSON` 与 `EXPLAIN ANALYZE`。后者执行 SELECT，仍只对本轮合成库读取；不运行 ANALYZE TABLE、建/删索引或改 optimizer 设置。
2. 用独立维护 root 身份**只读**查询 Performance Schema 的消费者/仪器、按 digest 的语句统计。root 不承担业务查询，不给 API 加权限，不重置/启用统计表；如果原统计不可用则如实记录缺口。统计只导出归一化 digest 文本和计数/耗时/行数，不导出原 SQL_HISTORY 或带用户参数的语句。耗时是 statement elapsed time，不能等同纯 CPU；前后差值含预热及少量背景操作。
3. 复用镜像内 JDK21 的 jcmd/JFR，API 容器按原 UID 附加自己的 PID 1。使用自有最小 JFC，仅执行/原生栈采样、线程/进程 CPU 和 GC 暂停事件；显式关闭环境变量、系统属性、异常、线程 dump 与对象内容相关采样。记录最长 60 秒、最大 16 MiB，不取 heap dump。每阶段结束停止并取回记录，解析时只取测量时间窗内的样本。
4. 按阶段保存 API/MySQL 的 cgroup cpu.stat 前后差值以及固定间隔的样本，与请求尾延迟、SQL 计数和 Java 栈关联。节流计数可能含预热/attach 开销，不将其冒充某条请求的精确因果证据。

JFR 样本占比只是采样分布，不是精确 CPU 百分比；原生栈可能包含 I/O 等待。GC、JSON、JDBC、日志等分类必须展示实际样本，不能先假定某个模块有问题。详情缓存完整性校验、MySQL 版本事实和身份复核均保持，不为性能删掉安全边界。

## 交付和边界

输出实际执行计划、归一化 SQL 差值、受限 JFR、独立归纳结果和可审阅诊断报告。执行前后逐表数据/授权/Flyway 摘要不变，所有请求状态和内容通过，精确清理新项目和负载容器。任何采样/工具失败保留原件，不改业务数据重试。

JFR 文件在复制/导出前核对事件类型，敏感事件应为 0；归档只走白名单并检查本轮随机秘密是否混入。私密配置、cookie/token、SQL dump 与上游二进制不进入交付包。正式 Linux/云容量、长稳、跨业务版本回滚和真实试用仍独立验收。

方法依据：[JDK21 jcmd](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html)、[MySQL Performance Schema 事件计时](https://dev.mysql.com/doc/refman/8.4/en/performance-schema-events-statements-current-table.html)。实际支持的参数另外用镜像内帮助核对。
