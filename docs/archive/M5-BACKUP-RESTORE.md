# ForgeOJ 加密备份与独立新库恢复工具

2026-10-24：延续用户“继续”授权，首版实现的是**维护窗口内手动MySQL备份、加密、独立新库恢复与保留计划**。真实结果见[M5备份验收](M5-BACKUP-VALIDATION.md)。不是每日调度、服务器外副本、整个系统恢复或生产切换。

## 1. 使用边界

工具 `deploy/production/scripts/backup.py` 只需Python3.10+、Docker/Compose和现有独立部署包；可从单独工具Zip解压使用，不要求ForgeOJ源码。加密复用已固定Temurin运行时内OpenSSL，无新增Python/Maven依赖，镜像不改为latest。Linux加密助手使用调用者UID/GID，Windows Docker Desktop使用0:0映射；均无网络、只读根目录、无Docker socket、cap_drop ALL，并只挂所需公钥或离线私钥。

目前每份SQL最大60MiB，未压缩内存归档最大64MiB、密文65MiB，单条外部命令180秒。只支持全部为InnoDB的基本表，发现视图/触发器/存储过程/事件即拒绝。源端须明确停止本实例API/Worker/Web及维护作业，工具拒绝活跃writer且与start.sh共用实例锁；不自动停业务或清消息。维护者还须保证没有其他人工DDL/写入。它不是无停机在线备份或PITR/binlog归档方案。

使用已有迁移器维护身份导出与导入，保持原schema内维护权限，没有给API/Worker增权，也没有新增全局备份账号。迁移器是可信运维身份而非专用只读备份身份；后续生产自动化应另行评估更窄权限。

## 2. 公私钥与完整性

