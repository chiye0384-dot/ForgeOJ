2026-10-30 当前：本地使用改进范围已 VERIFIED，固定入口 http://127.0.0.1:8088/problems。21题（20新题真实双验证/审核发布）、草稿保护、编辑器、批量标签和启动工具完成；API336/Worker139、Windows与Linux前端140通过，84条持久事实及数据挂载停启一致，重启后真实AC。当前入口为 docs/CURRENT-STATUS.md、docs/PRODUCT-IMPROVEMENTS-VALIDATION.md 及 docs/evidence/product-improvements/20261029/verification.json（docs内链接按相对位置解释）。本地单元已结束，不重复长验证或旧诊断下一步；完整M5/Release/RESUME_READY未提升，新源码上线需重打部署包。旧LAN数据/原包/失败记录/保护文件及桌面连续性规则保持；新演示保持运行供用户使用，不能按旧“精确清理”说明删除。变更未提交推送。

2026-10-28当前：SQL/API只读热点诊断有限单元 **VERIFIED**，见[报告](M5-PERFORMANCE-DIAGNOSTIC-REPORT.md)、[设计](M5-PERFORMANCE-DIAGNOSTIC-DESIGN.md)与[安全收据](evidence/m5-hotspot-diagnostic/20261028/verification.json)。四阶段94862次测量请求错误0、客户端/HTTP计数一致；原301题数据/权限与428编译输入不变，无新判题提交。列表三类业务SELECT及事务/状态交互、JDBC解析样本，详情缓存命中仍查epoch并解码JSON；实际Connector/J语句缓存默认关闭。SQL摘要计数差异未解释，长摘要截断、32帧缺项目帧、探针/JIT/GC/配额混杂明确保留，不能宣称唯一瓶颈、优化收益或云容量。

诊断证据Zip：target/forgeoj-hotspot-diagnostic-20261028-164533-ad5de7da/forgeoj-hotspot-diagnostic-20261028-164533-ad5de7da.zip，7795432字节/131文件，SHA256 814881967ca9662952b3f9aeac8c2cf713e407e7bf81d3832ad90ca5cebdecc2；白名单/实际随机秘密/敏感JFR事件/CRC/逐文件摘要通过。拥有者forgeoj-m5check-147876a386fa容器/卷/网络/三镜像别名和负载容器已精确清理；旧LAN八容器退出保留、无关redis运行。原部署/备份/回滚/基线包均保留，HEAD058f36c771f15735a1e987a21c55074b80f511a9，未提交推送。没有实施优化；下一有限单元建议新隔离项目做仅连接级语句/查询元数据缓存受控A/B，先核整组额度，充分预热/交错重复、固定资源/数据与正确性，收益未证实前不改生产。完整性能计划/M5仍IN_PROGRESS，E-06/E-07/Release/RESUME_READY不提升；真实Linux/域名CA/SMTP、每日异地/密钥保管、跨业务版本回滚、长稳/开放负载/真实试用仍缺。不购买/公网发布/发邀请/PR/main/tag/Release；既有继续授权生效，下方旧下一步为历史，保护文件及桌面连续性规则保持。

2026-10-28当前：本机隔离性能有限基线 **VERIFIED**，见[完整报告](M5-PERFORMANCE-BASELINE-REPORT.md)、[设计](M5-PERFORMANCE-BASELINE-DESIGN.md)与[安全证据](evidence/m5-performance/20261028/verification.json)。最终277633次测量请求状态/网络/超时/内容错误0，13个读阶段由原始NDJSON独立重算并匹配服务端计数；11次真实判题、唯一task/attempt/Outbox，九队列空、无丢失/重复副作用/沙箱残留。并发8吞吐下降、尾延迟升高，API/列表MySQL接近各自2CPU配额，保留两轮波动与首轮工具封顶失败；没有优化比例或云容量结论。完整性能计划/M5保持IN_PROGRESS，E-06/E-07/Release/RESUME_READY不提升。

接受环境：target/forgeoj-deployment-20261028-091950-6983a042/performance/suite-20261028-093237，项目forgeoj-m5check-dd854834aae9。证据Zip：target/forgeoj-performance-baseline-20261028-115205-376f94b3/forgeoj-performance-baseline-20261028-115205-376f94b3.zip，5608297字节/282文件，SHA256 bf9aa9dcb840a4001954ff4b20fe19d14be1a984c7b513a047bd25324df49bc0，扫描/CRC/逐文件摘要通过。CPU限制只在测试包；当前业务428输入仍匹配原Linux构件，HEAD058f36c771f15735a1e987a21c55074b80f511a9，部署/性能工作树未提交推送。准确项目容器/卷/网络/三镜像别名、所有负载容器和沙箱实时0，旧LAN八容器退出保留、无关redis运行；部署/备份/回滚原Zip摘要未变。下一有限单元建议只读SQL EXPLAIN/API热点与配额节流诊断，先核额度、冻结环境/脚本并保留原基线；不得调整条件后宣称优化。真实Linux/域名CA/SMTP、每日异地、跨业务版本回滚、长稳/开放负载/真实试用仍缺，不购买/公网发布/发邀请/PR/main/tag/Release。既有继续授权生效，下方旧日期下一步为历史；保护文件与桌面连续性规则保持。

