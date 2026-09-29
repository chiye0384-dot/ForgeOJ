# M0 真实纵向链路验证记录

> 验证日期：2026-09-29
> 被验证提交：`c25a7a2`
> 结果：Windows 开发环境真实纵向链路通过；固定 Linux 门禁仍未执行。

## 1. 验证目标

本记录验证以下组件不是只在各自测试中成立，而是能在同一个隔离环境中连成一条真实链路：

```text
Vue 页面
  -> Spring Security Session / CSRF
  -> Submission + JudgeTask + Outbox 事务
  -> RabbitMQ
  -> 独立 Judge Worker
  -> 受限 Docker Java 21 容器
  -> MySQL 终态
  -> 所有者结果查询
  -> Vue 轮询显示结果
```

本次只证明 Windows + Docker Desktop 开发环境中的 AC 正常路径。它不替代 M0 的其他 verdict 自动化、恶意代码矩阵或固定 Linux 完整重放。

## 2. 隔离环境

- 宿主：Windows x64；
- 后端运行时：Eclipse Temurin JDK 21.0.12.1；
- Docker Engine：Linux 29.8.0；
- Docker Compose：v5.5.1；
- Compose project：`forgeoj-m0-e2e`；
- MySQL：仓库固定的 8.4.12 tag + digest，临时映射到 `127.0.0.1:33306`；
- RabbitMQ：仓库固定的 4.3.6-management tag + digest，临时映射到 `127.0.0.1:35672`；
- API：独立 Java 进程，端口 `8080`，启用 `dev` profile；
- Worker：独立 Java 进程，显式开启 consumer 与 sandbox；
- 前端：Vite 开发服务器，端口 `5173`，通过 `/api` 同源代理访问 API；
- 所有数据库、队列、密码和端口配置仅用于此次 disposable 栈；验证结束后容器、网络、卷和临时密码文件全部删除。

## 3. 实际过程与结果

1. 从空数据卷启动 MySQL 和 RabbitMQ，两个服务最终均为 `healthy`。
2. API 启动时由 Flyway 依次执行 V1、V2 和 `dev` repeatable seed，日志显示 3 个 migration 成功。
3. Worker 连接 `/forgeoj` vhost，并在启动时通过 Docker Linux Engine 可用性检查。
4. 先通过真实 HTTP 客户端验证一次 AC，再在真实浏览器打开 Vue 页面完成登录、读题和提交。
5. 浏览器页面最终显示：

```text
submissionId = f72fa75b-a853-48b5-aa24-ec5a886e4ef2
processingStatus = FINISHED
verdict = AC
statusVersion = 2
```

6. 对同一条浏览器提交做只读数据库联表核对：

```text
submission.processing_status = FINISHED
submission.verdict = AC
submission.status_version = 2
judge_task.task_status = FINISHED
judge_task.status_version = 2
outbox_event.published_at IS NOT NULL = 1
```

7. RabbitMQ 队列 `forgeoj.judge.submission.v1` 的 `messages_ready=0`、`messages_unacknowledged=0`。
8. `docker ps -a --filter label=com.forgeoj.managed=true` 没有返回容器，证明本次终态后无 ForgeOJ 沙箱容器残留。
9. API 和 Worker 收到中断后均执行正常关闭；随后删除 `forgeoj-m0-e2e` 的容器、网络和两个数据卷。

## 4. 状态结论

这次验证把 M0 从“后端局部组合测试 + 模拟 API 的前端测试”推进到“Windows 开发环境真实浏览器到真实判题终态闭环”。M0 仍保持 `IN_PROGRESS`，原因是：

- 尚未在固定 Linux 环境从空缓存重放完整链路；
- 完整端到端层目前只人工验证 AC，其他 verdict 由 Worker 自动化测试覆盖；
- 更宽的恶意代码矩阵、API 无 Docker 控制面的目标环境检查仍需汇总到最终 M0 门禁；
- M1 的 Worker 崩溃恢复、attempt/lease、有限重试和死信不属于本次范围。

下一步是把同一条链路放入固定 Linux 环境进行可重复验收，并逐项核对 `docs/M0-VERTICAL-SLICE-DESIGN.md` 第 12 节的 20 项门禁。
