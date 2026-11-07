# ForgeOJ 本机隔离性能基线工具

只用于本仓库新建空库、可信 `validation-state.json` 与配套 `instance.env` 的拥有者项目；不能传入真实服务器或旧 LAN 演示目录。不会修改生产源码、schema/grants、限流、宿主设置或云资源。测试只访问固定 `web` / `oj.test` 的 Nginx HTTPS。

按 [设计](../../docs/M5-PERFORMANCE-BASELINE-DESIGN.md) 先生成独立包（固定原 API/Worker JAR 摘要），然后：

```text
python tools/performance/prepare-baseline.py target/新的拥有者部署目录/package
powershell -File tools/deployment/Verify-ProductionPackage.ps1 -PackageDirectory target/新的拥有者部署目录/package
python tools/performance/run-baseline.py target/新的拥有者部署目录 docker完整路径
python tools/performance/summarize-baseline.py target/新的拥有者部署目录
```

Windows Docker/Python/Node 不在 PATH 时，向部署验证器传 `-DockerCommand` / `-PythonCommand` / `-NodeCommand`，不要猜路径。需要 Docker daemon 的命令必须使用本机允许的维护访问方式。

`prepare-baseline.py` 仅在私密目录尚不存在时对这份 ignored 包增加固定 CPU 上限并更新清单。部署验证器负责新的随机项目、私密文件、空库迁移和 HTTPS 校验；`run-baseline.py` 仅接受该验证器生成的新项目状态，显式安装原创合成 fixture，不伪造判题结果。负载容器没有 Docker socket，只挂客户端、公开测试证书/数据描述和本阶段输出目录。

`--resume-read-only` 只用于工具测量失败后，在**同一可信测试项目仍有恰好一用户、301题、零提交且所有合成题内容匹配**时创建新的 `suite-*`，不重灌/清库。必须先核验并移除已退出的准确旧负载容器，保留所有旧文件；它不是对失败判题或生产库的通用重试开关。归纳新代际时给 reducer 第二个参数：

```text
python tools/performance/summarize-baseline.py target/拥有者部署目录 target/拥有者部署目录/performance/suite-时间戳
```

采样/请求数据在 `performance/cases` 或显式新代际下。归纳器独立重算全部分位数、请求数与吞吐，并比对服务器 HTTP 计数、缓存计数；不要只拿客户端打印的最快数字。首次请求计数封顶结果属于工具诊断，不是足时基线；最终测量与旧记录分别保留。

结束后核验本项目的 `com.forgeoj.perf.project` 负载容器已退出/移除，再调用仓库 `Stop-ProductionPackageCheck.ps1` 清理准确项目。禁止共享 prune、广泛进程停止或操作旧演示。先整理报告与安全原始数据；不要打包整个部署目录，里面有真实随机测试秘密。

本工具只覆盖小数据集、短时闭环读取及 11 次顺序判题。没有完整容量、长期稳定性、优化比例、独立 Linux 主机或生产试用结论。

## 只读热点诊断

见[诊断设计](../../docs/M5-PERFORMANCE-DIAGNOSTIC-DESIGN.md)和[实际报告](../../docs/M5-PERFORMANCE-DIAGNOSTIC-REPORT.md)。需要另建同配置空库测试包、通过部署验证并生成可信拥有者状态；不能针对真实库或已清理目录执行。

```text
python tools/performance/diagnose-hotspots.py target/新的拥有者部署目录 docker完整路径
python tools/performance/summarize-hotspots.py target/新的拥有者部署目录
```

采集只在 API 原 UID/PID 上启用最小 JFR，用 API 角色执行只读计划，用维护角色只读查询归一化 SQL 统计；不会改仪器、重置统计、改授权或连接参数。原始文件先保留，再按校验结果续接；`--resume-capture` 仅用于同一仍存活且业务事实/权限未变的测试实例，不重跑已完成负载，不是生产重试开关。

结束时先确认自己的负载容器和 recording 已停止，再用 `Stop-ProductionPackageCheck.ps1` 精确清理，最后运行 `build-diagnostic-evidence.py target/拥有者部署目录`。打包器只收白名单，核对全部 428 输入、JFR 事件与摘要、随机秘密、ZIP CRC/文件摘要。离线归纳失败只修工具并重算原始数据；SQL 摘要与 HTTP 计数分别呈现，不能强行补齐。本轮诊断没有优化实施。
