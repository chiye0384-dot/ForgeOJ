# 管理员初始化与本机恢复

本机维护命令属于 M4 管理身份单元。Web 启动不创建管理员，V18 不带默认账号或密码。这里是运行说明；本次验收只操作一次性数据库，尚未为真实环境创建管理员。

使用实际 API 可执行 JAR 和交互终端：

```text
java -jar forgeoj-api-0.0.1-SNAPSHOT.jar admin-bootstrap
java -jar forgeoj-api-0.0.1-SNAPSHOT.jar admin-recover
```

命令必须是唯一参数；重定向/管道输入或无控制台时退出码 2，不启动 Web、调度器或消息监听。密码使用控制台隐藏输入两遍，不能放命令参数、URL、输入管道、历史记录或仓库。维护模式在 Spring 启动前分流，不需要 Web JWT、RabbitMQ 或 SMTP 配置。

由操作者为**这一次维护进程**提供环境变量 `FORGEOJ_ADMIN_CLI_DB_URL`、`FORGEOJ_ADMIN_CLI_DB_USERNAME`、`FORGEOJ_ADMIN_CLI_DB_PASSWORD`。URL 必须为 `jdbc:mysql://.../forgeoj`，不能含 user/password；凭据只交给维护进程，完成后移除。不得复用 API/Worker 进程或把维护凭据加入其持久配置。实际数据库必须已成功应用 V18。远程数据库的传输安全和授权管理属于部署配置；本单元没有部署验收。

初始化会提示确认目标数据库名称、首管理员用户名和新密码。数据库必须没有任何管理员，且永久初始化标记为 false。成功后账号为 ACTIVE/SUPER_ADMIN/必须改密，初始化标记与成功审计一起提交；重跑拒绝。即使有维护权限的人删除账号，永久标记也不提供重复初始化入口。

当全部超级管理员无法登录时，操作者显式选择恢复命令，确认数据库、输入 `RECOVER`、既有 SUPER_ADMIN 的 ID、恢复原因和新密码。不能恢复普通用户或把审核/运维账号提升为 SUPER；不会新建账号。成功后指定账号恢复 ACTIVE、版本递增、全部旧会话撤销、首次强制改密，保留 LOCAL_RECOVERY 审计。其他管理员和普通身份不改变。任何失败统一返回 1，不打印凭据或底层异常；成功返回 0。恢复后的首次改密也会撤销旧会话，需要重新登录。

维护数据库身份必须短时受控，至少具备以下权限；不要把这些权限授给 Web API 或 Worker：

| 对象 | 维护操作需要 |
|---|---|
| flyway_schema_history | SELECT，用于确认 V18 |
| admin_policy_fence | SELECT、UPDATE(bootstrapped)，用于串行化与一次性标记 |
| admin_account | SELECT、INSERT、UPDATE(status,password_hash,must_change_password,version,updated_at) |
| admin_login_session | SELECT、UPDATE(revoked_at) |
| admin_audit_event | INSERT；无修改/删除审计的业务需要 |

账号、会话与审计在同一个事务内；审计写入失败会回滚，不能通过“先恢复账号、后补日志”处理。拥有更高数据库权限的操作者仍是信任边界，持久审计不是针对数据库管理员的防篡改系统。

后台入口是 `/admin/login`，用户名只接受 3–32 个 ASCII 字母、数字、下划线；管理域大小写不敏感。密码至少 12 字符、至多 72 个 UTF-8 字节，不 trim。后台没有公开注册或普通用户邮件找回。真实初始化、发布和服务器权限调整需单独获得用户授权。
