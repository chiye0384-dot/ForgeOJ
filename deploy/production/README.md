# ForgeOJ 独立部署包

本目录是生产部署准备模板，不是开发replay的改名版本。先读包内 `notices/M5-DEPLOYMENT-DESIGN.md`。真实上线、备份恢复、回滚、性能和试用仍需各自验收。

## 取得与校验包

在Windows仓库用 `tools/deployment/Build-ProductionPackage.ps1` 明确传入接受的Linux构件目录与API/Worker SHA256。工具核对当前编译输入，显式复制构件/部署文件/告知，不复制仓库、target会话、SMTP本地文件、演示fixture或私密配置。生成 `target/forgeoj-deployment-*/package`；验证完成后打包为Zip。解压后运行命令，不要求旁边存在ForgeOJ源码。

Linux要求Docker/Compose、Python3.10+、openssl、bash和util-linux的flock；宿主为专用linux/amd64/cgroup v2。请勿在有真实数据的实例上试跑初始化或演练。以下为未来主机上的操作步骤，尚无实际主机时无需执行。

## 构建与准备配置

```bash
bash scripts/build.sh m5-preparation
# 把输出的三个 sha256 镜像 ID 填到包外 instance.env，不能填 latest。
cp .env.example /srv/forgeoj-instance.env
bash scripts/init-private.sh /srv/forgeoj-private
# 获取真实 socket GID，填入 instance.env：
stat -c %g /var/run/docker.sock
bash scripts/search-tls.sh /srv/forgeoj-private sha256:实际API镜像ID
```

默认监听127.0.0.1，生产HTTP80/HTTPS443。`FORGEOJ_DOMAIN`、HTTPS端口后缀和邮件APP_URL必须完全一致；实际域名/DNS/证书确定后，将CA证书链放 `/srv/forgeoj-private/tls/fullchain.pem`，私钥放同目录 `privkey.pem`，父目录0700、密钥只供Nginx root读取（通常0400/0600）。不要将生产密钥放进包目录。

ES内部证书与只含公钥的truststore通过脚本产生，API无ES私钥。file secrets本质是bind mounts：父目录0700保护宿主，叶子文件0444允许不同UID容器读取各自被授予的文件；不要认为Compose uid/gid会重映射文件权限。生成后妥善保存，重跑会拒绝覆盖。更改DB秘密文件不会自动轮换已存在MySQL账号或Rabbit密码，需要专门维护窗和轮换验证。

填入实际生产SMTP主机、发件/登录地址，`FORGEOJ_MAIL_MODE=smtp`、TLS=starttls或implicit（隐式TLS时通常改端口465，按服务商实际设置）。授权码写私密 `secrets/smtp-password`，不写instance.env/命令行/Git。实际邮件仅发送给明确授权收件人。本包默认disabled，仅用于准备；生产预检会拒绝disabled及示例域名。

## 空库安装与启动

```bash
python3 scripts/preflight.py /srv/forgeoj-instance.env
bash scripts/start.sh /srv/forgeoj-instance.env
# 明确管理员初始化；交互输入数据库确认、用户名、密码；首次登录必须改密。
docker compose --env-file /srv/forgeoj-instance.env -f compose.yml run --rm admin admin-bootstrap
```

start先校验包全部文件/镜像/配置与秘密，再启动私有基础设施、运行Flyway V1～V24（不含devdata）、配置限域ES账号，最后API/Worker/Web/监控。任一步失败不继续。初始普通用户/题目/管理员均为空，禁止复制演示账号。应用日志、SQL DEBUG、请求body、curl -v、set -x、完整私密配置输出不得用作常规诊断。

第二版新增明确就绪检查：API要求数据库快照新鲜，Worker还要求消费者活动，Nginx通过本机HTTPS实际代理session（验证证书与域名），Prometheus/Grafana使用私有ready/health接口。`up --wait`等待这些检查通过，启动失败不会报告就绪。健康检查只影响状态和启动等待，不会自动重启不健康容器或修改业务任务；依赖中断时检查可以失败，不承诺自动修复。

