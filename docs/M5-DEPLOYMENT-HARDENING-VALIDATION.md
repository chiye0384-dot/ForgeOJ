# M5 第二版部署包：就绪、权限与监控持久化

2026-10-24：本有限单元 **VERIFIED**；完整M5仍 **IN_PROGRESS**。用户“继续”授权延续独立部署准备，无购买、公网发布、真实邮件/邀请或Release。实际Git仍为 `feat/m2-accounts` / `058f36c`，部署与文档修改保留工作树，未提交推送。

## 包与构件

- 第二版目录：`target/forgeoj-deployment-20261024-202551-c51834ae/package`。
- Zip：同目录父级 `forgeoj-deployment-20261024-202551-c51834ae.zip`，65,337,841字节，52个归档文件。
- SHA256：`4824fa8ad1ed158a89cf6e8c9865071b5f5295994c33cef92ef090bfb74e241c`。
- [机器证据](evidence/m5-deployment/forgeoj-deployment-20261024-202551-c51834ae/verification.json)与[工具文件快照](evidence/m5-deployment/forgeoj-deployment-20261024-202551-c51834ae/tool-inputs.json)。镜像ID、输入JAR/源码摘要、环境范围分别记录，不冒充Release。
- 428业务/测试/前端输入仍严格匹配既有固定Linux接受构件，未改Java/Vue业务、schema、权限/配额/lease/fencing/Outbox/ACK或许可选择。复用明确输入JAR并重新实际构建三份应用镜像；没有重新声称运行API335/Worker139/前端129全量。
- 首版Zip保持原SHA `2c97f251cba59fef7e9213444d319e39e0c2acbe4d4c53a0fe34ead37faa497f`；首版证据未覆盖。新证据按运行目录分代保存，finalize要求生命周期PASS及准确清理回执后才能归档。

## 本轮改变

API就绪要求既有私有采样成功且新鲜，Worker还要求消费者活动；健康脚本将专用Bearer传给curl stdin，不放argv或Docker配置。Nginx从容器本机实际HTTPS代理session，验证域名与证书；Prometheus/Grafana有明确ready/health检查。启动等待实际健康，并使用flock防同instance并发启动；活跃API/Worker/Web存在时拒绝迁移。健康状态不会自动触发Docker重启，不宣称自动修复。

生产POSIX预检复用 `private-permissions.sh`，检查0700父目录、只读秘密文件、0400/0600入口私钥以及相关符号链接。Worker实例ID显式放到Worker环境，移除API中无关设置。Grafana新增专用maintenance桥接网络以实现宿主loopback发布，此桥接允许出站；外部分析/预装插件仍关闭。业务入口与指标/基础设施公开边界不变。

## 实测结果

| 检查 | 直接观测 |
|---|---|
| 新独立项目 | `forgeoj-m5check-9929d1ea812e`；Docker Server29.8.0 / Linux x86_64 / cgroup v2，Windows Docker Desktop内核环境 |
| 应用构建、空库 | 三镜像构建成功；原V1～V24迁移成功，再运行validate24/无待执行；用户/管理员/题目均0 |
| 五服务就绪 | API/Worker/Web/Prometheus/Grafana均有实际Healthcheck并healthy；不以仅进程运行代替就绪 |
| HTTPS | 新证书、15项检查PASS；TLS验证开启；Secure/HttpOnly/Strict、缺CSRF/跨Origin/错误Host拒绝、伪造转发无效、匿名WS拒绝 |
| 原权限回归 | API读隐藏测试、Worker读账号表真实1142拒绝；无devdata、非root/只读rootfs、API无Docker、随机秘密不在Config.Env |
| Linux引擎权限 | 命名卷真实POSIX：正常模式通过；0755私密父目录、0644秘密、0644TLS私钥、秘密符号链接四项拒绝；3个服务UID能读取单独授权的叶子挂载，API UID无法遍历整个私密父目录；重复生成拒绝且原秘密摘要不变 |
| 启动拒绝 | 实际bash/flock/start.sh配受控preflight/docker替身：活跃writer存在不执行up/migrate，并发启动锁拒绝；不是完整Linux主机start.sh安装验收 |
| 依赖中断 | 准确停止空库MySQL后，API/Worker的ready检查失败且Docker健康均unhealthy；准确恢复后API/Worker/Web重新healthy，没有改业务记录/清队列 |
| Grafana维护入口 | 宿主127.0.0.1:3000实际health返回database=ok/version13.2.3；实际NetworkSettings端口存在且HostIp仅127.0.0.1 |
| 监控持久性 | 创建当前项目专属测试面板并取得既有Prometheus样本；force-recreate两个监控容器，确认容器ID变化后，原面板ID/标题和原时间点的历史样本完整相同；面板准确删除 |
| 秘密与回收 | 五服务Healthcheck命令/历史不含本轮随机秘密；准确容器/卷/网络均0，自有3个镜像标签删除，共享镜像与无关Redis保留 |

复现入口：先运行Build-ProductionPackage明确传既有构件与两份SHA；运行Verify-ProductionPackage `-Lifecycle`（依次实际构建/迁移/HTTPS/audit/check-package-lifecycle）。成功后用Stop-ProductionPackageCheck准确清理，再执行finalize-package。不能在有真实数据的实例上执行生命周期测试。原始日志/临时私密文件在ignored运行目录，不可整目录复制到Git或公开。

## 失败与修复

保留 `failed-grafana-host-port.log` 与 `failed-compose-internal-grafana.yml`。首轮Grafana内部healthy，但`.NetworkSettings.Ports[3000/tcp]`为空，宿主连接10061拒绝；原配置仅加入internal网络，声明HostConfig端口不能证明实际发布。补专用非internal maintenance桥接后，从宿主实际连接、同UID面板持久化及真实端口限制复测通过。没有关闭防火墙、放宽127.0.0.1或把监控加入公共Nginx。

故障定位后重复完整生命周期检查，最终使用禁用环境代理的loopback测试客户端，避免测试Basic凭据经过代理。接受包中Compose修复及README维护说明分别更新摘要后再次预检；三应用镜像输入未改变。工具快照为本轮交付版本，不表示每个工具或其所有分支都已运行。

## 当前边界与下一单元

这是Docker Linux引擎中的配置/故障/持久性验证，不是全新物理/云Linux主机、真实用户试用或生产运行。旧演示8容器当前Exited(255)，保留未启停，不能把2026-10-11可访问当今天仍运行；另一个Redis容器一直运行，未操作。

全新Linux安装/宿主权限、旧版本升级、真实域名/CA/SMTP、管理员与普通用户完整业务、证书续期、服务器外加密备份/新库恢复、两个兼容版本回滚、固定主机性能、真实试用和发行审计仍未完成。下一有限单元可准备并隔离验证加密备份/恢复工具；异地目的地、访问和离线解密密钥保管仍须实际外部条件。E-06/E-07、M5总验收、Release/RESUME_READY不提升。
