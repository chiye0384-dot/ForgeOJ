# ForgeOJ 上游与许可证策略

> 当前状态：最小官方骨架选型已确认并生成，上游和产物哈希已登记；ForgeOJ 自有代码已确认采用 Apache-2.0，版权人为池也。  
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

## 5. 引入记录模板

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

当前 M-1 直接 Maven/npm 项的实际解析版本、许可证和条件判断见 `docs/DIRECT-DEPENDENCY-LICENSES.md`。Apache-2.0 已满足 MySQL Connector/J Universal FOSS Exception 的根许可证类别前提，但 Connector/J 仍保持 GPL-2.0 + UFE；首次发布前必须按 fat JAR、镜像或安装包的实际组合复核完整源码可获得性、许可证和告知义务。

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

最终表述必须以实际采用记录为准。当前可以说“使用官方最小生成骨架建立了可构建工程基线”，但不能声称通用基础设施或 ForgeOJ 业务已实现。
