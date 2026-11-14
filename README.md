# ForgeOJ

面向 Java 学习者和小型教学班级的在线判题平台。支持 Java 21、单文件 Main.java、标准输入输出题；API 与判题 Worker 独立运行。

本仓库以 `main` 作为对外展示分支，保留 [feat/m2-accounts 分支](https://github.com/chiye0384-dot/ForgeOJ/tree/feat/m2-accounts)和历史提交。版本以仓库预览标签为准；预览标签不代表已上线服务。

从[五分钟设计导读](DESIGN.md)了解架构、判题状态机和故障处理；从[性能导读](PERFORMANCE.md)区分已测数据与尚未证明的容量；格式与检查命令见[开发约定](CONTRIBUTING.md)。

```mermaid
flowchart LR
    UI[Vue 页面] --> API[Spring Boot API]
    API -->|提交、任务、事件同事务| DB[(MySQL / Outbox)]
    DB --> Publisher[Outbox 发布器]
    Publisher --> MQ[RabbitMQ]
    MQ --> Worker[判题 Worker]
    Worker -->|租约与结果持久化| DB
    Worker --> Docker[受限 Docker 沙箱]
    API --> Redis[(Redis)]
    API -->|REST 全文检索| ES[(Elasticsearch)]
```

Elasticsearch通过JDK HttpClient调用REST API，因此pom没有额外ES客户端依赖；MySQL负责权限/当前版本复核，ES不可用时退回标题搜索。公网演示暂未部署，本机入口与部署准备模板见下文。

## 打开本地网站

保持 Docker Desktop 运行，双击根目录的 **打开ForgeOJ.cmd**，等待 Ready 后访问：

**http://127.0.0.1:8088/problems**

演示账号：learner / forgeoj-dev-only。这是本机隔离演示，不要填写真实个人资料。

    .\ForgeOJ.ps1 -Action Start
    .\ForgeOJ.ps1 -Action Status
    .\ForgeOJ.ps1 -Action Stop

停止会保留数据库和提交记录。启动入口只使用已初始化且核对归属的演示项目，不自动创建空库。首次没有演示环境时，先按[本地演示说明](docs/LOCAL-DEMO.md)准备经过验证的构件与独立环境。

## 当前状态

2026-11-14：完整固定Linux回归通过API 341项、Worker 139项、前端159项测试及全部构建检查。单Worker在固定A+B/3用例负载下，三轮约17.43～17.69份/分钟；完整环境、原始数据和限制见[性能导读](PERFORMANCE.md)。这是本机短时测量，公网与真实主机验收尚未完成。

既有本地演示包含21道题、官方题解与入门题单，以及[登录与注册流程](docs/ACCOUNT-UX-VALIDATION.md)、[可调整分栏的做题工作台](docs/WORKSPACE-UX-VALIDATION.md)、[提交历史与原源码查看、原请求重试、个人进度和继续草稿](docs/PRACTICE-CONTINUITY-VALIDATION.md)。本轮整理与验证见[报告](docs/PORTFOLIO-READINESS-VALIDATION.md)，详细边界见[当前状态](docs/CURRENT-STATUS.md)。

网站包含账号、公共题库、学习记录、题单、草稿、自测、官方题解、作者出题与审核发布、班级作业、独立管理后台、搜索和监控。判题链路使用 MySQL 事实源、事务 Outbox、RabbitMQ、幂等领取、租约/心跳、受限 Docker 执行与结果持久化后 ACK。

本地能访问不代表已完成云端验收。真实服务器、HTTPS、异地备份、长期运行与受控用户试用仍按 M5 分别验证。

## 代码与内容

- forgeoj-api：Spring Boot / MyBatis，HTTP、权限、业务事务和消息发布。
- forgeoj-judge-worker：异步消费、快照校验、Java 编译执行、资源约束与失败恢复。
- frontend：Vue / TypeScript，题库、练习和管理页面。
- content/starter：[20 道原创入门题](content/starter/README.md)的题面、测试、两份实现和题解。
- deploy/production：独立部署准备模板；不是已上线实例。
- tools/validation：固定 Linux、真实业务及故障验证工具。

## 验证与文档

前端：在 frontend 运行 npm ci、npm run verify。后端：根目录运行 .\mvnw.cmd --batch-mode clean verify；固定 Linux 及真实 Docker 环境按现有验证工具执行。

[需求](docs/ForgeOJ-Requirements.md) · [决策](docs/ForgeOJ-Decision-Log.md) · [路线图](docs/ForgeOJ-Roadmap.md) · [已知限制](docs/KNOWN_LIMITATIONS.md) · [M4 验收](docs/M4-GATE-AUDIT.md) · [M5 入口](docs/M5-NEW-CHAT-START.md) · [部署说明](deploy/production/README.md)

以前的 README 进度快照完整保存在[历史记录](docs/history/README-before-product-improvements-20261029.md)，其中相对链接按原仓库根目录解释；日期记录不覆盖当前状态。

自有代码采用 [Apache-2.0](LICENSE)，第三方与内容边界见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。普通 Docker 沙箱与单机方案面向受控小范围使用，不承诺专业强敌对多租户隔离或高可用 SLA。
