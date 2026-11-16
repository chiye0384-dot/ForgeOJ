# M2 普通账号模块

> 2026-10-02 用户确认首轮全部推荐方案。普通账号单元为 `VERIFIED`，完整 M2 保持 `IN_PROGRESS`；验收基于 M1 `d38e2c9` HEAD 的工作树快照，交付见 `feat/m2-accounts` 最新提交。固定 Linux 211/13、真实注册到 AC、独立审计与精确清理见 [验收记录](M2-ACCOUNTS-VALIDATION.md)和[脱敏事实](evidence/m2-accounts/README.md)。没有 PR/main merge/tag/Release。

账号为 PENDING_VERIFICATION、ACTIVE、DISABLED。新增账号以用户名、邮箱、BCrypt 密码注册，昵称可重复；账号、quota lock、激活令牌摘要同事务。既有 ID/密码/状态/quota/历史均保留，旧 ACTIVE 可用户名登录，补邮箱验证后才可找回。新用户名 3–32 ASCII 字母数字下划线，不区分大小写；邮箱首版常见 ASCII 地址，trim/lowercase，无供应商特殊合并；密码至少 12 字符、至多 72 UTF-8 字节，不 trim。

访问 JWT 5 分钟，独立 MySQL 登录会话绝对期限 7 天；HTTP 每请求复核账号 ACTIVE 和未撤销/未过期 session。JWT 不包含邮箱/密码，sid 是数据库登录会话 ID。Refresh 随机高熵，数据库只存 SHA-256 摘要，轮换时旧摘要标记消费；重用撤销该 sid。响应丢失可要求重登录，不无限重试旧 refresh。登录/刷新/撤销统一账号→会话→令牌锁序。全部退出、改密、重置、停用同事务撤销全部旧 session；密码变化使旧重置令牌失效。敏感写再次在事务中校验会话。

浏览器访问和 refresh 使用 host-only HttpOnly SameSite=Strict Cookie，生产 Secure；不放 localStorage、JSON 或 URL。所有写接口校验 CSRF 与精确同源 Origin，身份变化轮换 CSRF。认证不依赖 HttpSession；旧进程会话升级后需重新登录。JWT 签名/期限/issuer/audience 固定校验；生产需外部签名密钥，开发可随机生成临时密钥，测试仅使用公开测试密钥。

保留 GET /api/v1/auth/session 的 authenticated/user(id,username)/csrf 三字段结构。POST /login 保留 username 参数且可填邮箱。新增 POST /register、/email-verification/request、/email-verification/confirm、/refresh、/logout-all、/password-reset/request、/password-reset/confirm、/password/change、/email-binding/request、/email-binding/confirm；保留 POST /logout，重复安全退出 204。注册/重发/找回 202 固定 message，不透露存在性或发送状态；登录失败统一 401；无效/过期/已消费邮件令牌统一 400。所有返回严格白名单，凭据/摘要/session 状态不序列化。

激活令牌 24 小时、重置/补邮箱令牌 30 分钟，随机 256 bit；数据库只有摘要、用途、目标邮箱、到期和消费时间。重发不改已有密码；DISABLED 无法邮件激活。补邮箱必须当前会话和密码确认，验证后唯一索引裁决，旧邮箱不被未验证请求替换。邮件发送在提交后，本地开发模拟器仅 dev/test、绑定 loopback、独立端口、最多 100 条内存消息且不记录邮件内容；默认 disabled，不连接真实 SMTP。`AccountMailDelivery` 是投递扩展接口，disabled/投递失败被 service 捕获，仅记固定 `account.mail_delivery_failed`，不输出邮箱/token/异常。失败保留账号和未过期 token，可重发恢复；无持久可靠邮件承诺。生产注册/找回上线前需配置并验证生产 adapter，当前不能声称 SMTP 完成。邮件链接 fragment 由页面取出并立即清地址，POST 消费；补邮箱确认要求原账号的有效会话。

初始限流配置：登录 IP 30/5min、标识 10/5min；发信 IP 20/hour，数据库同用户同用途间隔 60s、5/hour；消费 IP 30/5min、refresh IP 60/5min。进程 limiter 最多 10,000 个键，重启不保留，容量用尽拒绝新键；数据库邮件冷却不绕过。Redis 留 M4。

追加 V6，不改 V1–V5。API 仅增加账号 INSERT/必要列 UPDATE，以及三张认证表 SELECT/INSERT/受限 UPDATE；不给账号 DELETE、hidden tests 或判题结果等权限。Worker 不增加认证权限。正式 MQ 四字段及快照不变。

WebSocket 路径、owner、Origin、上限、三字段消息、单调版本和轮询不变，订阅改持有 sid，每次扫描/发送前复核 MySQL session；已连接按会话绝对期限存活，重连需有效 JWT。撤销已提交后关闭对应 watcher，扫描兜底；DB 异常关闭。多标签共享 Cookie，同页合并 refresh，支持 Web Locks 时使用跨标签锁并再次 GET session 后协调刷新；缺少 Web Locks 的浏览器仍可登录，但短 JWT 到期后要求重登录，不自动轮换共享 refresh，以免多标签竞争触发重用撤销。

验证单元：1 数据/注册回滚与唯一竞争；2 邮件/激活与页面；3 JWT/刷新/撤销/通知和 M1；4 改密找回补邮箱与完整账号验收。全部包含状态/权限/失败/并发测试，最终真实浏览器注册→邮件→激活→选题→实际 AC，固定 Linux 回归。账号模块通过不表示全部 M2 五门禁通过。

四个账号验证单元现已闭环：固定 Linux API 100 + Worker 111、前端 13 项及全部检查通过；相同哈希的新 Replay 实际完成注册/本地邮件/fragment 清地址/激活/邮箱登录/AC 和旧 ACTIVE 轮询兜底，并通过 owner/Origin/退出/取消、八 verdict、日志/Outbox/队列与精确清理。188 个实现/验证输入与最终构建清单一致，详细命令和失败修复边界见 [验收记录](M2-ACCOUNTS-VALIDATION.md)。真实 SMTP、Redis、生产主机及其余 M2 功能仍未完成。
