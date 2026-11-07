# M5 镜像启动配置回滚：设计与操作

2026-10-27。当前有限范围是 **同一文件系统与业务构件、仅 ENTRYPOINT 不同的镜像配置回滚**。不是跨业务版本兼容矩阵、数据库降级、备份恢复或无停机发布。当前状态和真实结果见 [验收记录](M5-IMAGE-ROLLBACK-VALIDATION.md)。

## 选择与风险

现有已验收业务版本中，较早 M4 搜索版本没有当前私有就绪检查协议。没有把它接到现有 Compose 后关闭健康检查来冒充安全兼容。因此先完成保守的镜像配置回退工具：源/目标必须是本机已有的不可变 `sha256:` ID，各组件 RootFS 层和除 ENTRYPOINT 外的镜像 Config 必须完全一致，至少一个组件 ID 不同。标签改名或同镜像重启不能通过。Java/JAR、前端、schema、消息契约、用户身份和授权均不变。

工具不会判断任意 Java 启动参数是否安全；操作者仍须选取可信且曾验收的目标，核对计划来源和摘要。具有 Docker/维护数据库权限的操作者属于信任边界。计划摘要防止选错或被改动，不是签名。对文件或 schema 有变化的目标一律拒绝，需未来建立两个真实业务版本的升级/回滚矩阵。

演练会在明确拥有的全新空库项目安装原公开 fixture，经真实 HTTPS 生成 AC，再派生一个故意含无效 JVM 启动参数的坏镜像。坏镜像仅属测试故障，不进入独立生产包、不作为“已发布业务版本”。实际经历启动失败后使用工具回到原已验收镜像，并重新进行普通账号判题与历史核验。

## 前置条件和计划

只使用单实例维护窗。操作者先停止新流量，等待当前任务/租约/发布完成，再显式停止准确实例 Web/API/Worker。工具不自动停止原有写入者、改判定、清队列、重置预算或恢复死信。必须同时满足：

- 原三个应用容器存在、明确停止，实际镜像与实例文件相符；迁移器、管理员维护及搜索 setup 都没有运行。
- MySQL/RabbitMQ/Redis/搜索/Prometheus/Grafana 全部健康，所有容器标签/项目身份准确。
- V1～V24 共 24 版迁移成功；无正式判题、作者验证、自测运行/等待/重试及活动 lease/attempt，无待交付 Outbox、缓存失效或搜索重建；九个已知 Rabbit 队列（含死信）消息、未 ACK 和消费者均为 0。有遗留任务或死信时进入独立恢复流程，不直接套用本工具。
- 部署文件和私密配置预检通过，所有目标镜像已在本机；Linux 主机仍需真实权限验收，Windows 检查不替代它。

与 start.sh、backup.py 共用实例锁。计划绑定项目、数据库 UUID、完整结构/数据 dump 摘要、逐表数据摘要/行数、Flyway 历史、运行授权、实例文件摘要、包清单摘要、应用和基础设施容器 ID 及两组镜像。即使只改变索引、数据不变，也会拒绝执行旧计划。计划 30 分钟有效，执行前重新检查所有事实。

独立工具包的 `rollback.py` 与 `backup.py` 必须保留在同一目录，前者复用后者的私密命令、实例核对和锁；包内没有实例配置、回滚计划、私密原配置或任何应用镜像。可配合已有第二版独立部署包使用。

```bash
python3 scripts/rollback.py plan --package /srv/forgeoj-package \
  --instance /srv/forgeoj-instance.env \
  --api sha256:已验收API镜像ID --worker sha256:已验收Worker镜像ID \
  --web sha256:已验收Web镜像ID --output /srv/maintenance/唯一计划.json
# 仅独立 .test/loopback/disabled-mail 演练才使用 --isolated。
```

上面是操作模板，不是可直接复制的真实配置。读取输出摘要，检查受控计划中源/目标镜像和 ENTRYPOINT，再执行：

```bash
python3 scripts/rollback.py apply --plan /srv/maintenance/唯一计划.json \
  --sha256 经可信渠道核对的计划摘要
```

## 执行、失败和完成标准

执行前独占创建 `.applied.json` 日志和 0600 `.before.env` 私密副本，拒绝覆盖或重复执行。原实例文件用同目录临时文件和原子 replace 更新三个镜像 ID，其他配置值保留。私密副本可能含将来的 SMTP 配置，不得打包、提交、打印或用邮件传播。

只按 API→Worker→Web 顺序执行带 `--no-deps --no-build --pull never --wait` 的 Compose up，不运行迁移、拉取镜像、重建数据库或重建基础设施。[Docker Compose up 官方语义](https://docs.docker.com/reference/cli/docker/compose/up/)。数据库 UUID/完整数据和授权以及六个基础设施容器 ID 必须保持；失败则核对应用所有权后停止这三个选定应用，保留目标配置、私密原配置和失败日志，不自动再次尝试或切回。

`READY_FOR_BUSINESS_CHECK` 只表示容器就绪和数据/权限核对通过。还须真实 HTTPS 登录、读回历史原 AC/源码、提交新的代码并获真实 Worker AC、检查权限和消息，再由操作者验收。业务请求自然产生的新数据不应再与维护窗的完整快照机械比较。

本单元不重启 Codex/ChatGPT、Docker Desktop、WSL 或无关服务，不把脚本锁当作对其他人工 SQL/直接 Docker 操作的全局锁。维护窗仍须操作者保证无其他写入者。本工具没有支持跨主机、多个实例配置指向同一项目、长期 unattended 自动回滚或公网敌对程序运行。