备份端仅持RSA3072公钥证书。AES-256-GCM加密归档，RSA-OAEP-SHA256封装内容密钥，CMS流式二进制格式；OpenSSL完整认证通过后才将内存中的SQL送入MySQL。不会把数据库明文dump写入宿主临时文件。说明与实现依据：[OpenSSL CMS](https://docs.openssl.org/3.0/man1/openssl-cms/)。

`keygen`只在不存在的新目录创建私钥和公钥证书，拒绝覆盖。离线私钥文件未加口令，依赖0600权限与用户自行安排的离线/加密介质及另一份受控恢复副本；这个保管安排尚未实际提供或验收。不要在生产备份机生成并长期保留解密私钥，不要放进应用、Git、镜像、日志、邮件或备份上传目录。测试中生成的私钥只属于独立演练。

收据记录密文SHA256和UTC时间。**SHA256不是来源签名**：持公钥的人也能制造另一份有效密文，所以恢复必须从可信、独立保管的收据取得预期摘要，不能随手采用来源不明文件旁的收据。GCM检测密文篡改不等于认证备份出自哪台服务器；本单元没有搭建数字签名/密钥托管基础设施。

## 3. 备份包含与不包含

包含MySQL的全部基本表、原始源码/压缩测试BLOB、会话/配额、提交/任务/attempt、Outbox、管理审计及Flyway历史；元数据和SQL一起加密。只导出forgeoj schema，不包含mysql系统库、DB登录口令或实例JWT/SMTP/TLS配置。

同时导出API/Worker本人的表级/列级GRANT，严格白名单限forgeoj内SELECT/INSERT/UPDATE/DELETE以及两个既有身份；不接收全局权限、WITH GRANT OPTION或其他账号的授权。迁移器/DB root仍由新实例的私密配置初始化，源端密码不会覆盖新实例。`SHOW GRANTS`不预选数据库，所以尚无schema权限的新运行账号也能被正确检查。

备份前后逐表计数、确定性data-only dump SHA、Flyway历史及权限必须相同。mysqldump使用single-transaction/quick/no-tablespaces/GTID OFF、完整列/有序行、BLOB十六进制和无DROP输出；维护窗保证采集元数据期间无写入。[MySQL导出文档](https://dev.mysql.com/doc/refman/8.4/en/mysqldump.html)。

不包含RabbitMQ队列/未ACK/死信、Redis、ES索引、监控历史或整个主机文件系统。Redis/ES可重建不等于其余恢复步骤已完成；DB恢复后仍须处理原Outbox与RabbitMQ在途消息的一致性，不能直接启动Worker。

## 4. 操作示例

以下是未来操作者的命令模板，不是当前真实服务器配置。默认Docker命令为PATH里的docker；必要时在子命令前加 `--docker /完整路径/docker`。

先在受控离线设备准备密钥：

```bash
python3 backup.py keygen --directory /受控离线位置/forgeoj-backup-key
# 将recipient.pem公钥证书复制给备份端。private.pem继续离线保管。
```

操作者自行安排维护窗、停止准确实例写入服务后执行备份；工具本身不执行停机：

```bash
python3 backup.py backup --package /srv/forgeoj-package \
  --instance /srv/forgeoj-instance.env \
  --recipient /srv/backup-public/recipient.pem \
  --output /srv/encrypted-backups/唯一时间名.cms
```

输出LOCAL_ENCRYPTED，只表示本地加密副本已生成。`.receipt.json`单独按可信渠道保管。实际服务器外传输、远端摘要确认、访问凭据、告警及每日调度都未实现；没有远端确认不能算每日异地备份成功。

## 5. 恢复到新库

操作者先用独立项目名、新私密配置和**新命名卷**只启动MySQL；不要先运行Flyway、API、Worker或把旧卷挂入新项目。数据库forgeoj应存在但没有任何表，API/Worker仅有USAGE。还须具有与备份相同的API输入JAR摘要，禁止把未知schema版本直接导入新版本。

```bash
python3 backup.py restore --package /srv/相同兼容构件包 \
  --instance /srv/独立恢复实例.env \
  --backup /受控副本/唯一时间名.cms \
  --sha256 从可信收据取得的64位摘要 \
  --recipient /离线恢复位置/recipient.pem \
  --private-key /离线恢复位置/private.pem
```

恢复要求项目名和MySQL server_uuid均不同于源；空表与空运行授权条件失败就拒绝。先验证摘要、完整解密和归档成员，再导入表/数据及受限GRANT，最后核对全部计数/data SHA/Flyway历史/权限。SQL客户端启用binary-mode、关闭local-infile；不执行客户端system/source命令，也不用root导入。[MySQL客户端选项](https://dev.mysql.com/doc/refman/8.4/en/mysql-command-options.html)。

任何中途失败都保留独立目标供检查，不自动DROP/TRUNCATE/clean后重试，不触碰源库。成功仅输出RESTORED_AND_MATCHED，**不会启动应用或切换流量**。之后可先用维护迁移器validate相同历史，再做经授权的业务/权限/消息核验。真实切换必须明确会话/refresh/邮件令牌撤销与JWT秘密、备份后新写入损失、RabbitMQ/原Outbox恢复策略；工具保持历史数据保真，不擅自改这些业务事实。

## 6. 7日与4周保留计划

```bash
python3 backup.py retention-plan --directory /srv/encrypted-backups
```

按收据UTC日期和ISO周选择最近7个不同日期、4个不同周各自最新一份，先验证每份密文摘要。只是PLAN_ONLY，列出保留与删除候选，**删除数恒为0**；不因为本地文件数量漂亮就自动删除最近有效副本。必须确定远端成功回执、访问及删除策略后，另行实施保留清理；实际7日/4周运行尚未验收。练习用日历数据测试不等于真实运行了四周。

## 7. 交付与下一步

单独工具包只包含backup.py、本说明和项目LICENSE/NOTICE，不含应用构件、任何私钥、CMS业务数据或实例配置。与第二版部署包配合使用。真实Linux主机/权限、异地复制、每日调度、完整业务恢复/消息协调、生产密钥保管和完整发行审计仍是M5缺口；本单元不会将M5或E-06整体标为VERIFIED。
