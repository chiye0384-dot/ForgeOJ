# ForgeOJ 代码归属与复用边界

> 更新日期：2026-09-29
> 当前范围：M-1 已验证；M0 为 `IN_PROGRESS`。局部自动化及 Windows + Docker Desktop 上的真实浏览器到判题终态 AC 闭环已通过，固定 Linux 完整重放和最终门禁仍未通过。

## 1. 生成与第三方部分

- `mvnw`、`mvnw.cmd`、`.mvn/wrapper/`：由 Spring Initializr 生成，其中 Maven Wrapper 来自 Apache Maven Wrapper 3.3.4；`mvnw.cmd` 另含 ForgeOJ 记录的一处 Windows 空值保护补丁。
- 两个 Spring Boot 启动类与最初的空上下文测试：来自 2026-09-27 的 Spring Initializr 生成产物。
- `frontend/` 的基础构建、TypeScript、Router、Vitest、ESLint、OxcLint 和 Prettier 配置：由 create-vue 3.22.3 模板生成后修改。
- Spring Boot、Spring Security、Spring AMQP、Flyway、Testcontainers、MyBatis、Vue、Vite 及其他 Maven/npm 包是第三方依赖，不是 ForgeOJ 自行实现的能力。加入依赖不能被表述为已经自行实现认证、消息可靠性、迁移或集成测试。

精确版本、commit、许可证和生成产物哈希见 `docs/UPSTREAM-AND-LICENSE.md` 与 `THIRD_PARTY_NOTICES.md`。

## 2. ForgeOJ 在 M-1 完成的改造

- 新建 Maven 根聚合/父 POM，将 API 与 Judge Worker 组织为两个互不依赖的可执行模块。
- 锁定 JDK 21、Spring Boot 4.1.1、Maven 3.9.14 和官方 MyBatis Starter 4.1.0。
- 将 H2 限定在测试作用域，Worker 明确设为 non-web；未引入任何虚构 Mapper 或业务实体。
- 修补 Maven Wrapper 3.3.4 在 Windows 上对空 `Target[0]` 直接索引而无法启动的问题；补丁依据与退役条件记录在 `docs/UPSTREAM-AND-LICENSE.md`。
- 从前端生成结果移除 Vue DevTools 及演示页文案，增加可重复的 `npm run verify` 检查链。
- 新建项目级 CI、`.env.example`、`deploy/` 边界说明、决策和归属文档。

## 3. ForgeOJ 必须自行设计和验证的业务

以下内容不来自当前脚手架，也不会从现有 OJ 或大型后台项目复制：

- 题目、测试数据、参考程序和判题版本模型；
- Submission、JudgeTask、Outbox 状态与转移；
- RabbitMQ 契约、幂等、重试、死信和故障恢复；
- Judge Worker 与 Docker 沙箱适配器；
- 隐藏测试、私有参考程序、班级作业和管理后台的权限边界；
- Redis/Elasticsearch 的一致性、降级与重建；
- 性能、安全和 Linux 故障演练证据。

M0 当前自行设计和实现的边界包括：预置账号的登录会话与权限规则、Flyway migration、Submission/JudgeTask/Outbox SQL 与状态机、四字段任务消息契约、Worker 幂等领取、不可变快照与隐藏测试完整性校验、Docker CLI 沙箱适配与清理、M0 前端工作台，以及相应验收测试。其中 ForgeOJ 已自行实现并局部验证 V1/V2 migration、dev/test seed、数据库账号权限、服务端 Session/CSRF 登录边界、公开题目字段白名单、Submission/JudgeTask/Outbox 原子写入与请求幂等、publisher confirm 后才标记已发布的 RabbitMQ Outbox 循环、Worker 严格消息校验、双表原子幂等领取、真实 Docker 执行、六种 M0 verdict 映射、双表终态事务写回、写回后 ACK、仅提交所有者可读且字段/诊断受限的结果查询，以及前端登录、读题、提交和终态轮询。2026-09-29 又在 disposable Windows 开发栈真实跑通浏览器 AC 主链路；固定 Linux 完整重放仍只能描述为“正在验证”。

`db/devdata/R__seed_m0_development_data.sql` 中的“两数之和”题面、样例和隐藏测试数据是为 ForgeOJ M0 编写的最小原创开发数据，不来自第三方题库。它只在 `dev` profile 或显式集成测试位置中加载，不属于默认生产 migration。Spring Security、BCrypt、MyBatis 和 Flyway 仍是第三方框架能力；ForgeOJ 自有部分是配置、数据模型、Mapper/DTO、接口边界和相应测试，不能把框架本身表述为自行实现。

## 4. 明确排除

- 没有引入 RuoYi-Vue-Plus、ruoyi-vue-pro 或 JHipster 代码；
- 没有引入 CodeJudge 或其他 OJ 的代码、页面、题面、测试数据或性能数字；
- 没有引入 MyBatis-Plus 或 PageHelper；
- 没有创建泛化 `common` 模块。M0 的稳定跨进程消息已经以根目录中立 JSON Schema 表达；API 与 Worker 各自维护 DTO，不通过共享 Maven 代码模块形成耦合。

## 5. 许可证状态

池也拥有版权并有权许可的 ForgeOJ 自有源代码采用 Apache License 2.0，版权声明为 `Copyright 2026 池也`，见根 `LICENSE` 与 `NOTICE`。

这个根许可证不改变归属边界：Spring Initializr/create-vue 生成文件、Maven Wrapper、本仓库保留的其他上游代码以及 Maven/npm 依赖继续遵循各自许可证；题目、题解、测试数据和用户提交内容也不会自动改用 Apache-2.0。来源、修改点和发布义务继续记录在 `THIRD_PARTY_NOTICES.md`、`licenses/` 与直接依赖清单中。
