# ForgeOJ 版本路线图

> 当前阶段：M-1 项目准备已验证通过；M0 最小判题纵向切片进行中。
> 路线图描述先后顺序，不代表完成状态。

## 1. 状态规则

- `PLANNED`：已确认，尚未实现。
- `IN_PROGRESS`：正在开发。
- `IMPLEMENTED`：代码完成，尚未充分验证。
- `VERIFIED`：对应自动化测试或验收通过。
- `RELEASED`：进入有版本号的正式发布。
- `DEFERRED`：明确推迟到以后。
- `BACKLOG`：只有候选想法，尚未承诺进入版本。

当前 M-1 为 `VERIFIED`；M0 为 `IN_PROGRESS`；M1 至 V1.1 仍为 `PLANNED`；V2.0 仅为 `BACKLOG` 候选，不是承诺的里程碑。

## 2. 总体顺序

~~~text
文档基线
  → M-1 项目准备
  → M0 工程与判题纵向切片
  → M1 可靠异步判题
  → M2 题库与学习主流程
  → M3 班级与作业
  → M4 管理、搜索与降级
  → M5 Linux 验收与阿里云小范围上线
  → V1.0 Release
  → V1.1 题解社区

V2.0 综合学习内容：BACKLOG，需另行确认是否立项
~~~

每个里程碑通过门禁后再进入下一个，不并行铺开大量未闭环模块。

## 3. M-1：项目准备

目标：建立可追溯的工程起点。

验证结果（`VERIFIED`，2026-09-28）：

