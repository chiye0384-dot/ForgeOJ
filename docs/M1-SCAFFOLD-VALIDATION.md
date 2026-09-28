# M-1 脚手架生成与验证记录

> 执行日期：2026-09-27；许可证决策更新：2026-09-28  
> 执行环境：Windows 11 / JDK 21.0.12.1 / Node 24.14.1 / npm 11.11.0  
> 结论：本机、干净 Linux 容器与真实 GitHub Actions 工程基线均通过；M-1 为 `VERIFIED`，M0 仍为 `PLANNED`。

## 1. 生成输入与产物

后端使用 `https://start.spring.io/starter.zip` 生成，共同参数为：

- `type=maven-project`
- `language=java`
- `bootVersion=4.1.1`
- `groupId=com.forgeoj`
- `packaging=jar`
- `javaVersion=21`

API 生成包：

- `baseDir/artifactId/name=forgeoj-api`
- `packageName=com.forgeoj.api`
- `dependencies=web,mysql,h2`
- ZIP SHA-256：`BEE838C57920952BED60183B52DCB25E9734D23A9157DA0565E96F7AB1CE893B`

Worker 生成包：

- `baseDir/artifactId/name=forgeoj-judge-worker`
- `packageName=com.forgeoj.worker`
- `dependencies=mysql,h2`
- ZIP SHA-256：`33DC75E55A4F3AD0BBEC731AA001C9593BF57866022C425FD890912EE7AD4769`

前端生成命令：

~~~powershell
npx --yes create-vue@3.22.3 frontend --typescript --router --vitest --eslint --prettier --bare
~~~

两个后端 ZIP 的临时下载/解压目录已在记录哈希和完成导入后删除，可通过上述参数重新生成。

## 2. 最终工程改造

- 根 POM 只做 parent/aggregator，子模块为 `forgeoj-api` 和 `forgeoj-judge-worker`；
- 两个子模块不相互依赖；
- Spring Boot 固定为 4.1.1，Maven Wrapper 3.3.4 固定 Maven 3.9.14；Maven ZIP 的 SHA-256 为 `55fadd669532a3205d5db95f490bf13971d8b0843526f407f29db0e61f074ab3`，已写入 Wrapper 配置；
- `mvnw.cmd` 对 Maven Wrapper 3.3.4 的空 `Target[0]` 缺陷加入最小保护；Linux/macOS 的 `mvnw` 未改动；
- 根 POM 显式管理 `mybatis-spring-boot-starter:4.1.0`；
- H2 仅为 test scope，MySQL Driver 为 runtime scope；
- Worker 设置 `spring.main.web-application-type=none`；
- 没有引入 MyBatis-Plus、PageHelper、Security、RabbitMQ、Flyway、Actuator 或 Docker 客户端；
- create-vue 默认加入的 Vue DevTools、`Vite App` 标题和模板 favicon 已移除，未引入 Pinia 或 E2E 工具。

### 2.1 根许可证验证

- 用户已确认池也拥有版权并有权许可的 ForgeOJ 自有源代码采用 Apache-2.0；
- 根 `LICENSE` 与 Apache 官方文本及仓库保留的标准文本逐字节一致，SHA-256 为 `CFC7749B96F63BD31C3C42B5C471BF756814053E847C10F3EB003417BC523D30`；
- 根 `NOTICE` 包含 `Copyright 2026 池也`，并保留 Apache Maven Wrapper 的 NOTICE 归属；
- 根 POM 和前端根包元数据均声明 Apache-2.0；第三方代码和依赖仍按各自许可证记录，不被重新许可。

## 3. 后端验证

### 3.1 Wrapper 环境

~~~powershell
.\mvnw.cmd --version
~~~

结果：

- Apache Maven 3.9.14；
- Java 21.0.12.1；
- `JAVA_HOME` 指向本机 Eclipse Temurin JDK 21 安装目录。

