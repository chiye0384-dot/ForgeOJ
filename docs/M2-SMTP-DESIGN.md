# M2 SMTP 适配与投递边界

> 2026-10-03。SMTP 适配 VERIFIED，本地 TLS 协议 11 项在 Windows 和固定 Linux 均通过，统一验收见 [记录](M2-LIBRARY-SMTP-VALIDATION.md)；真实服务商投递仍待配置和验收。完整 M2 保持 `IN_PROGRESS`。

## 1. 模式与配置

`AccountMailDelivery` 根据 `forgeoj.auth.mail.mode` 只选择一个实现：默认 `disabled` 不连接网络；`local` 仅 dev/test 的 loopback 邮件模拟器；`smtp` 显式启用 SMTP。发送仍在账号事务最终提交后执行，失败只记录固定 `account.mail_delivery_failed`，公开响应保持原先的模糊提示；用户可在冷却后重发。不新增持久投递队列，不保证 exactly-once 或最终投递。

API 进程需提供 `.env.example` 中的 `FORGEOJ_AUTH_MAIL_*` 环境变量；Compose 的 `.env` 文件不会自动导出给单独启动的 Java API。用户名/密码只经 API 环境注入，不提交真实 `.env`，不写入日志、测试证据或文档。真实投递还需要明确服务商、发件地址和用户允许接收验收邮件的地址。

| 环境变量 | 含义 |
|---|---|
| `FORGEOJ_AUTH_MAIL_MODE` | 显式设为 `smtp` |
| `FORGEOJ_AUTH_MAIL_APP_URL` | 浏览器账号页面的 HTTPS 根地址；dev/test 仅 loopback 可用 HTTP |
| `FORGEOJ_AUTH_MAIL_SMTP_HOST` / `PORT` | 服务商 SMTP 主机、端口 |
| `FORGEOJ_AUTH_MAIL_SMTP_TLS` | `starttls` 必须升级 TLS；`implicit` 首字节即 TLS，常用 465 |
| `FORGEOJ_AUTH_MAIL_SMTP_FROM` | 发件邮箱，禁止显示名、换行或多个地址 |
| `FORGEOJ_AUTH_MAIL_SMTP_USERNAME` / `PASSWORD` | 完整认证对；无认证仅用于已配置网络授权的私有中继 |
| `FORGEOJ_AUTH_MAIL_SMTP_CONNECT_TIMEOUT_MS` / `READ_TIMEOUT_MS` / `WRITE_TIMEOUT_MS` | 默认各 5000，允许 100～30000 毫秒 |

生产必需 TLS 1.2/1.3、默认信任链和主机名校验，没有 trust-all 或明文模式。邮件调试关闭；SMTP AUTH 只能发生在已建立 TLS 后。激活 24 小时、重置/补邮箱 30 分钟；链接使用账号页面 fragment，保持原有页面清地址和显式 POST 单次消费流程。配置失败的异常不拼接真实地址/凭据。

## 2. 实现归属与验证

Spring Boot Mail starter / Spring Mail 与 Jakarta Mail / Angus 提供 MIME 和 SMTP/TLS 客户端；ForgeOJ 实现模式选择、配置校验、目的/令牌链接、账号事务后的投递接入与测试。实际版本、固定构件与许可证在直接依赖清单和 U-010 登记，不能把邮件协议栈算作自建。

`SmtpAccountMailTests` 在本机随机端口创建隔离的 SMTP/TLS 测试服务器；临时测试证书与私钥由 JDK keytool 生成并只放 JUnit 临时目录，通过仅测试注入的 sender 信任该证书，生产实现仍使用默认信任。Windows 11 项零失败/错误/跳过，覆盖真实 STARTTLS/implicit TLS、三种目的的 MIME/信封/fragment、无 STARTTLS 不泄露认证、不可信证书、可信但主机不匹配、收件和认证拒绝、静默服务器超时及配置/地址/令牌校验。没有连接外部服务商或发送真实邮件。

## 3. 真实投递验收仍待完成

取得用户提供的非敏感服务商配置与授权收件地址，在本机注入凭据，使用明确的隔离验收账号依次验证激活、找回与补邮箱。核对服务商接受投递与真实邮箱收到、账号页面完整消费及失败重发行为，保留脱敏状态而不保存邮件令牌或凭据。SMTP 接受消息不等于最终送达；退信、反垃圾配置、服务商限额和持久重试需按上线环境单独处理。尚未完成该验证，L-033 的生产投递限制继续有效。
