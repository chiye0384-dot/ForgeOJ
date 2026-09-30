# ForgeOJ 直接依赖许可证清单

> 核验日期：2026-09-30
> 范围：已验证的 M-1 工程基线和 M0 最小判题纵向切片，以及当前已声明的直接 Maven/npm 依赖和构建工具；M1 仍为 `PLANNED`。此清单不是法律意见，也不替代发布前的完整传递依赖报告。

## 1. 核验方法

- Maven 版本取自 Spring Boot 4.1.1 解析后的依赖树和本机 Maven Central POM 元数据；
- npm 版本取自 `frontend/package-lock.json` 的实际解析结果，许可证取自对应包的 `package.json`；
- 仅开发/测试依赖也记录，但与最终运行包分开判断；
- ForgeOJ 自有代码已选定 Apache-2.0；本清单仍只判断当前直接依赖的义务与风险，不替代正式法律意见或发布物审计。

## 2. 后端直接项

| 直接项 | 解析版本 | 作用域/用途 | 上游声明许可证 | 当前判断 |
|---|---:|---|---|---|
| Spring Boot parent/BOM、`spring-boot-starter-webmvc`、测试 starter、Maven Plugin | 4.1.1 | 编译、API 运行、测试和打包 | Apache-2.0 | 可采用；保留上游版权与 NOTICE 义务 |
| `org.springframework.boot:spring-boot-starter-security` | 4.1.1（Security 7.1.1） | API 运行 | Apache-2.0 | 可采用；依赖存在不等于认证功能已经实现 |
| `org.springframework.boot:spring-boot-starter-amqp` | 4.1.1（Spring AMQP 4.1.1） | API/Worker 运行 | Apache-2.0 | 可采用；M0 只实现最小 RabbitMQ 链路 |
| `org.springframework.boot:spring-boot-starter-jackson` | 4.1.1（Jackson Databind 3.1.5） | Worker 运行；严格解析四字段任务 JSON | Apache-2.0 | 可采用；JSON 库为第三方能力，消息契约与校验规则为 ForgeOJ 自有实现 |
| `org.springframework.boot:spring-boot-starter-flyway` | 4.1.1（Flyway Core 12.4.0） | API 运行；唯一生产迁移执行者 | Apache-2.0 | 可采用；Worker 不引入生产 Flyway |
| `org.flywaydb:flyway-mysql` | 12.4.0 | API runtime；MySQL 数据库支持 | Apache-2.0 | 可采用；由 Boot 4.1.1 BOM 管理版本 |
| `org.mybatis.spring.boot:mybatis-spring-boot-starter` | 4.1.0 | API/Worker 运行 | Apache-2.0 | 可采用；不等于引入 MyBatis-Plus |
| `com.mysql:mysql-connector-j` | 9.7.0 | runtime | GPL-2.0 with Universal FOSS Exception 1.0 | 可在 Apache-2.0 开源源码组合中采用；发布物仍需专项审计 |
| `com.h2database:h2` | 2.4.240 | test only | MPL-2.0 或 EPL-1.0 双许可证 | 仅用于空上下文测试；不进入生产运行依赖，发布前仍需保留相应许可证信息 |
| `org.springframework.security:spring-security-test` | 7.1.1 | API test only | Apache-2.0 | 可采用；只用于安全边界自动化测试 |
| `org.springframework.boot:spring-boot-testcontainers` | 4.1.1 | API/Worker test only | Apache-2.0 | 可采用；只提供 Spring Boot 测试集成 |
| `org.testcontainers:testcontainers-junit-jupiter`、`testcontainers-mysql`、`testcontainers-rabbitmq` | 2.0.5 | API/Worker test only | MIT | 可采用；用于 disposable MySQL/RabbitMQ 集成测试 |