2026-10-27当前：同文件系统、仅ENTRYPOINT变化的镜像配置故障/回退有限单元 **VERIFIED**，见[回滚验收](M5-IMAGE-ROLLBACK-VALIDATION.md)及[手册](M5-IMAGE-ROLLBACK.md)。两个不同镜像ID的真实启动故障、失败保持停止、新计划回到原镜像、11类拒绝、71表结构/数据/授权匹配、五条新旧真实AC与源码保留、回退后HTTPS判题通过。JAR与文件层相同，跨业务版本/跨schema回滚仍未验证；完整M5 IN_PROGRESS，E-06/E-07/Release/RESUME_READY不提升。

当前独立工具：target/forgeoj-rollback-tools-20261027-201347-7a617b78/forgeoj-rollback-tools-20261027-201347-7a617b78.zip（20174字节，SHA256 4c518731247ce4221f5bcdc9eb01c838726e30d1bad7e89f26f30a3ef4527890）。安全证据docs/evidence/m5-image-rollback/20261027/verification.json。接受项目forgeoj-m5check-3c7199fdf1f7及两个夹具别名已精确清理；旧LAN八容器退出保留、无关redis不操作。早先夹具构建/长停顿超时记录保留，不能当接受结果；本次接受rollback-attempt-20261027-200636。原部署Zip、备份工具Zip未覆盖，HEAD仍058f36c771f15735a1e987a21c55074b80f511a9，工作树未提交推送。下一有限单元建议固定环境性能设计/基线测试，先核额度与实际环境；真实Linux/域名CA/SMTP、每日异地备份、跨业务版本兼容与真实试用仍缺，不购买/公网发布/发邀请/PR/main/tag/Release。继续授权已生效，下方旧日期下一步仅历史；保护文件与桌面连续性规则保持。

2026-10-27：手动维护窗加密备份与独立空库恢复有限单元 **VERIFIED**；实际演练为2026-10-24，本日完成独立工具包与清理核验。71表数据/权限匹配、8类拒绝、Flyway校验及Linux引擎UID1000加解密通过。见[M5备份验收](M5-BACKUP-VALIDATION.md)与[操作手册](M5-BACKUP-RESTORE.md)。完整M5保持IN_PROGRESS；每日调度、异地副本、真实Linux主机、生产密钥保管/消息协调、回滚、性能和真实试用未验收。E-06/E-07/Release/RESUME_READY不提升。

当前交付：target/forgeoj-backup-tools-20261027-093540-fbb721fd 内独立工具Zip，SHA256 69ea58f59a0233982af2cfa3b0f40abe6424eb18adeed54a992553e3d90f56b3；第二版完整部署Zip保持原样。四个独立测试项目容器/卷/网络全0；旧LAN八容器已退出且保留，无关redis运行。HEAD 058f36c771f15735a1e987a21c55074b80f511a9、feat/m2-accounts，工作树未提交推送。下一有限单元建议同schema兼容镜像回滚设计与独立验证，先核额度和真实状态；不得覆盖源库或把新库恢复当切流，不购买/公网发布/发邀请/PR/main/tag/Release。下方旧日期下一步为历史；现有继续授权已生效，不重复等待同一个继续。保护文件和桌面连续性规则保持。

2026-10-24当前接续：用户继续后完成第二版独立包的有限加固，详见docs/M5-DEPLOYMENT-HARDENING-VALIDATION.md。五服务真实就绪、引擎POSIX权限四负例/三UID授权、数据库中断/恢复、监控容器重建后历史样本与面板保留、15项HTTPS及最小权限检查已VERIFIED；完整M5仍IN_PROGRESS。新证据为docs/evidence/m5-deployment/forgeoj-deployment-20261024-202551-c51834ae/verification.json，首版证据未覆盖。准确独立测试资源已清理，无关redis保持运行；旧LAN演示8容器当前已退出，保留未启停，不能把旧可用网址当当前运行。部署/文档工作树未提交推送。下一单元可做独立加密备份/恢复工具，真实主机/域名CA/SMTP、服务器外备份、兼容回滚、性能/真实试用仍未验收，不买云/公网发布/发邀请/PR/main/tag/Release。保护文件与桌面连续性规则保持。