- 已确认并登记 Spring Initializr + create-vue 最小官方骨架，不引入大型业务脚手架；
- 已固定 JDK 21、Spring Boot 4.1.1、Maven 3.9.14、MyBatis Starter 4.1.0；PageHelper 为 `DEFERRED`；
- 已生成后端双模块、前端、`deploy`、文档和 CI 目录，并初始化 `main` 分支的 Git 仓库；主干加短期主题分支策略见 D-033；
- 已在当前 Windows 环境通过后端 `clean verify` 与前端类型/静态/格式/测试/构建检查；
- 用户已人工确认当前工程结构；ForgeOJ 自有代码已选择 Apache-2.0，版权人为池也，根许可证和直接依赖许可证记录已落地；
- 已在只读挂载源码、全新 Maven/npm 缓存的固定 Linux/amd64 容器中完成后端和前端复现；
- 首个公开提交已推送至 [ForgeOJ](https://github.com/chiye0384-dot/ForgeOJ)；真实 [GitHub Actions 运行 36386617957](https://github.com/chiye0384-dot/ForgeOJ/actions/runs/36386617957) 的 `backend` 与 `frontend` 两个 job 均为 `success`，M-1 门禁闭环。

交付物：

- 选择许可证清楚的通用脚手架，或决定使用最小自建骨架；
- 记录上游仓库、commit/tag、许可证和复用边界；
- 初始化 Git 仓库和分支策略；
- 建立后端多模块、前端、部署、文档目录；
- 接入格式检查、基础构建和最小 CI；
- 建立 `.env.example`，确认密钥不进入仓库。

门禁：

- 全新环境可以按 README 构建；
- 许可证检查完成；
- 不含未经授权复制的 OJ 代码；
- 项目结构经过一次人工审阅。

## 4. M0：最小判题纵向切片

目标：尽早验证最难的技术风险，不先做完整页面。

当前进展（`IN_PROGRESS`，2026-09-29）：

- 用户已明确确认启动 M0；开发分支为 `feat/m0-vertical-slice`；
- 已完成 M0 直接依赖与许可证登记、纵向切片设计、四字段消息 JSON Schema、7 张业务表的 V1 Flyway migration，以及固定 digest 的 MySQL/RabbitMQ disposable Compose；
- 已在真实 MySQL 8.4.12 Testcontainer 中验证 migration，证明 API 账号不能读取隐藏测试、Worker 能读取隐藏测试但不能读取用户密码；Compose 两个服务的健康检查也已在隔离临时栈通过；
- 已加入仅由 `dev`/集成测试显式加载的原创开发题目和 BCrypt 预置账号；默认生产 migration 不创建公开默认密码；
- 已在真实 MySQL 8.4.12 Testcontainer 中验证服务端 Session 登录/注销、会话固定攻击防护、CSRF 拒绝、错误密码拒绝，以及公开题目 DTO 不暴露隐藏数据集和判题镜像字段；
- 已在真实 MySQL 8.4.12 Testcontainer 中验证 Submission、JudgeTask 与 Outbox 同事务创建；Outbox 无写权限时三表全部回滚，相同 Idempotency-Key 的顺序和 8 路并发重放只产生一组记录，不同 key 产生独立提交；
- 提交接口只接受规范 UUID、`JAVA_21` 和不超过 65,536 UTF-8 字节的无 package 单文件 `public class Main`；判题快照完整固化，四字段 Outbox payload 不含源码或隐藏数据；
- 已在固定 digest 的 RabbitMQ 4.3.6 Testcontainer 中验证 durable exchange/queue/binding、persistent 四字段 JSON 和 publisher confirm；只有已确认且可路由的消息才设置 `published_at`，删除目标队列后的退回消息保持未发布且不泄露 payload 到业务日志；
- 已在真实 MySQL 8.4.12 与 RabbitMQ 4.3.6 Testcontainers 中验证 Worker 严格四字段契约解析、manual ACK/prefetch 1、task/submission 对应校验、`QUEUED -> RUNNING` 双表事务更新、权限失败回滚，以及重复投递/八路并发只有一个执行权；
- 已验证 Worker 仅为对应的 `RUNNING` task/submission 读取提交时快照和对应版本隐藏测试；读取过程有界展开 gzip，并复核源码、单文件和有序数据集 SHA-256，篡改或错配时 fail closed；
- 已以 `ProcessBuilder(List<String>)` 实现不经过宿主 shell 的 Docker CLI 边界；真实 Docker Engine 测试证明未启动容器具有非 root、禁网、只读根、capabilities 删除、no-new-privileges、CPU/内存/PID 与受限 tmpfs 配置，并在测试后删除；
- 已在真实 Docker Engine 中验证源码和隐藏输入只经 stdin 传输、不进入 CLI 参数；每次提交只编译一次，每个测试点以独立非 root JVM 执行，首个失败后停止；
- 已覆盖 AC、WA、CE、RE、TLE、OLE，实施单用例时间、整次用户执行预算与 stdout/stderr 共享字节上限，并验证所有运行分支强制清理容器；
- 已通过 V2 Flyway migration 在不修改 V1 的前提下加入 OLE；JudgeTask 与 Submission 终态以短事务、行锁和版本条件同时写入，任一更新失败时整体回滚；
- 已在真实 RabbitMQ、MySQL 和 Docker 组合中验证“manual ACK → 幂等领取 → 快照读取 → 沙箱执行 → 终态提交 → ACK”；平台执行故障写 `SYSTEM_ERROR` 且 verdict 为空，重复消息不再次改变终态；
- Worker 消费与沙箱开关仍默认关闭，只在具备受控 Docker 环境时显式启用；
- 已实现仅提交所有者可读的 Submission 结果查询；查询以 `(submission_id, user_id)` 同时过滤，他人、不存在和格式错误的 ID 统一返回 404，响应只包含状态版本、处理状态、verdict 和脱敏诊断等白名单字段；
- 已实现 M0 前端工作台和 `/api` 开发代理；Vitest 以模拟 API 响应覆盖匿名会话、CSRF 登录、公开题目、带 Idempotency-Key 的 Java 21 提交、轮询至终态并停止，以及不显示未声明隐藏字段；
- 当前下一步是组合验证真实浏览器/API/RabbitMQ/Worker/Docker/MySQL 从提交到最终结果的完整链路；
- 登录、公开题目读取、提交事务、RabbitMQ 发布、Worker 到终态、所有者结果查询及模拟 API 驱动的前端轮询是已验证的局部切片；真实组合闭环和固定 Linux 复现仍未完成，因此 M0 整体保持 `IN_PROGRESS`。

最短流程：

1. 使用预置测试账号完成最小登录；
2. 浏览一道人为内置的题；
3. 提交 Java 21 `Main.java`；
4. API 创建 Submission 与 Outbox；
5. RabbitMQ 投递；
6. Worker 创建受限 Docker 容器；
7. 编译、运行测试并写回结果；
8. 页面轮询得到结果。

门禁：

- AC、WA、CE、RE、TLE 至少各有自动化样例；
- 重复投递不会重复产生业务结果；
- API 进程没有 Docker 权限；
- 超时后容器和临时文件会清理；
- 固定 Linux 环境可以复现。

暂不做：精美 UI、ES、完整班级、社区和复杂管理员页面。

M0 的登录只用于打通受保护提交链路，可使用预置账号和最小会话；注册、邮箱验证、找回密码、多会话和完整安全闭环在 M2 实现。

## 5. M1：可靠异步判题与安全边界

目标：把“能跑”提升为“故障下仍可解释和恢复”。

范围：

- 完整处理状态与判题结果；
- 编译一次、逐测试启动 JVM、首错停止；
- 自测队列与正式提交队列；
- 用户并发和排队限制；
- Outbox 发布重试、手动 ACK、幂等、死信；
- Worker 中断与 ACK 丢失恢复；
- WebSocket 通知和轮询兜底；
- Docker 非 root、禁网、只读根、资源与输出限制；
- 固定镜像 digest 和 judge_version；
- 沙箱恶意测试集。

门禁：

- 消息重复、乱序边界和 Worker 崩溃测试通过；
- SYSTEM_ERROR 不会被记录为用户 RE；
- 隐藏数据和基础设施凭证不可从用户程序读取；
- 每个提交可通过 ID 串联 API、消息、Worker 和结果日志。

## 6. M2：题库、账号与学习主流程

目标：完成普通学习者可用的核心产品。

范围：

- 注册、邮箱验证、登录、找回密码、多会话与注销会话；
- 公共题库、详情、标签与难度；
- 题目草稿、测试数据、私有参考程序；
- 公共审核版本和题目归档；
- 官方题解及公共查看规则；
- 官方题单、进度条；
- 个人私有题单；
- 服务端代码草稿和冲突提示；
- 自测与正式提交历史。

门禁：

- 注册到 AC 的端到端测试通过；
- 题目版本变化不会篡改历史提交；
- 普通用户无法读取隐藏测试和私有参考程序；
- 个人题单只对本人可见；
- 账号停用会使全部旧会话失效。

## 7. M3：班级与作业

目标：形成区别于纯刷题站的教学闭环。

范围：

- 班级创建、邀请码、OWNER/ASSISTANT/MEMBER；
- 成员退出、移除、恢复、所有权转让和归档；
- 班级私有题；
- 作业草稿、定时开始、截止和迟交策略；
- 成员快照和 PRECOMPLETED；
- 班级题解开放策略；
- 教师仅查看与本班作业相关的提交。

门禁：

- 班级作业端到端测试通过；
- 完整权限矩阵测试通过；
- 后加入、退出、移除和归档等边界有测试；
- 教师不能查看成员无关的私人练习代码。

## 8. M4：管理后台、搜索与降级

目标：补足真实系统所需的治理和运维能力。

范围：

- 独立管理员认证；
- CONTENT_REVIEWER、OPS_ADMIN、SUPER_ADMIN；
- 公共题审核；
- 异常任务与死信幂等重试；
- 管理员账号和审计日志；
- Redis 缓存、会话和限流降级；
- Elasticsearch 公共题全文搜索；
- ES 版本同步、死信、重建和 MySQL 回退；
- 最小 Prometheus/Grafana 面板。

门禁：

- 普通用户访问后台接口返回 403；
- 三个后台角色的越权操作均被拒绝；
- Redis、ES 和 RabbitMQ 故障演练通过；
- 索引删除后可从 MySQL 重建；
- 管理操作可追溯到管理员、对象、时间和原因。

## 9. M5：Linux 验收与小范围上线

目标：证明项目不只在开发机运行。

范围：

- 单机 Docker Compose；
- Nginx 与 HTTPS；
- Flyway 空库安装和版本升级；
- 配置、密钥、健康检查和冒烟测试；
- 阿里云或同类 Linux ECS 小范围试运行；
- MySQL 每日加密备份和异地副本；
- 恢复演练和应用回滚演练；
- 固定环境性能测试；
- 安全边界和运维手册。

门禁：

- 全新 Linux 主机可按文档部署；
- 备份能恢复到全新数据库；
- 应用镜像可以回滚；
- 性能报告记录环境、数据集、commit 和原始结果；
- 至少完成一轮受控真实用户试用与问题记录。

## 10. V1.0 Release：简历核心版

只有 M0 至 M5 全部门禁通过，才创建 V1.0 release。此时从证据矩阵中挑选三到五条已验证亮点写入简历。

V1.0 不因以下事项推迟：

- 用户题解互动；
- 综合论坛；
- 公开个人题单；
- 多语言；
- 微服务或 Kubernetes。

## 11. V1.1：题解社区版

范围：

- 从本人 AC 提交发布题解；
- 只读已验证代码；
- 标题模板兜底，思路和复杂度选填；
- 点赞、收藏；
- 三层评论和题目关联讨论；
- 举报、软删除、恢复和作者通知；
- 自助注销及匿名化。

门禁：

- 修改已验证代码必须重新 AC；
- 删除题解不影响原提交；
- 举报数量不会自动定罪；
- 内容审核访问遵守最小权限；
- 注销后身份信息按规则删除或匿名化。

## 12. V2.0 候选范围

V2.0 不是当前承诺。候选项仅包括：

- 可关联或不关联题目的简单学习帖子；
- 适合长讨论的评论结构；
- 用户主动发布的公开个人题单；
- 公开题单的收藏、复制、举报和下架；
- 公开内容在个人主页中的展示。

个人题单创建时默认私有。若用户主动公开，仍须支持取消公开并恢复私有，且不得通过公开题单泄露班级私有题或其他非公开题。是否进入 V2.0，要根据 V1.1 真实使用反馈另行决定。

## 13. 长期不做或需重新立项

- 多语言和多 JDK 版本；
- Special Judge、交互题和部分分；
- 竞赛与全站排行榜；
- 抄袭检测；
- 在线多文件 IDE；
- 大规模公网任意代码执行；
- AI 自动写题解或自动审核；
- 复杂社交、私信、关注和推荐；
- 多机房、高可用和 Kubernetes。

这些内容不是“做完 V2 自动增加”，而是需要新的需求访谈、威胁评估和资源预算。