MySQL Connector/J 的 POM 明确写明 “GPL v2 with Universal FOSS Exception 1.0”。[Oracle 的例外文本](https://oss.oracle.com/licenses/universal-foss-exception/)把额外许可限定在与完整源码、采用 OSI 批准或 FSF 自由许可证的 “Other FOSS” 一起使用/分发。Apache-2.0 是 OSI 批准许可证，因此根许可证类别这一前提已满足；Connector/J 本身仍保持 GPL-2.0 + UFE，不能被 ForgeOJ 根许可证重新许可。首次发布 fat JAR、镜像或安装包前，仍须按实际组合复核完整对应源码可获得性、许可证和告知方式；若发布方式不能满足条件，必须更换驱动方案或取得合适许可。

H2 的测试作用域不能作为 MySQL 语义证据，也不能因为不进入生产运行包就从依赖清单中消失。

## 3. 前端运行直接依赖

| npm 包 | 锁定版本 | 许可证 |
|---|---:|---|
| `vue` | 3.5.43 | MIT |
| `vue-router` | 5.3.1 | MIT |

## 4. 前端直接开发依赖

| npm 包 | 锁定版本 | 许可证 |
|---|---:|---|
| `@tsconfig/node24` | 24.0.5 | MIT |
| `@types/jsdom` | 28.0.3 | MIT |
| `@types/node` | 24.19.0 | MIT |
| `@vitejs/plugin-vue` | 6.0.9 | MIT |
| `@vitest/eslint-plugin` | 1.6.27 | MIT |
| `@vue/eslint-config-typescript` | 14.9.0 | MIT |
| `@vue/tsconfig` | 0.9.1 | MIT |
| `eslint` | 10.11.0 | MIT |
| `eslint-config-prettier` | 10.1.8 | MIT |
| `eslint-plugin-oxlint` | 1.60.0 | MIT |
| `eslint-plugin-vue` | 10.8.0 | MIT |
| `jiti` | 2.7.0 | MIT |
| `jsdom` | 29.1.1 | MIT |
| `npm-run-all2` | 8.0.4 | MIT |
| `oxlint` | 1.60.0 | MIT |
| `prettier` | 3.8.3 | MIT |
| `typescript` | 6.0.3 | Apache-2.0 |
| `vite` | 8.3.1 | MIT |
| `vitest` | 4.1.11 | MIT |
| `vue-tsc` | 3.3.11 | MIT |

这些开发工具不等于 ForgeOJ 自研能力，也通常不进入 Vite 生产静态文件；仍需保留锁文件和来源记录。

## 5. M0 外部开发/测试运行时

| 运行时镜像 | 固定引用 | 主要许可证边界 | 当前判断 |
|---|---|---|---|
| MySQL Community Server | `container-registry.oracle.com/mysql/community-server:8.4.12@sha256:7dcc4add9183664de3a214daf85a50c3ba6cccfd7534f700b6561bf5b41885be` | MySQL Community Server 为 GPLv2；镜像内第三方组件各自适用原许可证 | 只从 Oracle Registry 拉取作 disposable 运行时，不提交镜像层或派生镜像 |
| RabbitMQ Management | `rabbitmq:4.3.6-management@sha256:cdf40d8cb363d145e377ed88d59696a42386ffe54b30125f10eb128b862eea95` | RabbitMQ Server/核心插件主要为 MPL-2.0；基础系统、Erlang 等组件各自适用原许可证 | 只从官方 registry 拉取作 disposable 运行时，不提交镜像层或派生镜像 |
| Testcontainers Ryuk | `testcontainers/ryuk:0.14.0` | MIT | Testcontainers 测试清理辅助镜像；不进入 ForgeOJ 生产运行包 |
| Eclipse Temurin Java 21 | `eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438` | Temurin 二进制为 GPLv2 with Classpath Exception；容器 Dockerfile 仓库为 Apache-2.0；Ubuntu 与镜像内其他组件各自适用原许可证 | M0 判题容器的外部固定运行时；当前不提交镜像层、归档或 ForgeOJ 派生镜像 |

镜像许可证不会自动改变 ForgeOJ 自有源码的 Apache-2.0，但若以后分发镜像归档、复制镜像层或制作派生镜像，必须重新审核对应源码提供、NOTICE 和内部组件义务。

## 6. 当前结论与剩余门禁

- Spring Boot、Spring Security、Spring AMQP、Flyway、Testcontainers、MyBatis、Vue 和构建工具的直接许可证已登记；
- M0 新增直接依赖的 Apache-2.0/MIT 许可证与根 Apache-2.0 不冲突，不需要重新选择根许可证；
- Testcontainers 库的 MIT 许可证不覆盖它启动的 MySQL、RabbitMQ、Ryuk 等容器镜像；判题使用的 Eclipse Temurin 也有独立许可证边界。当前使用的精确运行时已在第 5 节登记，升级时必须同步更新；
- H2 被限制为测试作用域；
- Apache-2.0 已解决 MySQL Connector/J Universal FOSS Exception 的根许可证类别前提，但实际发布组合的履约审计仍未完成；
- GitHub Actions 的三个直接 Action 已在工作流中固定完整 commit，许可证记录见 `THIRD_PARTY_NOTICES.md`；
- 首次对外发布前仍要生成并审阅完整 Maven/npm 传递依赖许可证报告，并随发布物保留要求的许可证与 NOTICE；
- M-1 的根许可证与当前直接依赖许可证门禁已完成；完整传递依赖和最终发布物报告属于首次 Release 前门禁，不再作为 M-1 独立复现门禁。
