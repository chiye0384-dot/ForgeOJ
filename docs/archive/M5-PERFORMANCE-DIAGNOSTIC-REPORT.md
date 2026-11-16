# M5 SQL/API 只读热点诊断 DIAG-20261028-01

本机隔离诊断有限单元 **VERIFIED**；完整性能计划/M5 **IN_PROGRESS**，E-06/E-07、Release、RESUME_READY 不提升。没有实施业务、索引、连接参数或资源优化，没有优化比例结论。原始无探针基线及其归档保持不变。

## 结果与下一步

列表路径每次有 COUNT、分页、标签三类业务 SELECT，并有事务与连接状态交互。请求线程样本较多落在 JDBC/MyBatis，其中出现 Connector/J 的 QueryInfo 构造和字符串扫描。详情缓存命中仍读取 MySQL 的 cache_epoch，并完成 Redis 缓存完整性校验、JSON 解码及 HTTP 响应编码。它们是有证据的成本位置，尚不能证明唯一瓶颈或预计收益。

镜像内实际 Connector/J 9.7 的 Driver.getPropertyInfo（未建立连接）返回 cachePrepStmts=false、prepStmtCacheSize=25、prepStmtCacheSqlLimit=256、useServerPrepStmts=false；实际 JDBC URL 没有相关覆盖，源码也没有显式设置。下一有限单元建议在新隔离项目做**仅连接级语句/查询元数据缓存**的受控 A/B，先充分预热、固定资源/数据、交错重复并检查内容与权限不变，再决定是否采用。当前没有打开缓存，也不承诺改善。保留重复读事务、代数/完整性/身份校验，不为性能降低边界。

## 实验条件

2026-10-28，拥有者项目 forgeoj-m5check-147876a386fa，原始目录 target/forgeoj-deployment-20261028-155345-36f0e4a8/diagnostics。Docker Desktop/WSL2 Linux 内核 6.18.33.2-microsoft-standard-WSL2；API/MySQL 各 2 CPU，cpu.max=200000 100000。其他限制沿用原基线，仅在 ignored 测试包设置。

同一固定 API/Worker JAR，428 个编译输入摘要匹配；API SHA256 f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e，Worker ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f。Git HEAD 058f36c771f15735a1e987a21c55074b80f511a9，工具仍在未提交工作树，没有提交/推送。

数据集 SHA256 09d4eaa246747eb77d0525b7aecdebba7db045f44b5ff5eb6592bccd8d0a682b：1 个合成用户、301 公共题、301 判题版本、903 用例、零提交，无班级/作业。短/中/长合成题面各 100。Node 固定原客户端和镜像，无新增依赖。

四阶段各 20 秒，闭环并发 4/8，无重试，同桥 HTTPS 验证证书，列表预热 30 次，详情预热 300 次。共 **94,862 次测量请求**，状态/网络/超时/内容错误均 0；原始 NDJSON 独立重算，与服务端 HTTP 计数（含预热）完全一致。没有新建判题提交，本单元不产生新的判题正确性证明。

存在 JFR、元数据采集、首次 JVM/JIT/GC 及运行顺序影响；本轮结果不能与原无探针基线直接计算优化比例。共享开发机和小数据量不代表云主机容量。

| 阶段 | 测量请求 | req/s | P95 ms | P99 ms |
|---|---:|---:|---:|---:|
| list-c4 | 10313 | 515.57 | 28.967 | 57.493 |
| detail-c4 | 34818 | 1740.74 | 3.717 | 6.593 |
| list-c8 | 15914 | 795.34 | 52.573 | 56.267 |
| detail-c8 | 33817 | 1690.62 | 9.497 | 45.633 |

## SQL 执行计划与摘要边界

使用 API 原有角色执行 EXPLAIN JSON/ANALYZE 和 SHOW INDEX，未改授权/索引/统计设置。COUNT 扫描 301 行、筛出 100，已有 problem_tag 与 judge_version 主键查找各循环 100 次，空闲观察总耗时约 0.473 ms。首分页主键扫描 59 行返回 20，总耗时约 0.101 ms；OFFSET 80 末页扫描 299 行产生 100 个候选后返回 20，总耗时约 0.365 ms。标签使用覆盖主键范围扫描，返回 40 行，约 0.039 ms。cache_epoch 是常量主键行访问。单次空闲计划耗时不等于并发端点尾延迟；301 行的小表不能仅凭扫描就判定缺索引或证明未来大表表现。

维护 root 仅只读读取 Performance Schema 的归一化 digest 元数据；未重置/启用仪器，也未导出 SQL_HISTORY。列表另观察到 SET autocommit、read only/read write、COMMIT 等约五类事务控制交互和 SELECT @@SESSION.transaction_isolation 状态探测。保留原重复读语义。

**SQL 摘要计数没有完全对齐。** 含预热的列表 C4 期望 10343 次，COUNT/page/tags 实际 10068/10320/10333；C8 期望 15944，实际 15839/15932/15933。详情 epoch C4 期望 35118、实际 35077；C8 期望 34117、实际 34098。原因尚未确认，不能用这些差值精确声称每请求 SQL 次数，也不能把差异当作业务请求丢失；客户端/HTTP 计数一致，二者分别记录。

