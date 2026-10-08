# ForgeOJ 上游与许可证策略

2026-10-08 M4独立管理身份单元：新增管理账号/会话/事务审计/CLI、V18、独立后台页面和验收工具为ForgeOJ自有实现，池也/Apache-2.0。复用既有 Spring Security/BCrypt、MyBatis、JDBC、Nimbus 与标准库，没有新增第三方依赖或复制外部管理模板。公开密码、账号和源码只属于一次性fixture；生产没有默认管理员、普通邮件找回或Web初始化。

2026-10-08 M3总门禁闭环：只新增ForgeOJ自有集成测试及只读证据/清理核查脚本，池也/Apache-2.0。Python汇总只用标准库，没有新第三方依赖或复制题目/程序；生产实现、迁移和grants未变。完整M-1至M3 VERIFIED，未正式发布，以下日期记录保留历史。

2026-10-08 教学记录单元：新增API查询、前端只读页面、测试与验收脚本均为ForgeOJ自有实现，延续池也/Apache-2.0；没有新增第三方依赖、迁移或复制外部程序/题目。验收中的班级题和Java程序为原创一次性fixture，公开证据只含fixture页面、资源ID、摘要与状态，无真实凭据或用户数据。完整M2 VERIFIED、M3单元进度见当前交接，以下状态段落为历史。

> 当前状态：M-1、M0 与 M1 均为 `VERIFIED`，M1 15 项门禁证据见 `M1-GATE-AUDIT.md`；M1 最终测试补齐与范围校正未增加依赖或复制外部代码。M2 普通账号单元现为 [VERIFIED](M2-ACCOUNTS-VALIDATION.md)，完整 M2 保持 `IN_PROGRESS`，增量 JOSE/Nimbus 构件见 U-009。M0 直接依赖、消息契约与 Docker 客户端边界已登记。ForgeOJ 自有代码采用 Apache-2.0，版权人为池也；尚无正式发布。
> 本文定义代码进入仓库之前必须完成的检查，不能被理解为已经获得某个项目的授权。

## 1. 核心原则

- Star 数、教程热度和能够下载，不代表允许复制。
- 没有明确许可证的仓库只能学习思想，不能复制代码、页面、题面、测试或文档。
- 修改开源代码不会自动把原作者代码变成 ForgeOJ 原创。
- AI 帮助生成或改写代码，不会消除上游许可证和来源义务。
- 先检查许可证，再让代码进入仓库；不能等准备开源时补做。
- 根许可证已在实际依赖和脚手架确定后选择为 Apache-2.0；以后改变依赖或发布方式仍须重新检查兼容性。

## 2. 复用边界

### 2.1 可以考虑复用

前提是许可证兼容、来源可追溯且确实节省低价值工作：

- Spring Boot 通用工程骨架；
- 登录或后台的通用页面布局；
- 统一响应、异常处理和参数校验设施；
- 通用审计、日志、数据库迁移和 Docker Compose 结构；
- 成熟、版本明确的第三方库。

### 2.2 ForgeOJ 必须自行设计和验证

- 题目与判题版本模型；
- Submission、JudgeTask 和 Outbox 状态；
- RabbitMQ 消息契约、幂等、重试和死信；
- Judge Worker；
- Docker 沙箱控制与安全测试；
- 隐藏测试、私有参考程序和官方题解边界；
- 班级、作业和资源级权限；
- 公共题审核与异常任务运维闭环；
- Redis/Elasticsearch 的一致性与降级；
- 简历性能与故障证据。

完整 OJ 项目不能作为“换名称和页面”的底座。可以阅读其公开设计，但若复制受许可保护的实现，必须明确记录，且会削弱核心能力的本人所有权。

## 3. 上游候选准入检查

每个候选都必须回答：

1. 仓库的准确 URL 是什么；
2. 采用哪个不可变 commit 或正式 tag；
3. 根目录是否有明确许可证；
4. 许可证是否覆盖拟复用的文件；
5. 是否存在 NOTICE、第三方子模块或额外条款；
6. 是否允许修改、再分发和计划中的使用方式；
7. 是否要求公开源码、保留声明或使用相同许可证；
8. 是否适配决策日志记录的当前后端基线（现为 JDK 21 / Spring Boot 4.1.1）；
9. 是否含有未知来源的复制代码或素材；
10. 移除上游业务后，留下的通用能力是否仍值得复用；
11. 团队是否能解释和维护保留的代码；
12. 是否有更小、许可证更简单的替代方案。

许可证判断不确定时，不引入；必要时寻求专业法律意见。本文不是法律意见。

## 4. 当前上游登记表

