# M5 镜像启动配置回滚验收

2026-10-27。**同文件系统、仅 ENTRYPOINT 变化的镜像配置回滚有限单元 VERIFIED**；完整 M5 保持 IN_PROGRESS。跨业务版本兼容、schema 升降级、正式 Linux 主机和真实切流仍未验收；不是两个不同业务版本之间的升级/回滚。操作与约束见 [回滚手册](M5-IMAGE-ROLLBACK.md)。

## 真实演练结果

使用 Windows/Python 3.13、Docker Desktop Linux 引擎和已有固定构件。独立空库安装 V1～V24，通过 15 项 HTTPS 检查后，仅向这个拥有者项目安装原公开开发 fixture。较早 M4 搜索构件缺少当前就绪协议，没有关闭健康检查或将其当作已兼容目标。

测试基线 API 镜像 `sha256:057b6d916b7fc6be1026c0943c146f431d72fd173ccb85d49b363eeb21d80c11`；故障镜像 `sha256:e2d20bfc382ce7f581e063f95569900026609cf83f2c4e51856debaefcf72697`。两者 ID 不同、RootFS 相同，仅将 ENTRYPOINT 改为故意无效的 JVM 参数。实际观察到 `Unrecognized VM option 'ForgeOJDeliberateStartupFailure=1'`、退出码 1；故障镜像不进入交付包或正式发布记录。

| 项目 | 接受结果 |
|---|---|
| 启动故障处理 | 目标无法就绪，工具记录 FAILED_STOPPED，三个应用保持停止；不自动再次尝试、回源或恢复数据库 |
| 新计划回退 | 回到原不可变 API/Worker/Web 镜像，按 API→Worker→Web 就绪；六个基础设施容器 ID、数据库 UUID 保持 |
| 数据和结构 | 维护窗前后 71 表计数、确定性数据 SHA、完整 schema/data dump SHA、24 版 Flyway 和运行授权匹配 |
| 真实判题 | 最终一轮开始前 AC `fce585f9-b578-4320-8099-74496deab22a`；真实排队提交 `3f5b22b1-49ca-4002-8664-bcb2fb56150c` 由恢复后的 Worker 自然完成；回退后新提交 `872382d7-ba88-4081-85a9-245b7abf1c5c` 真实 FINISHED/AC |
| 历史保留 | HTTPS 读回最终一轮原 AC；清理前 SQL 对五条新旧真实提交逐一复核 FINISHED/AC 与原源码 SHA，含此前失败演练留下的两条，没有重置历史或造判定 |
| 权限与运行约束 | API 读隐藏测试、Worker 读用户表实际 ERROR 1142；API/Worker 非 root、只读根、API 无 Docker socket、维护凭据未挂载、私密值不出现在 Config.Env |
| 11 类拒绝 | 活跃写入者、真实未完成提交、同一镜像、可变标签、文件层变化、计划摘要不符、实例锁占用、实例配置变化、仅新增索引的结构漂移、计划过期、重复执行旧计划 |

结构漂移验证只向测试库临时新增索引，确认 data-only 快照保持相同但完整 dump SHA 改变，执行旧计划确实拒绝；随后准确删除该测试索引并比对原结构摘要，不改 Flyway 历史或业务数据。最终审计确认测试索引不存在。

## 构件、执行与证据

- 部署目录：`target/forgeoj-deployment-20261027-114214-aa1ad0f9`。
- 项目：`forgeoj-m5check-3c7199fdf1f7`；接受证据代际：`rollback-attempt-20261027-200636`。
- 原业务构建：`target/forgeoj-linux-20261011-092954-14188847`。428 项业务/测试/前端输入仍逐文件匹配；本单元不重新全量编译。API JAR SHA256 `f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e`，Worker JAR SHA256 `ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f`。
- 执行编排：`python tools/deployment/Verify-ImageRollback.py 拥有者目录 docker路径 node路径`。只适用于新建空库拥有者环境；`--reuse-ready` 仅在已核验原镜像就绪的同一测试环境创建新证据代际，不重灌 fixture 或覆盖旧文件。
- 安全汇总：[机器证据](evidence/m5-image-rollback/20261027/verification.json)。只保留报告、已审查的故障原因、构件/工具摘要和清理回执，不复制实例文件、计划全文、`.before.env`、秘密、SQL dump 或整个演练目录。
- 打包：`python tools/deployment/Build-RollbackKit.py`，固定六文件白名单，逐文件摘要、CRC、解压后 AST/CLI 检查；独立工具包用于配合现有第二版部署包，不重写旧部署 Zip。

## 失败记录与准确边界

首次“文件层变化”负例在非 root 镜像的 `/` 写测试标记而构建失败，改到该用户可写的 `/tmp`，没有给产品镜像增权。测试夹具重复构建曾改变镜像 ID；旧 ID/失败记录保留，在尚未生成计划前核验新夹具的准确命令与相同文件层后冻结，后续不重复构建它。

早先验证进程经历长时间停顿后超时，具体系统原因未确认，不能称为已通过；工具留下真实 FAILED_STOPPED 回执，原数据不变。先核对停止状态，用新的计划恢复原镜像，再冻结新增完整结构摘要的工具并重新执行全部有限门禁。接受的是 `rollback-attempt-20261027-200636`，不是先前失败/超时结果。原记录保留在 ignored 演练目录。

源/目标业务 JAR 与文件层完全相同；不同的是镜像启动配置。因此本单元证明配置发布失败后的回退、停止保护、数据保留和判题恢复，不证明任意旧业务构件理解新 schema/消息。严格无待处理任务/死信条件、有界内存 dump、单实例维护窗和操作者无其他人工写入仍是前提。全新 Linux 权限、跨业务版本矩阵、在线流量切换、真实生产 SMTP、每日异地备份、性能和真实试用均未完成；E-06/E-07/Release/RESUME_READY 不提升。

## 交付与清理

独立工具Zip：target/forgeoj-rollback-tools-20261027-201347-7a617b78/forgeoj-rollback-tools-20261027-201347-7a617b78.zip，20174字节，SHA256 `4c518731247ce4221f5bcdc9eb01c838726e30d1bad7e89f26f30a3ef4527890`。六文件白名单、CRC、解压摘要/AST/三个CLI help路由检查通过。

准确测试项目容器/卷/网络全0、三应用镜像别名及两夹具镜像别名已无force清理；没有prune/清理其他服务。旧LAN演示八容器退出且保留，无关redis运行；旧网址不可当作当前在线。原部署与备份Zip未覆盖。工作树未提交推送，保护文件只核摘要且未变。下一有限单元建议固定环境性能设计/基线测试，仍需独立核额度、冻结构件/环境/数据集/原始结果。