`start.sh`用flock串行化同一instance文件的启动；发现该实例API/Worker/Web仍运行就拒绝迁移，不自动停写或清队列。Linux预检复用`private-permissions.sh`：四个私密父目录0700、秘密只读、TLS私钥0400/0600，拒绝相关符号链接。Windows文件模式不能作为Linux权限证据，现场仍要核对宿主挂载/UID/GID及维护账号。

升级：备份并验证可恢复、记录旧镜像与schema兼容性→维护窗拒绝新写→观察在途任务/lease/未ACK及死信→显式停止本实例web/api/worker→更改镜像ID→再运行start。不要直接 `down -v`，不要对其他项目/主机服务做广泛停止，不用Flyway clean/repair或schema降级替代升级。

## HTTPS 冒烟及维护访问

```bash
curl --fail https://实际域名/api/v1/auth/session
curl --fail https://实际域名/problems
# 由维护者自己启动本地转发；不要关闭现有SSH：
ssh -L 3000:127.0.0.1:3000 维护账号@实际主机
```

session应匿名200且Set-Cookie包含Secure/HttpOnly/SameSite；跨域写入403、伪造转发头无法改变Origin判断。浏览器逐项验证注册/授权SMTP激活、登录、创建/验证/审核原创题、提交真实AC、WS与轮询、管理员权限/审计。空库匿名冒烟不能代替这些业务门禁。基础设施端口不应有公网映射，主机端口/防火墙需另外实测（Docker端口规则不能仅凭UFW状态推断）。

监控不经公共Nginx提供，Grafana只发布宿主loopback3000，不同实例应先检查冲突。Prometheus/Grafana采用命名卷；28日/2GB指标预算与每容器日志3×10MB需实际磁盘告警。首次创建Grafana账号有私密随机密码，日常查看改用Viewer；不要发送秘密或共享生产admin密码。

Grafana单独连接maintenance桥接网络，让Docker实际提供127.0.0.1端口转发；此桥接网络允许出站连接，外部分析/插件预装等仍关闭。其他基础设施不连接该维护网络，也不新增公网映射。不能只看Compose声明或容器内部健康，应在宿主访问`http://127.0.0.1:3000/api/health`，再经已授权维护隧道使用。

入口证书续期由主机维护方案执行，经 `nginx -t` 与准确web容器reload，再检证书日期与HTTPS；内部ES证书365日，提前更新信任并验证后滚动。脚本不购买域名、不申请CA、不配置云安全组或修改宿主sysctl。

## 备份与回滚接口

备份设计见M5设计第5节。本单元没有声称提供已验收的每日异地加密备份、恢复或兼容镜像回滚脚本。私密目录/TLS/迁移器身份的离线保管独立于业务dump；ES可重建而MySQL不可丢，Rabbit在途/死信另有恢复策略。后续确定服务器外存储/访问/离线解密密钥后实施与演练。

## 隔离验证

`preflight.py --isolated` 只接受127.0.0.1、`.test`域名和disabled邮件，禁止借此公网发布。测试证书为单独临时私密目录，绝不装入浏览器/OS信任库。独立随机项目/卷；验证完只清理准确项目和自有应用镜像，保留用户LAN演示与共享基础镜像。完整验收及失败记录在仓库 `docs/M5-DEPLOYMENT-VALIDATION.md`，不把Docker Desktop运行当全新Linux主机上线。

2026-10-27更新：scripts/backup.py已提供手动维护窗加密/独立空库恢复/只读保留计划，单独工具Zip随附完整操作说明。此前“本单元未提供恢复脚本”是首版范围历史；每日调度、异地副本、生产切流与回滚仍未验收。

2026-10-27：scripts/rollback.py提供同RootFS、仅ENTRYPOINT变化的镜像配置回退，配套独立工具包内含手册；显式维护窗、任务/消息清静和计划复核是前提。不能代替跨业务版本兼容矩阵或数据库恢复，真实主机与切流未验收。