2026-10-11 M5当前接续：用户已明确“继续先做设计和独立部署包”。设计与首版包IMPLEMENTED，隔离空库/固定镜像/15项HTTPS/最小权限/私有监控检查VERIFIED；完整M5 IN_PROGRESS。当前入口为docs/M5-DEPLOYMENT-DESIGN.md、docs/M5-DEPLOYMENT-VALIDATION.md与docs/evidence/m5-deployment/verification.json。真实Linux/域名CA/SMTP、异地恢复、兼容镜像回滚、性能及真实试用未验收；不购买/公网发布/发邀请/PR/main/tag/Release。现有分支保留未提交的部署准备与LAN演示文件。手机演示项目forgeoj-e2e-20261011-145312-83d835a3供用户使用，不能按历史“无运行项目”停止它。下方只读第一轮等待“继续”和M5 PLANNED为此前交接历史，本次授权已取代。保护文件/桌面连续性/额度规则保持。

# ForgeOJ新对话启动指令：M4完成后的M5接续准备

交接日期：2026-10-11，Asia/Shanghai。仓库：`D:/Java项目/ForgeOJ`。本文件是当前入口，取代旧M1/M2/M3/M4交接中的“下一步”。完整事实、规则、工具与验收标准见[M5详细交接](M5-NEXT-CHAT-HANDOFF.md)。

## 新对话必须执行的第一轮

用户本次要求整理交接并创建新对话。**第一轮只读接手、核事实并提出M5建议，等待用户明确说“继续”后才改业务/部署代码或启动长验证。** 不重新做已完成M4，不把创建对话当作购买云资源、公网发布或创建Release的授权。

1. 显式进入`D:/Java项目/ForgeOJ`；桌面保存项目“简历Java项目”根是父目录`D:/Java项目`，它不是ForgeOJ Git根。在ForgeOJ目录执行Git/Maven。使用`forgeoj-development`技能及delivery-workflow。
2. 依次读本文件、仓库`AGENTS.md`顶部、[详细交接](M5-NEXT-CHAT-HANDOFF.md)、[M4总审计](M4-GATE-AUDIT.md)、[监控验收](M4-MONITORING-VALIDATION.md)最终部分和[机器证据](evidence/m4-monitoring/verification.json)。
3. 读[Roadmap](ForgeOJ-Roadmap.md)第9/10节、[Requirements](ForgeOJ-Requirements.md)14.4/14.5/15、[Decision Log](ForgeOJ-Decision-Log.md)D-045～D-048、[限制](KNOWN_LIMITATIONS.md)、[上游许可](UPSTREAM-AND-LICENSE.md)、[性能计划](Performance-Test-Plan.md)及[简历证据矩阵](Resume-Evidence-Matrix.md)E-06/E-07。
4. 只读核对实际分支、HEAD、工作树、保护文件SHA、额度与Docker。完成M4实现提交`30b7f53619f9c1aaf930ea1df64f82f5d42271be`已推送`feat/m2-accounts`；本交接另有文档提交，核实际HEAD，不将正常文档提交当业务漂移。
5. 当前M-1～M4全部VERIFIED；M5/V1.1 PLANNED；没有PR/main合并/tag/Release/RESUME_READY。M4七步及5/5总门禁已完成。第一轮不启动全量测试、演练容器、云资源、真实邮件或公网服务。
6. 向用户报告已读文档、实际Git/环境、M5已有基础和缺口、建议首个有限单元、真正需要用户提供的主机/域名/备份/试用信息。仓库能查的事实自己查；不让用户替你选普通工程细节。
7. 用户明确“继续”后，先形成可审阅M5设计和分组计划，再在已授权范围实际推进。没有主机信息时可做独立设计/部署包和隔离验证；不得假装主机/公网/异地备份/真实试用门禁通过。费用、新服务/许可、真实持久数据和公网发布须依据实际授权处理，先完成可审阅成果，再请求必要的最终批准。

## 不可越过的边界

- `docs/M2-NEXT-CHAT-HANDOFF.md`、`.smtp.qq.local`禁止读/打印/复制/编辑/暂存/提交/覆盖/删除；只允许SHA核对，摘要在详细交接。
- 不关闭、退出、重启、终止或更新Codex/ChatGPT；不关闭内置自动化标签页。旧tab5曾在about:blank/deliverable，跨对话先查真实标签页，不盲用旧ID，不复现关闭缺陷。
- MySQL为业务/权限事实源，API无Docker权限、Worker保持最小授权；不能改判定/源码、清死信、重置恢复预算或关闭认证来通过部署。
- 每组昂贵验证前核额度；用户明确“如果额度不够做下一件事就及时停下来”。不足则写诚实检查点、精确清理，不弱化验收后标VERIFIED。
- 旧验收容器和数据库已清理，旧URL失效；`target`构件/日志不是运行服务，不能复用旧私有cookie。

旧`M4-STEP4-NEW-CHAT-START.md`、`M4-NEXT-CHAT-HANDOFF.md`、M3及更早入口均为历史。本轮只读接手完成后，等待用户“继续”。
