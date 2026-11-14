2026-11-14：本文件主体为此前失败恢复与额度停点，已由[本轮验收](PORTFOLIO-READINESS-VALIDATION.md)取代。当前本地完整回归与判题基线VERIFIED；Git交付以仓库PR/预览标签为准，不按下方旧下一步重跑。

# 项目展示整理检查点

2026-11-14，整体 **IN_PROGRESS，尚未交付 main/tag**。先读本文件，再读 [计划](PORTFOLIO-READINESS-PLAN.md)、根目录 [DESIGN](../DESIGN.md)、[PERFORMANCE](../PERFORMANCE.md) 和 [开发约定](../CONTRIBUTING.md)。本文件取代旧记录中的“下一步购买服务器”；用户要求先完成前四项，之后再讨论服务器。

## 已写入工作树

- Spotless 3.0.0、google-java-format 1.28.0，Maven validate 与 CI 格式门禁；API/Worker 生产和测试 Java 统一格式。
- 190 处长注解 SQL 改为多行文本块；整理重复完整类名引用。SQL token/占位符对比收据在 `target/portfolio-readiness-20261113/sql-and-import-reflow.json`。不要再次运行该目录的一次性重写脚本。
- DESIGN 的架构、内部任务状态机及故障表；README 设计/性能/开发入口，说明 ES 使用 JDK HTTP REST 接入。
- PERFORMANCE 归纳历史读接口结果及适用范围，L-022 纠正过时表述。新的单 Worker/容器分段测量只有脚本，**尚未执行，不存在新性能结果**。
- `tools/performance/` 新增 judge-load、CLI 计时包装器、采集/归纳、包生成/清理工具；`tools/validation/Collect-PortfolioEvidence.py` 预备证据汇总。仅做过语法检查，完整流程尚未验证，不能称为可接受性能证据。

## 本轮验证及失败边界

1. 宿主 Spotless apply/check、API 测试编译通过；故意破坏一个 Java 缩进后格式检查真实拒绝，恢复原字节后通过。日志为 `target/portfolio-readiness-20261113/format-negative.log`、`format-positive.log`、`test-compile.log`、`spotless-fixture-recovery.log`。
2. 固定 Linux 第一次 `target/forgeoj-linux-20261113-214658-df153d22`：依赖下载 TLS 失败，无测试结论。
3. 第二次 `target/forgeoj-linux-20261113-215045-11e92048`：API 340 项/59 suites，1 failure；重建管理测试只调用一次扫描便断言成功，实际任务仍 RUNNING。日志 `fixed-linux-second.log`。
4. 第三次 `target/forgeoj-linux-20261114-100013-3ae8c338`：API **341 项/59 suites，340 通过、0 failure、1 error、0 skipped**。唯一 error 为 `readyIntentRecoversTheSameJobAfterLastAttemptAliasSwitchCrash` 的 `SearchUnavailable`。日志 `fixed-linux-accepted.log` 名称含 accepted，但**该轮失败、构件不可接受**；独立统计 `third-full-api-summary.json`。Worker 与前端被 Maven 失败阻断，未运行。
5. 测试修正只在 `ElasticSearchIntegrationTests.java`：正常重建等待有限恢复；新增一次 refresh 失败后同一作业恢复且仅一条终态审计的回归；测试索引准备在有限等待中检查已有索引归属，重复相同索引/版本写入并核对别名。未改生产客户端超时、重试次数、权限或状态规则，保留真实故障断言。第三次 error 未保留具体 REST 调用位置，不能断言唯一根因已证实。
6. 最新专项目录 `target/forgeoj-linux-20261114-102736-f13b6d11`，日志 `target/portfolio-readiness-20261113/fixed-linux-es-focused.log`。最终结果须看下面的专项收据，不能用第三次失败覆盖、拼装成一轮全通过。

## 下一步与接受条件

先核额度、Git、实际拥有者容器和专项结果。已经授权前四项，不再要求用户重复确认；没有服务器/公网/邮件/购买授权。

