# M5 部署包验证

2026-10-24当前：第二版就绪/真实POSIX引擎权限/数据库中断恢复/监控容器重建持久性有限单元已 **VERIFIED**，见[第二版验收](M5-DEPLOYMENT-HARDENING-VALIDATION.md)。首版及其证据保持历史，当前完整M5仍IN_PROGRESS。

2026-10-11：设计和首版独立包 **IMPLEMENTED**，本文列出的隔离配置/镜像/空库/HTTPS检查 **VERIFIED**。用户已授权本单元，无外部主机/域名/备份/SMTP/试用条件。完整M5仍IN_PROGRESS，不能把下列有限检查提升为真实主机上线。

业务基线 `058f36c`，完整M4 `VERIFIED`，复用已验收Linux构件 `target/forgeoj-linux-20261011-092954-14188847`。新增部署配置/runner/包工具与编译输入分别记录。手机LAN演示项目 `forgeoj-e2e-20261011-145312-83d835a3` 保持运行，用户手机已确认登录/真实AC。

M5完整状态 **IN_PROGRESS**；全新主机、异地恢复、兼容镜像回滚、性能和真实试用五门禁均未通过。未Release/RESUME_READY。

## 实際执行与交付范围

接受包目录 `target/forgeoj-deployment-20261011-152559-02e22ae1/package`，独立测试项目 `forgeoj-m5check-df0331a167df`。机器证据及Zip摘要见 [verification.json](evidence/m5-deployment/verification.json)。不重复编译业务，生成包时428输入全部hash匹配、API/Worker输入JAR明确核SHA；三份镜像独立实际构建，API展开并排除devdata，镜像ID另记，不称运行API字节仍是输入JAR。

| 检查 | 实测结果 |
|---|---|
| 脱离源码构建 | 包内三份Dockerfile实际成功；全部shell脚本bash -n通过 |
| 配置预检 | 48包文件摘要匹配；随机独立私密文件；isolated PASS；示例域名/disabled SMTP正式模式拒绝 |
| 空库迁移 | 原V1～V24全部SQL成功；第二次validate24并无待执行迁移；不是旧版本升级演练 |
| 无种子身份 | MySQL普通用户0、管理员0、公共题0；API镜像无db/devdata及application-dev.properties |
| 权限/进程 | API读隐藏测试、Worker读用户表均真实1142拒绝；两个进程非root/只读rootfs；只有Worker挂Docker socket；无迁移/rootsecret；运行Config.Env不含随机秘密值 |
| HTTPS入口 | 使用专用oj.test自签证书的CA信任，未关闭TLS验证；15项检查PASS：静态/SPA、HTTP308、Secure/HttpOnly/Strict、缺CSRF/跨Origin403、伪造转发不改变判断、dev-mail/src404、错误Host拒绝、匿名WS经API拒绝 |
| 监控 | 实际promtool通过；API/Worker/Rabbit三目标up；两指标入口匿名401；Grafana数据库ok，Prometheus/Grafana命名卷配置；不冒充重启持久化/浏览器监控验收 |
| 清理 | 准确测试项目容器/卷/网络删除；应用镜像只按记录的自有标签清理；共享基础镜像和LAN演示保持，原LAN session实测200 |

原始日志在对应ignored运行目录，包含测试随机秘密时不能直接复制/打印整个目录。最终包只从48项公开包文件和manifest归档，不含旁边private/instance.env/运行日志。Compose配置通过解析检查网络/port/secret路径，仅提取必要字段，不输出完整私密配置。

## 保留的失败与修复

- `target/forgeoj-deployment-20261011-152136-c83b9a7f`：YAML flow临时挂载未加引号，逗号被解析成额外路径，启动失败；已加引号，准确资源清理。
- `target/forgeoj-deployment-20261011-152430-b011a945`：Windows bind secret模式777被ES拒绝；使用容器内0400临时密码文件解决，准确资源清理。正式Linux父目录权限仍待实际主机核验。
- 接受运行内保留 `failed-compose-before-nginx-chown.yml` / `failed-compose-before-nginx-cache.yml` 及对应HTTPS失败日志；只读Nginx启动分别缺临时目录CHOWN和默认cache写路径，补仅所需cap及16MB tmpfs后通过。
- `https-smoke-failed-samesite-expectation.log` 是检查器错误地期望Lax；实查既有源码和安全属性为Strict，改成严格匹配Strict后15项PASS，没有改业务Cookie或删除安全断言。
- 接受包compose与两份非运行行为文件（README/SMTP implicit预检枚举）按修复更新清单后复核；原失败配置/日志在包外保留。SMTP仍disabled，没有真实邮件投递验收。

## 仍须完成

全新Linux主机/实际文件权限、真实域名CA/SMTP/维护访问、旧版本升级、授权账号完整业务/管理员/WS推送、监控重启持久性、证书续期、异地加密备份与新库恢复、两个兼容版本镜像回滚、固定主机性能、真实试用及完整发布物许可证/漏洞审计均未由本单元验收。默认loopback/disabled邮件/显式维护迁移仅用于准备；正式预检并不代替完整发布门禁。当前保留文档/部署工具工作树，未提交推送或公开发布。
