# M5 手动加密备份与独立新库恢复验收

2026-10-27 整理交付；实际数据库与加密演练记录产生于 2026-10-24，未冒充今日重跑。有限单元 **VERIFIED**，完整 M5 **IN_PROGRESS**，E-06/E-07、Release、RESUME_READY 不提升。

## 已验证的结果

使用 Windows/Python 3.13 与 Docker Desktop Linux 引擎，在本轮拥有的独立空库部署中安装明确的公开开发 fixture，普通账号经真实 HTTPS 登录并提交 Java 两数之和，真实 Worker 完成提交 `b7833727-1750-4603-94ea-eb9ef98955d1`，结果 FINISHED/AC。随后明确停止该测试实例的 Web/API/Worker，执行维护窗备份与恢复。没有对旧手机演示库或真实用户数据执行本演练。

| 检查 | 结果及适用范围 |
|---|---|
| 新项目、新 MySQL UUID、新私密配置、空 schema | 恢复前运行身份仅 USAGE，没有先运行迁移器建表 |
| 71 张 InnoDB 表 | 所有逐表行数、确定性 data-only dump SHA、Flyway 历史与 API/Worker 表/列授权全部匹配，源库未改变 |
| 迁移校验 | 恢复后相同构件的 24 个 Flyway 版本校验/迁移无变更通过 |
| 权限拒绝 | API 读隐藏测试、Worker 读用户表均实际 ERROR 1142；没有提权换取通过 |
| 认证加密 | AES-256-GCM / RSA-OAEP-SHA256；摘要与完整解密认证在导入 SQL 前完成；未写数据库明文 dump 文件 |
| 8 类拒绝 | 已有密钥目录、活跃 writer、已有备份输出、错误私钥、错误密文摘要、篡改后故意提供新摘要、恢复到源实例、恢复到非空目标库 |
| Linux 引擎文件权限 | UID 1000、目录 0700、私钥 0600 下实际加解密往返通过；不是全新 Linux 主机 Python CLI 的验证 |
| 保留计划 | 15 份合成日历副本得到保留 9 / 删除候选 6 / 实际删除 0；逐文件未改变，覆盖 7 个日期和 4 个 ISO 周；不是实际连续四周运营 |

原始机器结果、固定工具摘要、工具包摘要与清理回执集中于 [安全机器证据](evidence/m5-backup/20261027/verification.json)。安全证据不包含私钥、实例密码、数据库 SQL、业务密文或私密配置。

## 可复现入口与来源

操作说明见 [备份与恢复手册](M5-BACKUP-RESTORE.md)。编排命令为 `python tools/deployment/Verify-BackupTools.py 拥有者部署目录 docker路径 node路径` 与 `python tools/deployment/Verify-BackupCryptoPosix.py docker路径`；前者需要本轮新建、显式 fixture 的拥有者部署目录，不应传入真实实例或旧演示目录。源码打包入口为 `python tools/deployment/Build-BackupKit.py`，只复制五项固定白名单并校验解压文件、CRC、SHA256、AST 和五个 CLI help 路由。

本次独立工具包为 `target/forgeoj-backup-tools-20261027-093540-fbb721fd/forgeoj-backup-tools-20261027-093540-fbb721fd.zip`，16,402 字节，SHA256 `69ea58f59a0233982af2cfa3b0f40abe6424eb18adeed54a992553e3d90f56b3`。它是供第二版部署包配合使用的工具，不是新的完整部署发行包。

- 源部署：`target/forgeoj-deployment-20261024-210022-78391b32`，项目 `forgeoj-m5check-29fc2fce56ac`。
- 最终恢复目标：`target/forgeoj-deployment-20261024-211833-9ab7ed76`，项目 `forgeoj-m5check-1f3f3562c7d4`。
- 前两次目标：`forgeoj-m5check-635415b1a898`、`forgeoj-m5check-9b91b8be6437`；失败/较早通过记录保留，没有删除后改写成第一次成功。
- 原固定构件：`target/forgeoj-linux-20261011-092954-14188847`；业务输入未改变，本单元未重新全量编译。API SHA256 `f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e`，Worker SHA256 `ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f`。
- 最终业务备份密文 SHA256 `b0f3b5eb411c025ebbf3772547fc9da2193d2635088873aa91125145ad733861`；密文与测试私钥仅保留在 ignored 演练目录，不随工具包或证据分发。

## 故障与修复记录

首次 mysqldump 使用了仅 mysql 客户端支持的 `--connect-timeout`，实际拒绝后修正；原失败日志保留为 `failed-mysqldump-connect-timeout.log`。新目标运行账号无 schema 权限，原 SHOW GRANTS 错误预选数据库导致拒绝，修正为不预选数据库查询本人授权，没有增加权限，日志为 `failed-unprivileged-target-show-grants.log`。POSIX 测试初始化中 chown 后再 chmod 与受限 capabilities 冲突，修正测试初始化顺序，日志为 `failed-posix-probe-chmod-order.log`。这些日志均在源演练目录保留。

最终工具与已通过演练包中的 `backup.py` 字节一致；早于权限修正的结果不是本次接受结果。日期不同的原始报告保持原样。

## 清理和未完成边界

前两次目标于 2026-10-24 清理；最终目标及源项目于 2026-10-27 核对清理回执与引擎状态，四项目容器/卷/网络均为 0。脚本只定向核对各自标签、路径和镜像别名，没有 prune 或操作其他服务。旧 LAN 演示八容器当前已退出，保留未启停；无关 `redis` 保持运行。旧网址不保证现在可用。

本单元只有本地文件副本、手动维护窗和新数据库比对，没有搭建每日调度、远端存储/上传确认、真正 7 日/4 周运行、生产密钥保管、全新 Linux 主机或正式 HTTPS/SMTP。RabbitMQ 在途/未 ACK/死信与 Outbox 协调、恢复后的会话和邮件令牌处置、真实业务 API 恢复回放、应用切流/兼容镜像回滚、性能与真实用户试用仍未验收。没有自动启动目标应用、覆盖源库或声称整个系统已恢复。下一有限单元可设计并验证同 schema 的兼容镜像回滚；跨 schema/数据回滚需独立设计，不能直接套用。