| 名称 | 仓库 | commit/tag | 许可证 | 拟复用范围 | 决定 | 核验日期 | 证据 |
|---|---|---|---|---|---|---|---|
| Spring Initializr 在线生成服务 | [start.spring.io](https://github.com/spring-io/start.spring.io) / [initializr](https://github.com/spring-io/initializr) | 核验日仓库快照：`start.spring.io@29903a3fd5ccdb6f8623111871ab2a9b804cb160`；`initializr@d445a056ced904ba5098df1e75b75bebf9840687` | Apache-2.0 | 生成两个最小 Maven 工程；由 ForgeOJ 重组为根聚合工程 | `ADOPTED` | 2026-09-27 | 在线服务未暴露部署 commit；生成请求和 ZIP SHA-256 见 U-001 |
| Spring Boot | [spring-projects/spring-boot](https://github.com/spring-projects/spring-boot) | `v4.1.1` → `6fdf67ea1552691e932604d4bf67a5e08ff0b0ea` | Apache-2.0 | 后端框架与 BOM | `ADOPTED` | 2026-09-27 | [4.1.1 发布说明](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/) |
| Spring Security | [spring-projects/spring-security](https://github.com/spring-projects/spring-security) | `7.1.1` → `a825937b8175ee85872c49d9c7fc25eea8cff991` | Apache-2.0 | API 认证与安全测试；M2 增量 JOSE 模块 | `ADOPTED` | 2026-09-28；JOSE 2026-10-02 | [7.1.1 release](https://github.com/spring-projects/spring-security/releases/tag/7.1.1)；U-009 固定 POM/构件 |
| Nimbus JOSE+JWT | [connect2id/nimbus-jose-jwt](https://bitbucket.org/connect2id/nimbus-jose-jwt) | Maven 发布 `10.9.1`，POM SCM tag `10.9.1`；固定构件 SHA-256 见 U-009 | Apache-2.0 | Spring Security JOSE 传递的 JWT 签名/解析；不复制上游源码 | `ADOPTED_TRANSITIVE_RUNTIME` | 2026-10-02 | [10.9.1 固定 POM](https://repo.maven.apache.org/maven2/com/nimbusds/nimbus-jose-jwt/10.9.1/nimbus-jose-jwt-10.9.1.pom) |
| Spring AMQP | [spring-projects/spring-amqp](https://github.com/spring-projects/spring-amqp) | `v4.1.1` → `cc1c35f30c3e2f06af1c9f258d71b0255e9e5ddc` | Apache-2.0 | API/Worker RabbitMQ 传输 | `ADOPTED` | 2026-09-28 | [4.1.1 release](https://github.com/spring-projects/spring-amqp/releases/tag/v4.1.1) |
| Jackson Databind | [FasterXML/jackson-databind](https://github.com/FasterXML/jackson-databind) | `jackson-databind-3.1.5` | Apache-2.0 | Worker 严格解析四字段任务 JSON；通过 Boot Starter 引入 | `ADOPTED` | 2026-09-29 | [Maven Central POM](https://repo.maven.apache.org/maven2/tools/jackson/core/jackson-databind/3.1.5/jackson-databind-3.1.5.pom) |
| Flyway | [flyway/flyway](https://github.com/flyway/flyway) | `flyway-12.4.0` → `be256634108dba6f760dbb0604e3421f12f2c431` | Apache-2.0 | API 唯一生产迁移执行者与 MySQL 模块 | `ADOPTED` | 2026-09-28 | [12.4.0 release](https://github.com/flyway/flyway/releases/tag/flyway-12.4.0) |
| Testcontainers Java | [testcontainers/testcontainers-java](https://github.com/testcontainers/testcontainers-java) | `2.0.5` → `5c448202ac69d073f746433d3e79f6a2bf0ec585` | MIT | MySQL、RabbitMQ 与 JUnit 集成测试；test scope only | `ADOPTED_TEST_ONLY` | 2026-09-28 | [2.0.5 release](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.5) |
| Docker CLI | [docker/cli](https://github.com/docker/cli) | 本机验证版本 `29.8.0`；源码 release line `v29.8.0` | Apache-2.0 + NOTICE | Worker 通过外部 CLI 控制 M0 一次性容器；不复制或分发 CLI | `ADOPTED_EXTERNAL_RUNTIME` | 2026-09-28 | [官方仓库与许可证](https://github.com/docker/cli) / [Engine 29.8.0 release](https://github.com/moby/moby/releases/tag/docker-v29.8.0) |
| MySQL Community Server image | [Oracle Container Registry](https://container-registry.oracle.com/) | `8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be`（linux/amd64） | GPLv2；镜像内第三方组件许可证独立 | M0 disposable 数据库，不复制或再分发镜像 | `ADOPTED_EXTERNAL_RUNTIME` | 2026-09-28 | [8.4.12 release notes](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-12.html) / [官方 Docker 指南](https://dev.mysql.com/doc/refman/8.4/en/docker-mysql-getting-started.html) |
| RabbitMQ management image | [Docker Official Image](https://hub.docker.com/_/rabbitmq/) | `4.3.6-management@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95`（linux/amd64） | RabbitMQ Server/核心插件主要为 MPL-2.0；镜像内组件许可证独立 | M0 disposable broker，不复制或再分发镜像 | `ADOPTED_EXTERNAL_RUNTIME` | 2026-09-28 | [RabbitMQ 下载](https://www.rabbitmq.com/docs/download) / [上游 LICENSE](https://github.com/rabbitmq/rabbitmq-server/blob/main/LICENSE) |
| Testcontainers Ryuk | [testcontainers/moby-ryuk](https://github.com/testcontainers/moby-ryuk) | `0.14.0` → `b3726af` | MIT | Testcontainers 自动启动的 test-only 清理辅助镜像 | `ADOPTED_TRANSITIVE_TEST_RUNTIME` | 2026-09-28 | [0.14.0 release](https://github.com/testcontainers/moby-ryuk/releases/tag/0.14.0) |
| Eclipse Temurin Java 21 image | [Docker Official Image](https://hub.docker.com/_/eclipse-temurin) / [adoptium/containers](https://github.com/adoptium/containers) | `21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438`（OCI index；linux/amd64 child `sha256:4cfc63a7118da9267c17e2c988e1599d83d5d64a3832a760f0e77c7e8f6b29f7`）；containers revision `33e90a8e65658c51e78419e09f12fb86de0fa3e3` | Temurin 二进制 GPLv2 with Classpath Exception；Dockerfile 仓库 Apache-2.0；镜像内 Ubuntu/第三方组件许可证独立 | M0 Java 21 判题外部运行时；不复制、归档或制作派生镜像 | `ADOPTED_EXTERNAL_RUNTIME` | 2026-09-28 | [Temurin 许可证 FAQ](https://adoptium.net/docs/faq/) / [Official Image](https://hub.docker.com/_/eclipse-temurin) / [containers LICENSE](https://github.com/adoptium/containers/blob/master/LICENSE) |
| Maven Wrapper | [apache/maven-wrapper](https://github.com/apache/maven-wrapper) | `maven-wrapper-3.3.4` → `524486aff97d0748926a977665d5befb3251ff17` | Apache-2.0 + NOTICE | 保留 `mvnw`、`mvnw.cmd` 和 wrapper 配置，固定 Maven 3.9.14；Windows 脚本带一处空值保护 | `ADOPTED_WITH_PATCH` | 2026-09-27 | 许可证与 NOTICE 已保存到 `licenses/`；补丁依据 [issue #395](https://github.com/apache/maven-wrapper/issues/395) / [draft PR #416](https://github.com/apache/maven-wrapper/pull/416) |
| MyBatis Spring Boot Starter | [mybatis/spring-boot-starter](https://github.com/mybatis/spring-boot-starter) | `mybatis-spring-boot-4.1.0` → `5f1d7e3e01054a663cd1ae8b37141fe88c015f58` | Apache-2.0 | Maven 直接依赖；原生 MyBatis 集成 | `ADOPTED` | 2026-09-27 | [4.1.0 发布](https://github.com/mybatis/spring-boot-starter/releases/tag/mybatis-spring-boot-4.1.0) / [Maven Central](https://central.sonatype.com/artifact/org.mybatis.spring.boot/mybatis-spring-boot-starter/4.1.0) |
| create-vue | [vuejs/create-vue](https://github.com/vuejs/create-vue) | `v3.22.3` → `10d767adf0fad58b500e0f2e2149266ef827100e` | 生成器 MIT；模板/生成文件 CC0-1.0 | Vue 3 + TypeScript + Router + Vitest + ESLint + Prettier 最小前端 | `ADOPTED` | 2026-09-27 | 精确许可证已保存到 `licenses/`；生成命令见 U-002 |
| RuoYi-Vue-Plus | [dromara/RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) | `6.X@24b6b40adea1d0ce7ba9fd218e131b9af5acaea0` | MIT | 仅评估，未复制代码 | `REJECTED` | 2026-09-27 | 虽已是 JDK 21 / Boot 4.1.1，但围绕 MyBatis-Plus、多数据源和完整后台模块组织，超出 ForgeOJ 最小起点 |
| ruoyi-vue-pro | [YunaiV/ruoyi-vue-pro](https://github.com/YunaiV/ruoyi-vue-pro) | `master@1697112f1164b206aeb031493980f5cb3a6e2cd5` | MIT | 仅评估，未复制代码 | `REJECTED` | 2026-09-27 | 包含商城、CRM、ERP、支付、AI、IoT 等大量无关模块，删改与学习成本高于最小骨架 |
| JHipster Generator | [jhipster/generator-jhipster](https://github.com/jhipster/generator-jhipster) | `main@be3cfa077a9099186b5773bf7452c500adb917d5`（`package.json` 9.4.0） | Apache-2.0 | 仅评估，未生成或复制代码 | `REJECTED` | 2026-09-27 | 生成器平台和约定范围大于 ForgeOJ 双进程最小起点，降低所有权的可解释性 |
| PageHelper | [pagehelper/Mybatis-PageHelper](https://github.com/pagehelper/Mybatis-PageHelper) | 未选版本 | 未引入，尚未产生许可证义务 | M2 真实分页需求出现后再评估 | `DEFERRED` | 2026-09-27 | 见 D-032 |

上表中的 `REJECTED` 只表示不适合作为 ForgeOJ 底座，不表示对上游项目质量的否定。本仓库没有复制它们的代码、页面、素材或业务模块。

### U-001：Spring Initializr 后端生成产物

- 官方服务：`https://start.spring.io/starter.zip`
- 生成日期：2026-09-27
- 可追溯说明：在线服务没有在产物中暴露其部署 commit；上表两个 commit 是生成日对官方仓库的核验快照，不冒充服务端部署身份。对本次实际产物，请求参数与 ZIP SHA-256 是直接证据。
- 共同参数：`type=maven-project`、`language=java`、`bootVersion=4.1.1`、`groupId=com.forgeoj`、`packaging=jar`、`javaVersion=21`
- API 参数：`baseDir/artifactId/name=forgeoj-api`、`packageName=com.forgeoj.api`、`dependencies=web,mysql,h2`
- API ZIP SHA-256：`BEE838C57920952BED60183B52DCB25E9734D23A9157DA0565E96F7AB1CE893B`
- Worker 参数：`baseDir/artifactId/name=forgeoj-judge-worker`、`packageName=com.forgeoj.worker`、`dependencies=mysql,h2`
- Worker ZIP SHA-256：`33DC75E55A4F3AD0BBEC731AA001C9593BF57866022C425FD890912EE7AD4769`
- 原样保留：两个启动类、上下文启动测试和 Maven Wrapper 脚本的核心结构。
- 修改：由 ForgeOJ 新建根聚合 POM，两个子 POM 改为继承根工程；移除 H2 Console，将 H2 限制为 test scope；显式加入 MyBatis Starter 4.1.0；Worker 固定为 non-web。
- Windows Wrapper 补丁：`mvnw.cmd` 不再直接索引可能为 `$null` 的 `.m2` 目录 `Target[0]`，而是先验证属性、值和首元素，再决定是否按符号链接路径解析。该补丁只影响 Wrapper 缓存目录定位，不改变 Maven 版本或构建逻辑；上游 3.3.4 已有同根因 issue，修复 PR 在核验日仍为 draft，后续升级 Wrapper 时应重新检查并删除已上游化的本地补丁。
- Maven 发行包完整性：`distributionSha256Sum=55fadd669532a3205d5db95f490bf13971d8b0843526f407f29db0e61f074ab3`；本地补丁后 `mvnw.cmd` SHA-256 为 `0E085EB62B8EB51484BE2983F0C24D0D49C31F838869AD1A9F4D2105A8351B11`。
- 排除：生成的 `HELP.md`、子工程重复 wrapper/git 文件和非 M-1 依赖。
- 验证：当前 Windows 环境中 `.\mvnw.cmd --batch-mode clean verify` 通过，两个上下文测试共 2 个。
- 边界：测试用 H2 只验证空骨架装配，不验证 MySQL 语义。

### U-002：create-vue 前端生成产物

- 生成命令：`npx --yes create-vue@3.22.3 frontend --typescript --router --vitest --eslint --prettier --bare`
- 保留：Vue 3、TypeScript、Vue Router、Vitest、ESLint、OxcLint、Prettier 和 Vite 最小配置。
- 修改：将包名改为 `forgeoj-frontend`，删除生成器默认加入的 Vue DevTools 插件，用中性的 ForgeOJ 骨架状态替换演示页，增加无写入的 `verify` 检查链。
- 排除：Pinia、Cypress/Playwright、JSX、实验性开发工具和业务 UI。
- 验证：`npm install` 审计为 0 个已知漏洞；`npm run verify` 在 Node 24.14.1 / npm 11.11.0 下通过。

### U-003：MyBatis 与 PageHelper 边界

- 直接依赖仅为官方 MyBatis Spring Boot Starter 4.1.0，根 POM 显式锁定版本；Spring Boot BOM 不代替这一锁定。
- MyBatis-Plus 没有引入，也不会从被拒绝的后台脚手架中间接复用。
- PageHelper 是 `DEFERRED`：当前 `pom.xml` 不含它；M2 重新评估时必须记录当时的精确版本、commit/tag、许可证与 Boot 4.1 兼容测试。

2026-10-03 首个题库分页的专门评估见 D-032 与 `M2-REMAINING-IMPLEMENTATION.md`：当前固定排序、显式 COUNT 和页内标签批量读取由 MyBatis 原生 SQL 表达，未引入 PageHelper，故未选择其版本或产生新增许可证义务。后续决定采用时仍执行上述依赖门禁。

### U-004：M0 后端直接依赖门禁

- API 运行依赖新增 Spring Security、Spring AMQP、Spring Boot Flyway Starter 与 `flyway-mysql`；Worker 运行依赖新增 Spring AMQP 与 Spring Boot Jackson Starter，后者只用于严格解析四字段任务 JSON。
- API 测试依赖新增 `spring-security-test`；API 与 Worker 测试依赖新增 Spring Boot Testcontainers 以及 Testcontainers JUnit Jupiter、MySQL、RabbitMQ 模块。
- 解析版本为 Spring Security 7.1.1、Spring AMQP 4.1.1、Jackson Databind 3.1.5、Flyway 12.4.0、Testcontainers 2.0.5，均由 Spring Boot 4.1.1 BOM 管理，子 POM 不重复写版本。
- 上述项目源码没有复制进 ForgeOJ；它们提供第三方框架或测试能力，不能被表述为 ForgeOJ 自行实现了认证、消息可靠性、迁移或集成测试。
- M0 不引入 OAuth/JWT、Spring Session/Redis、Spring Cloud Stream、Spring Retry、PageHelper 或 MyBatis-Plus。
- M0 不把 `docker-java` 作为生产沙箱客户端；Testcontainers 2.0.5 在 test scope 传递使用的 `docker-java` 只服务 disposable 集成测试，仍属于第三方传递依赖。
- 依赖声明后的 Windows/JDK 21 构建验证：`./mvnw --batch-mode clean verify` 的两个模块上下文测试均通过；这只关闭依赖装配门禁，不代表 M0 业务链路完成。

### U-005：Docker CLI 外部运行时边界

- M0 Worker 通过 ForgeOJ 自有 `SandboxRuntime`/`DockerCommandExecutor` 适配器调用宿主机 Docker CLI，不复制、修改或随 ForgeOJ 分发 Docker CLI 或 Docker Desktop。
- 生产适配器使用 `ProcessBuilder(List<String>)`，不经过 shell、不拼接用户文本；Docker 可执行文件、固定镜像 digest 和资源限制来自可信配置。
- 本机已验证 Docker CLI/Engine 29.8.0、Linux containers 和 Docker Desktop 4.92.0。Docker Desktop 是开发机外部工具，适用其单独订阅条款，不能因为 `docker/cli` 源码采用 Apache-2.0 就把 Desktop 写成同一许可证。
- 最终 M0 功能与安全证据仍必须在固定 Linux 环境复现，并记录 CLI、Engine、API 版本与镜像 digest。

### U-006：M0 disposable 基础设施镜像

- MySQL 使用 Oracle 厂商官方 Community Server `8.4.12`，固定 `linux/amd64` digest `sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be`。8.4.12 是针对 MySQL Server Docker 镜像的安全更新，因此不退回当时 Docker Hub Official Image 仍停留的 8.4.11。
- RabbitMQ 使用 Docker Official Image `4.3.6-management`，固定 OCI index digest `sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95` 并显式指定 `linux/amd64`。
- Testcontainers 2.0.5 自动使用 `testcontainers/ryuk:0.14.0` 清理 disposable 资源；Ryuk 是 MIT 许可的 test-only 辅助镜像，不是 ForgeOJ 生产服务。
- Compose 和 integration test 只从 registry 拉取镜像，不向仓库提交镜像 tar、镜像层，也不通过 `FROM` 创建 MySQL/RabbitMQ 派生镜像。
- MySQL Community Server 的 GPLv2、RabbitMQ 的 MPL-2.0 以及镜像内基础系统/第三方组件许可证不会重新许可 ForgeOJ 自有源码；若未来分发镜像归档或派生镜像，必须重新审计完整分发义务。
- digest 固定保证重拉内容一致，也意味着安全更新不会自动进入；升级必须通过显式依赖变更，同步更新本表、Compose、测试与验证证据。
- Windows/Docker Desktop 实测：MySQL 8.4.12 Flyway migration 和账号读取边界测试通过；独立 Compose 项目中 MySQL 与 RabbitMQ 均达到 healthy，MySQL 三账号初始化和 RabbitMQ `/forgeoj` vhost 已核对，随后 disposable 容器、网络与卷已清理。

### U-007：M0 Java 21 判题运行时镜像

- M0 开发种子中的判题版本固定 Docker Official Image `eclipse-temurin:21.0.12_8-jdk-jammy`，OCI index digest 为 `sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438`；当前目标 `linux/amd64` child manifest 为 `sha256:4cfc63a7118da9267c17e2c988e1599d83d5d64a3832a760f0e77c7e8f6b29f7`。
- 对应 `adoptium/containers` 来源 revision 记录为 `33e90a8e65658c51e78419e09f12fb86de0fa3e3`。Temurin 二进制采用 GPLv2 with Classpath Exception；官方容器 Dockerfile 仓库采用 Apache-2.0，Ubuntu 基础系统和其他镜像组件继续适用各自许可证。
- ForgeOJ 当前只让 Docker Engine 从 registry 拉取该镜像作为用户代码的外部 Java 21 运行时，不把镜像层、tar 归档或派生镜像提交/分发到仓库，也不把 JDK 或 Dockerfile 表述为 ForgeOJ 自研。
- digest 固定用于重现提交时的判题环境，不代表镜像永远安全；升级时必须创建新的 judge version，并同步审计 tag、index/平台 digest、来源 revision、许可证和回归结果，不能静默改写历史提交环境。

### U-008：M1 原生 WebSocket 通知依赖

- 2026-09-30 在 API POM 加入 `org.springframework.boot:spring-boot-starter-websocket:4.1.1`，不添加 STOMP/SockJS 或前端 socket 包，也不向 Worker 添加 WebSocket 依赖。
- 来源是现有 Spring Boot `v4.1.1`（上表固定 commit）与 Spring Framework `v7.0.9` 发行构件；Maven 实际解析 `spring-boot-websocket:4.1.1`、`spring-websocket:7.0.9`、`spring-messaging:7.0.9`。已有 WebMVC/Tomcat 依赖含 `tomcat-embed-websocket:11.0.24`。
- 本机缓存的 Starter、Framework 与 Tomcat POM 声明 Apache-2.0；版本、许可证与传递依赖仍需纳入首次 Release 的完整发布审计，不由根许可证重新许可。
- 核验来源：[Starter 固定 POM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-websocket/4.1.1/spring-boot-starter-websocket-4.1.1.pom)、[Framework 固定 POM](https://repo.maven.apache.org/maven2/org/springframework/spring-websocket/7.0.9/spring-websocket-7.0.9.pom)、[Spring WebSocket 官方文档](https://docs.spring.io/spring-framework/reference/web/websocket/server.html)、[Spring Security 官方边界说明](https://docs.spring.io/spring-security/reference/servlet/integrations/websocket.html)。当前文档可能随上游升级，实际采用版本以固定构件为准。
- 不复制上游源码、业务模块或模板；Spring 提供握手、传输和并发发送包装。ForgeOJ 自行实现同源/所有者授权、三字段投影、会话复核、有界订阅、单调快照、终态清理、轮询/重连及测试。Tomcat 1 秒同步发送超时属性已对固定 11.0.24 sources JAR 核验，不宣称跨容器通用。
- 构建与运行验证见 `M1-NOTIFICATION-VALIDATION.md`；不把依赖装配成功单独视为通知或 M1 已验证。

2026-10-01 的关联日志与真实进程故障阶段均未增加直接依赖或复制外部源码。子 JVM 控制使用既有 JDK 21，临时服务使用已登记的 Testcontainers/MySQL/RabbitMQ/Temurin；attempt 容器身份与 MySQL 关闭事实查询是 ForgeOJ 自有实现。框架、broker、daemon 和镜像能力的归属/许可证边界不变，详见两项阶段验证记录与 `OWNERSHIP.md`。

### U-009：M2 普通账号 JWT 编解码依赖

- 采用日期：2026-10-02，D-041 用户已确认的普通账号设计内；账号单元为 `VERIFIED`，完整 M2 仍为 `IN_PROGRESS`，最终命令、哈希和范围见 [账号验收](M2-ACCOUNTS-VALIDATION.md)。
- API POM 新增直接项 `org.springframework.security:spring-security-oauth2-jose`，版本由既有 Spring Boot 4.1.1 BOM 管理，实际构件为 7.1.1。沿用上表既有 Spring Security `7.1.1` release/commit，没有升级整个框架、引入 OAuth 登录服务或复制源码。
- 该固定 POM 传递声明 `spring-security-oauth2-core:7.1.1`、`spring-security-core:7.1.1`、`spring-core:7.0.9` 和 `com.nimbusds:nimbus-jose-jwt:10.9.1`。Nimbus 的官方 SCM/tag 由实际 POM 记录为 Bitbucket `connect2id/nimbus-jose-jwt` / `10.9.1`；未把未核实的 commit 写作已核验。
- 许可证核验：本机 Maven Central 缓存的 JOSE 与 Nimbus POM 均声明 Apache License 2.0；固定来源为 [JOSE 7.1.1 POM](https://repo.maven.apache.org/maven2/org/springframework/security/spring-security-oauth2-jose/7.1.1/spring-security-oauth2-jose-7.1.1.pom)与 [Nimbus 10.9.1 POM](https://repo.maven.apache.org/maven2/com/nimbusds/nimbus-jose-jwt/10.9.1/nimbus-jose-jwt-10.9.1.pom)。Nimbus POM 的 shade 配置仍须在首次 Release 审计实际内嵌依赖和告知材料；本次库级核验不关闭完整发布物门禁。
- 构件 SHA-256（2026-10-02 本机实际文件）：JOSE POM `6157142BB4720EE555A41075F9DDFBEB9B5C12607FFCBDA53944DE59F1EC9E4E`，JOSE JAR `E07D47CAC04DE4F01BE6A6C3F8BE249472444B24FD4FAD8FF030B45BFE6C5B9C`；Nimbus POM `D729B6D5D9EFCB6BF54E77A9513C54DEB2F1A2D9E9AAB5FF3DDFFBC1CBF716AA`，Nimbus JAR `33152EA83EC50D22706FDAF3B07ACBCD716F9A68EDCABDD7C4D02843CBDCDCF6`。
- 复用范围：通过库 API 使用 HS256 JWT encoder/decoder 和固定 issuer/audience/期限验证；BCrypt、Spring Security 过滤链、数据库事务和 JDK 随机/摘要能力继续属于现有第三方或平台能力。未复制上游实现、页面、题目或模板，未给 Worker 新增 JOSE 依赖，根许可证未变。
- ForgeOJ 自有相邻模块：账号/会话/摘要模型与 V6、权限 SQL、激活/找回/补邮箱状态转换、refresh 轮换和重用撤销、Cookie/CSRF/Origin 规则、HTTP 与通知会话复核、账号页和本地邮件投递接口及相应测试。不能表述为自行实现密码学或 JWT 协议库。
- 验证证据：固定 Linux 后端 211 / 前端 13 项、相同产物的真实账号链路与权限/日志/队列审计和精确清理已闭环，见 [验收记录](M2-ACCOUNTS-VALIDATION.md)和[脱敏事实](evidence/m2-accounts/README.md)。验收执行于基线 HEAD `d38e2c9` 的工作树快照，交付身份见 `feat/m2-accounts` 最新提交；不预写未知提交或 push 状态。
- 剩余条件：完整传递/内嵌依赖、漏洞与发布物许可证审计留在首次 Release 门禁。依赖装配或构件哈希不单独证明账号安全或生产部署通过，账号本地闭环也不证明 SMTP 上线投递。

### U-010：M2 SMTP 库适配

- 采用日期：2026-10-03，用户要求继续剩余 M2（含真实 SMTP）并开始实施；本轮先验证适配和本地 TLS 协议，真实服务商投递仍待配置。
- 直接新增 `org.springframework.boot:spring-boot-starter-mail:4.1.1`，由现有 Boot BOM 管理；仅 API 使用，Worker 不获得邮件依赖或凭据。实际树为 Boot Mail 4.1.1、Spring Context Support 7.0.9、Jakarta Mail API 2.1.5 / Activation API 2.1.4、Angus Mail 2.0.5 / Activation 2.0.3。
- 官方固定来源：[Boot v4.1.1](https://github.com/spring-projects/spring-boot/tree/v4.1.1)、[Framework v7.0.9](https://github.com/spring-projects/spring-framework/tree/v7.0.9)、[Angus Mail 2.0.5 LICENSE](https://raw.githubusercontent.com/eclipse-ee4j/angus-mail/2.0.5/LICENSE.md)、[Mail API 2.1.5 LICENSE](https://raw.githubusercontent.com/jakartaee/mail-api/2.1.5/LICENSE.md)、[Angus Activation 2.0.3 LICENSE](https://raw.githubusercontent.com/eclipse-ee4j/angus-activation/2.0.3/LICENSE.md)、[Activation API 2.1.4 LICENSE](https://raw.githubusercontent.com/jakartaee/jaf-api/2.1.4/LICENSE.md)。全部固定 POM/JAR SHA-256 与 JAR 内 LICENSE/NOTICE 位置见 [构件事实](evidence/m2-library/smtp-dependencies.md)。
- 许可证：Spring 项为 Apache-2.0；Mail API/Angus Mail 的 POM SPDX 为 EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0；Activation 两项为 EDL-1.0/BSD-style。上游各自许可证不由根 Apache-2.0 覆盖，实际分发组合、告知和对应源码条件继续由首次 Release 完整审计核实。
- 复用 MIME、SMTP/TLS 客户端及 Spring 配置能力，不复制上游源码。ForgeOJ 自有部分为模式选择、地址/链接/TLS/超时配置约束、提交后邮件接入以及 loopback 协议测试；不称为自建邮件协议栈。
- 验证与边界：Windows 与固定 Linux 各 11 项实际 SMTP/TLS 测试通过，证书/私钥在 JUnit 临时目录动态生成，不提交、不修改全局信任。统一 229/24 和 JAR/输入证据见 [题库/SMTP 验收](M2-LIBRARY-SMTP-VALIDATION.md)；无真实邮件服务、Inbox 送达或可靠持久重试证据，见 `M2-SMTP-DESIGN.md` 和 L-033。

## 5. 引入记录模板

2026-10-02 的原创 `tools/validation/` 仅编排已登记的固定 Maven/Node/Temurin/Docker CLI/MySQL/RabbitMQ 镜像，digest 与复现证据见 `M1-FIXED-LINUX-VALIDATION.md` 和 `M0-E2E-VALIDATION.md`。Docker CLI 二进制只在一次性测试镜像内从官方固定镜像复制，不存入仓库或新增生产发布物；未复制外部脚本，未新增 Maven/npm 依赖或变更根许可证。

同日独立重放工具沿用这些固定镜像，仅使用既有 Node 内置模块编写原创有界 HTTP/WebSocket 探针。协议格式参考 [RFC 6455](https://www.rfc-editor.org/rfc/rfc6455.html)，不复制 RFC 实现代码；第三方生产 WebSocket 仍由既有 Spring/Tomcat 提供。原创故障代理、SQL fixture、事实/日志审计及 MySQL 初始化子 shell 修复均不新增依赖、grants 或镜像分发；细节见 `M1-E2E-VALIDATION.md`。

~~~markdown
### U-XXX：组件或脚手架名称

- 官方仓库：
- 采用的 commit/tag：
- 获取日期：
- 许可证及文件位置：
- NOTICE / 额外声明：
- 原样保留的文件：
- 修改的文件与修改性质：
- ForgeOJ 自行实现的相邻模块：
- 移除或替换的上游业务：
- 许可证兼容检查：
- 安全与依赖扫描：
- 对应 commit：
- 最终决定：采用 / 拒绝 / 替换
- 决定原因：
~~~

2026-10-01 沙箱安全验证使用原创有界 Java 程序和既有 Docker CLI、固定 Temurin 镜像与 JDK/Testcontainers。启用 daemon 提供的 `--init` 与 `--ipc none`，不复制或打包 Docker init 可执行文件，不新增依赖或改变镜像 digest。进程回收和 cgroup/namespace 为平台能力，ForgeOJ 的贡献是控制策略、验证与清理修复；详见 `M1-SANDBOX-SECURITY-VALIDATION.md` 和 `OWNERSHIP.md`。

2026-10-02 资源 verdict 阶段仍使用既有 JDK、Docker CLI、固定镜像和 MySQL/Flyway/Testcontainers，没有外部代码导入或新增依赖。cgroup 事件接口定义参考 Linux 官方文档，分类器、V5 与原创回归程序属于 ForgeOJ；来源与限度记录在 `M1-RESOURCE-VERDICT-VALIDATION.md`。

## 6. 仓库内需要保留的归属材料

引入第三方代码后，至少维护：

- 上游原许可证文件；
- 许可证要求的版权头和 NOTICE；
- 根目录 `THIRD_PARTY_NOTICES.md`；
- `docs/OWNERSHIP.md`，说明原样复用、修改和自行实现的模块；
- 依赖锁定信息和自动生成的依赖清单；
- 本文的上游登记表；
- 对应引入 commit，避免来源无法追踪。

删除某段代码不代表可以删除所有历史归属；是否能删除声明必须按许可证和发布历史判断。

## 7. 题目、题解与测试数据来源

代码许可证不能自动覆盖题目内容。题目创建者必须选择：

- 完全原创；或
- 基于明确许可来源改编。

改编内容至少记录原链接、作者、许可证/授权、修改说明和访问日期。不得直接搬运：

- 商业或学校私有题库；
- 来源不明的题面；
- 他人隐藏测试；
- 他人官方题解或代码；
- 仅因网页可访问就误认为可再发布的内容。

公开审核必须检查来源字段。版权问题出现时，内容可下架，但审计和处理记录应保留。

## 8. 第三方依赖

Maven、npm、Docker 镜像和操作系统包都属于第三方组成。Release 前至少完成：

当前直接 Maven/npm 项（含 M0 已声明依赖）的实际解析版本、许可证和条件判断见 `docs/DIRECT-DEPENDENCY-LICENSES.md`。Apache-2.0 已满足 MySQL Connector/J Universal FOSS Exception 的根许可证类别前提，但 Connector/J 仍保持 GPL-2.0 + UFE；首次发布前必须按 fat JAR、镜像或安装包的实际组合复核完整源码可获得性、许可证和告知义务。

- 固定直接依赖版本；
- 生成依赖与许可证清单；
- 检查高风险或不兼容许可证；
- 检查已知严重漏洞；
- 固定生产镜像到可追踪 tag 或 digest；
- 记录替换或升级决定；
- 不把依赖库能力称为本人实现。

### 8.1 ForgeOJ 根许可证边界

- D-034 已确认：池也拥有版权并有权许可的 ForgeOJ 自有源代码采用 Apache License 2.0，版权声明为 `Copyright 2026 池也`；
- 根 `LICENSE` 使用未修改的 Apache-2.0 官方全文，根 `NOTICE` 记录项目版权并保留适用的第三方归属；
- 生成文件、保留的上游代码、依赖和工具继续遵循各自许可证，不会因根许可证而被重新许可；
- 代码许可证不自动覆盖题目、题解、测试数据或用户提交内容；这些内容继续按第 7 节单独记录来源与授权；
- 根许可证决定关闭 M-1 的许可证选择门禁，但不代替首次正式发布前的完整传递依赖和发布物审计。

## 9. 简历和面试表述

正确表述应类似：

> 基于经许可证核验的通用工程骨架，保留基础页面与通用设施；自行设计并实现 ForgeOJ 的判题任务、Outbox、Judge Worker、沙箱、班级权限和故障恢复链路。

最终表述必须以实际采用记录为准。M-1 阶段只证明官方最小生成骨架；当前 M0/M1 及普通账号单元的自有业务实现与验证入口见 `OWNERSHIP.md`、`M0-E2E-VALIDATION.md`、`M1-GATE-AUDIT.md` 和 [账号验收](M2-ACCOUNTS-VALIDATION.md)，不能把框架/内核能力、其余 M2～M5 计划或未发布能力写成本人已完成的发布成果。