1. 专项未通过时先诊断该组，不盲目重复完整 API。专项通过后，再安排一整组固定 Linux All，含 API/Worker、前端格式/lint/类型/159 测试/构建；源码冻结期间不改编译输入。当前包生成器/证据汇总器要求完整同轮输出，不得拿过滤运行冒充完整回归。
2. 完整回归通过后按 [性能工具说明](../tools/performance/README.md) 新建独立部署包，prepare-baseline、Verify-ProductionPackage、55 次真实判题（5 预热/50 测量）、独立归纳、准确归属清理。先审阅未运行工具的路径、权限和失败保留行为，发生失败保留原目录、原键和原请求，不自动重放。
3. CLI 包装器开启时测到的耗时含测量开销；原始 NDJSON/DB/容器 ID/资源/摘要相互校验，只报告该同类题短时本机负载，不推断云容量。汇总与清理后才能更新 PERFORMANCE 数字和 VERIFIED 收据。
4. 审阅本轮与原有缓存 A/B、部署门禁修改，显式暂存无秘密文件，提交功能分支并推送；PR 合入 main，等待保护要求的 backend/frontend 及新增格式检查。不要移除保护或强推。README 分支说明在实际交付时更新。最后核对 main 与版本再打 `v0.1.0-preview.1`（若届时未占用）；不创建正式 GitHub Release，不声称完整 M5 生产验收完成。

## 保留与安全边界

- Git 起点：`feat/m2-accounts`，`6bb1c290ebe73fbb7407f091082a517f601d7712`。初核 main 为 `919935244fb67e888d2048cd03ac339a8dc7d6b4`，落后 42 个提交；main 受保护。恢复时重新核远端，不把旧值当当前。
- 本轮没有 commit/push/PR/merge/tag。原有未提交改动备份为 `target/portfolio-readiness-20261113/preexisting-tracked.patch`；不要覆盖历史证据、reset、clean 或 `git add .`。
- 原演示 `forgeoj-e2e-20261029-201735-604f52a7` 及单独学习 Redis 继续停止，原容器和卷保留；未启动/迁移原网站。Docker 数据盘和恢复备份不是垃圾。
- `docs/M2-NEXT-CHAT-HANDOFF.md`、`.smtp.qq.local` 只用 Get-FileHash，禁止读取/复制/输出/暂存。摘要仍分别为 `e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054`、`6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907`。
- 不关闭 Codex/ChatGPT/内置浏览器标签，不重启 Docker Desktop、WSL 或共享服务，不按进程名广泛终止。
- 工具：Docker `C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe`，JDK `D:/JDK21`，Maven `D:/Maven/apache-maven-3.9.14/bin/mvn.cmd`，仓库缓存 `D:/Maven/apache-maven-3.9.14/mvn_repo`，Node `D:/Node.js/node.exe`。

最新额度五小时 88%/周 45% 已用。按 AGENTS 的“额度不足下一组就停止”，当前余量不足以覆盖全量回归、测量、合理失败处理、证据和 PR/CI 整组，因此先收齐专项并保存检查点；不消耗免费 reset，不开启新压测或发布组。

## 专项最终收据

2026-11-14 10:31：最新固定 Linux 搜索专项 **20/20、1 suite、零失败/错误/跳过，BUILD SUCCESS**。本轮测试框架已自动清理。相对第三次全量，编译输入只改变了 ES 测试文件，生产输入相同；这是一份分开呈现的专项结果，不将旧全量失败改标通过。机器收据 `target/portfolio-readiness-20261113/es-focused-receipt.json`。下一步仍为额度足够时完整同轮回归，再进行尚未运行的判题测量。

## 18:33 接续

2026-11-14 18:33 接续：当前额度五小时7%/周48%已用，可以继续已授权四项。固定 Linux All 正在 target/forgeoj-linux-20261114-183240-6cbb9cfe 验证，日志 target/portfolio-readiness-20261113/fixed-linux-final-all.log；未通过前不打性能包或交付 main/tag。原保护文件 SHA 未变。Docker工具启动后自动拉起的7个历史验证容器已核标签与旧记录，仅精确停止并保留；原演示与学习Redis仍停止。服务器购买/公网/真实邮件/正式Release继续延后。

估算整组包含完整回归约30～40分钟、隔离包与有限测量约15～25分钟、失败诊断余量及证据/PR/CI交付；当前额度可推进，扩大失败重试前再次核验。Docker引擎实际29.9.2，本轮性能报告记录实际环境，不沿用旧版号。历史容器自动拉起处置收据 `target/portfolio-readiness-20261113/resume-historical-autostart.json`。