最终复验时，原始 3.3.4 `mvnw.cmd` 在当前 Windows `.m2` 普通目录上把严格的 `$null` 当数组索引，报 `Cannot index into a null array`。这与 Apache Maven Wrapper [issue #395](https://github.com/apache/maven-wrapper/issues/395) 的根因一致。按上游 [draft PR #416](https://github.com/apache/maven-wrapper/pull/416) 的方向，并补上对空集合首元素的检查后，Wrapper 恢复正常启动。该本地补丁已列入上游与归属记录，不能再将 `mvnw.cmd` 描述为完全未修改。

另外将 `MAVEN_USER_HOME` 指向项目内一次性空目录后执行 `mvnw.cmd --version`，Wrapper 成功下载、校验并启动 Maven 3.9.14；随后已删除该临时缓存。这直接验证了 `distributionSha256Sum`，但仍不代替未来全新主机的完整工程构建。

### 3.2 干净构建与测试

~~~powershell
.\mvnw.cmd --batch-mode clean verify
~~~

结果：`BUILD SUCCESS`；反应器中根工程、API 和 Worker 全部成功，两个空上下文测试共 2 个，失败 0、错误 0、跳过 0。

在依赖下载完成后又执行了：

~~~powershell
.\mvnw.cmd --offline --batch-mode clean verify
~~~

加入 Windows Wrapper 空值保护并锁定 Maven ZIP 校验值后的最终离线验证同样 `BUILD SUCCESS`，总用时 10.264 秒，证明当前 Maven Wrapper/本地依赖集合可重复构建。这不等于另一台全新主机的复现证据。

构建日志中的两类警告已保留而没有伪装消失：

- 空骨架暂无 MyBatis Mapper，扫描器因此提示未找到 Mapper；
- Boot 测试依赖中 Mockito/Byte Buddy 在当前 JDK 上会提示未来动态 agent 限制。

它们均不影响本次构建结果，也不应通过创建虚构 Mapper 或隐藏 JVM 警告来“消除”。

### 3.3 依赖边界

~~~powershell
.\mvnw.cmd --batch-mode dependency:tree
~~~

已确认：

- API 包含 `spring-boot-starter-webmvc:4.1.1`；Worker 不包含 WebMVC；
- 两模块均使用 `mybatis-spring-boot-starter:4.1.0`；
- 其传递版本为 MyBatis 3.5.19 / MyBatis-Spring 4.1.0；
- H2 2.4.240 只在 test scope，MySQL Connector/J 9.7.0 在 runtime scope；
- 依赖树不含 MyBatis-Plus、PageHelper 或 Docker 客户端。

## 4. 前端验证

锁文件干净安装：

~~~powershell
npm ci
~~~

结果：安装 292 个包，审计 293 个包，`0 vulnerabilities`。

干净安装后分别执行过五项命令，并在最终状态再次执行聚合命令：

~~~powershell
npm run type-check
npm run lint:check
npm run format:check
npm run test:unit:run
npm run build
npm run verify
~~~

五项及聚合命令退出码均为 0；OxcLint 为 0 警告/0 错误，Vitest 为 1 个测试文件/1 个测试通过，Vite 8.3.1 生产构建成功。

第一次安装时，生成器给出的 `@vue/test-utils` 宽松版本范围漂移到了要求 Node 24.15 的传递依赖。最终骨架不需要该库，已改为直接用 Vue `createApp` 完成挂载测试并移除该依赖；最终 `npm ci` 无 engine 或弃用警告。

## 5. Skill 一致性验证

`forgeoj-development/references/upstream-selection.md` 已从硬编码 Spring Boot 3 改为读取决策日志中的当前后端基线。主保存副本、项目级副本和用户级已安装副本的 4 个文件逐文件 SHA-256 一致，三份均通过官方 `quick_validate.py`。

## 6. 未验证和不得外推的内容

- GitHub Actions 已在首个公开提交上真实运行并通过；后续提交仍需持续通过同一工作流；
- M-1 已在全新缓存的 Linux 容器中复现；这不是 M5 所要求的固定 Linux 主机功能、安全和性能验收；
- H2 不是 MySQL 替代证据，尚未验证任何 Mapper、迁移、事务、锁、Outbox 或幂等 SQL；
- RabbitMQ、Docker 沙箱、Security、认证、题库和提交链路都没有实现；
- M-1 的 `VERIFIED` 只代表项目准备门禁闭环，不得据此将任何 OJ 业务能力写成已实现或写入简历。

## 7. 2026-09-28 根许可证落地复验

- 根 `LICENSE` 与仓库保留的 Apache-2.0 标准文本逐字节一致，均为 11,358 字节，SHA-256 为 `CFC7749B96F63BD31C3C42B5C471BF756814053E847C10F3EB003417BC523D30`；
- POM XML 与前端 `package.json` / `package-lock.json` 均可解析，分别得到 `Apache License, Version 2.0` 与 SPDX 标识 `Apache-2.0`；前端仍保持 `private: true`；
- `.\mvnw.cmd --batch-mode clean verify` 返回 `BUILD SUCCESS`，API/Worker 共 2 个测试通过；
- `npm ci` 安装 292 个包、审计 293 个包，结果为 0 vulnerabilities；
- `npm run verify` 的类型检查、OxcLint、ESLint、Prettier、1 个 Vitest 测试和 Vite 生产构建全部通过；
- 全库未发现“根许可证待定”或“人工结构审阅待完成”等过时表述。

以上复验确认本机许可证变更没有破坏工程基线；独立环境证据见下一节。

## 8. 2026-09-28 干净 Linux 容器复现

源码以只读方式挂载到 Docker Linux/amd64 容器，只将构建所需文件复制到容器临时工作目录；没有挂载宿主机 Maven/npm 缓存、`target`、`node_modules` 或 `dist`。容器退出后自动删除。

固定镜像：

- 后端：`maven:3.9.14-eclipse-temurin-21`，多架构 manifest digest `sha256:98819eb3745bd2007c3f1a19b59085c1fa3929aecb7dbfa431dfcf5a4f18ce3c`，本次 Linux/amd64 manifest digest `sha256:5c73793a3919815ff0e3921c53f8070d65a35090c0472763fa40ac22c0cfa0f1`；
- 前端：`node:24.14.1-bookworm-slim`，多架构 manifest digest `sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c`，本次 Linux/amd64 manifest digest `sha256:e484ae3f1e3c378021c967fd42254f343c302a9263e412280eac32bf5bca7008`。

后端容器使用全新的 `/tmp/m2` 作为 Wrapper 和依赖缓存。镜像本身不含 `unzip`，先在一次性容器中安装该 Wrapper 运行前提；随后 `bash ./mvnw --version` 验证 Maven 3.9.14 和 Temurin 21.0.10，`clean verify` 的根工程、API、Worker 全部 `SUCCESS`，2 个上下文测试通过。

前端容器使用 Node 24.14.1、npm 11.11.0 和全新 npm cache。`npm ci` 安装 292 个包、审计 293 个包，结果为 0 vulnerabilities；`npm run verify` 的类型检查、OxcLint、ESLint、Prettier、1 个 Vitest 测试和 Vite 生产构建全部通过。

第一次隔离命令曾把 npm 可执行入口放在不可执行的 tmpfs，并在复制前端文件时遗漏 `.gitignore`，分别导致 `run-s: Permission denied` 和 OxcLint 扫描 `node_modules`。改为容器普通临时工作目录并完整保留 `.gitignore` 后通过；这两个失败来自复现装置，不是源码修复。Maven 第一次尝试未安装 `unzip`，Wrapper 因改下 tarball 而无法匹配 ZIP 哈希；直接下载 ZIP 得到的 SHA-256 与仓库记录一致，补齐 `unzip` 后 Wrapper 校验通过。

该结果满足 M-1 的独立全新环境复现门禁，但不冒充 M5 固定 Linux 主机验收；GitHub 托管 CI 证据见下一节。

## 9. 2026-09-28 真实 GitHub Actions 复现

- 公共仓库：[`chiye0384-dot/ForgeOJ`](https://github.com/chiye0384-dot/ForgeOJ)；
- 触发提交：`c2906b48e329eb5d6e676bf5e74f8fd96c47d5e7`（`chore: establish ForgeOJ M-1 scaffold`）；
- 工作流：[CI run 36386617957](https://github.com/chiye0384-dot/ForgeOJ/actions/runs/36386617957)，由 `main` 分支首次 `push` 触发，结论为 `success`；
- [`frontend` job](https://github.com/chiye0384-dot/ForgeOJ/actions/runs/36386617957/job/108813214922)：`success`，执行 Node 24.14.1、`npm ci` 与 `npm run verify`；
- [`backend` job](https://github.com/chiye0384-dot/ForgeOJ/actions/runs/36386617957/job/108813215193)：`success`，执行 Temurin JDK 21 与 `bash ./mvnw --batch-mode clean verify`。

结合人工结构确认、Apache-2.0 与第三方许可证边界、Windows 本机验证、干净 Linux 容器复现和上述托管 CI，M-1 于 2026-09-28 标记为 `VERIFIED`。这不等于 M0 或任何判题业务已经实现，也不替代 M5 的固定 Linux 主机功能、安全和性能验收。