补充只读计数校准中，串行 1000 次 SELECT 返回/摘要增量均 1000，四路并发共 2000 次均 2000；Digest_lost=0、10000 槽位使用 564。该小探针没有复现原差异，也没有解释差异，不计入 HTTP 负载。max_digest_length 和 performance_schema_max_digest_length 都为 1024，长详情摘要被截断，分类使用实际保留下来的投影字段，完整 SQL 仍由源码界定。SQL 计时是 elapsed time，含预热/少量背景，不能当纯 CPU 时间。

## Java 样本、GC 与配额

下表仅取测量 UTC 时间窗内的 http-nio 请求线程 Java 样本。JDBC、JSON、日志等采用包含栈分类，类别可能重叠。32 帧内不少栈看不到项目帧；采样计数不等于 CPU 百分比，不给每个函数分配精确开销。原生 accept/epoll/socket 样本可能是等待，不能算热点 CPU。

| 阶段 | 请求 Java 样本 | 32 帧内无项目帧 | 包含 JDBC/MyBatis | 包含 JSON | GC 暂停重叠总 ms | 最大暂停重叠 ms |
|---|---:|---:|---:|---:|---:|---:|
| list-c4 | 475 | 298 | 260 | 23 | 422.489 | 127.069 |
| detail-c4 | 447 | 290 | 72 | 57 | 298.952 | 3.981 |
| list-c8 | 522 | 295 | 305 | 20 | 427.705 | 5.011 |
| detail-c8 | 581 | 351 | 129 | 78 | 517.656 | 107.066 |

列表的项目帧主要是 LibraryService.list；详情可见 PublicCache.read/decode 与控制器/过滤器。长 GC 暂停实际存在：list-c4 最大约 127 ms、detail-c8 最大约 107 ms，不能排除其对本轮尾延迟的贡献，也不能据此证明此前无探针基线有相同 GC 行为。未采集 heap dump。

| 阶段 | API 节流周期/总周期 | MySQL 节流周期/总周期 |
|---|---:|---:|
| list-c4 | 204/226 | 3/231 |
| detail-c4 | 76/233 | 0/234 |
| list-c8 | 3/224 | 198/224 |
| detail-c8 | 203/227 | 0/222 |

此差值区间还包含预热/attach/控制开销；throttled_usec 是累计统计，不能直接除以墙钟推导请求被阻塞百分比。列表 C4 与 C8 受到不同进程的配额约束，首次 JIT/探针/GC 有混杂因素；没有单一因果或扩容收益结论。

## 证据、失败与安全收尾

原始 api.jfr、8 帧紧凑 JSON、32 帧文本栈、逐请求 NDJSON、归一化 SQL 前后值、cgroup/HTTP 指标、执行计划和独立归纳结果均保留。最小 JFC 只有执行/原生样本、CPU、GC，加 JFR 固有 Checkpoint/Metadata；环境变量、系统属性、异常、线程 dump、对象内容等七类敏感事件计数全部为 0。导出逐文件扫描本轮实际随机秘密、完整 PEM/JWT；私密配置、证书/密钥、cookie/token、数据库 dump、上游二进制不打包。仅保留本轮无秘密的 JFR 观测文件。

工具失败如实保留：Checkpoint 拼写校验错误、docker cp 看不到容器 tmpfs、32 帧完整 JSON 超过 96 MiB 导出上限；最终改成准确元事件名、exec 二进制流加摘要核对、紧凑 JSON 加文本栈。完成的负载没有重复运行，没有清库/重置统计。前两阶段旧完整 events.json 原件只在原始目录保留，不进入 ZIP。离线归纳器先误设摘要计数必须一致、后修正长摘要分类；失败脚本随证据保留，修正只重算离线数据。

数据/授权/Flyway 前后摘要不变，428 输入匹配。API 无残留 JFR recording，精确清理本轮容器/卷/网络与三镜像别名，负载容器移除；旧 LAN 八容器退出保留，无关 redis 保持运行。没有启动/停止桌面客户端，没有 Git 提交/推送或公网发布。

安全交付收据见 [verification.json](evidence/m5-hotspot-diagnostic/20261028/verification.json)，机器归纳见 [summary.json](evidence/m5-hotspot-diagnostic/20261028/summary.json)。ZIP 路径、SHA256、逐文件清单和 CRC 在收据中；它是诊断证据，不是新的生产部署版本。原部署/备份/回滚/性能基线 ZIP 均保留。

方法与范围见 [诊断设计](M5-PERFORMANCE-DIAGNOSTIC-DESIGN.md)、[原基线](M5-PERFORMANCE-BASELINE-REPORT.md)。真实 Linux/公网 HTTPS/SMTP、每日异地备份与密钥保管、跨业务版本回滚、长稳/开放到达负载、真实试用仍独立待验收。
