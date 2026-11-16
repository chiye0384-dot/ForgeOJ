# M4 第5步：Redis 与故障降级

2026-10-10；VERIFIED（仅第5步），见[M4 Redis验收](M4-REDIS-VALIDATION.md)。用户在第4步交付 `4eb49c0` 后授权继续；本单元不包含 ES、监控、M5、发布或真实数据库操作。

采用 Boot 4.1.1 管理的 Spring Data Redis/Lettuce；Redis 固定为官方 7.2.16 Alpine 镜像，摘要 `sha256:29e8589c3f9ba699b5f7aa4b3c7733c58852a3626439e619aa0ee78de08c6ca0`。7.2 系列保持 BSD-3-Clause；不复制上游源码，不引入付费服务。来源：[官方归档](https://download.redis.io/releases/)、[官方许可](https://redis.io/legal/licenses/)。依赖实际解析和告知另登记。

## 已批准范围内的实现规则

1. Cache-Aside 只缓存公共题详情、匿名官方题单详情/目录。个人题单、个人进度、题解、隐藏测试、私有内容和提交代码均排除。键包含 MySQL 公共内容代数与请求摘要；每次先读取数据库代数，旧代数绝不命中。空结果短 TTL，正常结果 TTL 抖动。
2. V23 增加代数及独立失效 Outbox；公共题批准/文本修订/更正/归档/恢复/失效业务事务显式调用 CacheInvalidations，一并递增代数、记录旧代数失效，回滚一并回滚。官方题单没有写接口，维护者修改题单/标签或开发种子时必须在同一事务采用文末 SQL 规约，不能绕过写入规约而宣称缓存一致。API 定时按有限批次删除旧代数索引；失败保留事件并退避，恢复后重放。旧键还有 TTL，Redis 的单个 retired-through 值阻止已退役代数的迟到重建；不扫描整个 Redis，不混用判题 RabbitMQ Outbox。触发器方案经真实 MySQL 1419 失败后弃用，不给迁移账号追加 SUPER、不改变全局 log_bin_trust_function_creators。
3. 热点使用有限本机锁和 Redis token 租约。只有仍持有 token 的重建者能写入缓存；其他实例短暂等候后读取 MySQL，不把 Redis 锁当作业务排他锁。
4. 普通及管理会话使用分离键域。每次读取 MySQL 当前有效会话/账号状态；管理员当前角色、版本及强制改密标记也来自 MySQL。普通 username 不提供修改接口，其缓存以独立 session UUID 为版本；管理员名称缓存加账号版本。缓存仅保留安全身份显示信息，不含密码、邮箱、JWT/刷新 token。数据库失败返回 503；缓存绝不能延长已撤销会话或恢复旧权限。此设计保留每次最小 DB 权限复核，不宣称认证完全不访问数据库。
5. 登录及管理限流增加 Lua 原子共享计数；每次同时维护原有有限单机计数。Redis 清空/故障时，原本机预算不会清零；多实例故障期间只承诺各实例保守限制，不冒充全局配额。新正式提交另有账号 60/60s 限流，数据库排队/运行额度及幂等唯一键仍是最终约束，原已接受同键回执仍可获取。
6. 连点辅助仅用短租约合并正在进行的请求，不缓存判题状态或作为接受凭证。过期、清空、冲突、故障均回到原 MySQL 幂等/当前权限/CAS 路径；班级和作业绑定照常复核。
7. 默认关闭 Redis 功能；显式配置启用。连接/命令250ms超时，故障冷却2s且只允许一个恢复探测；Lettuce两条I/O及两条计算线程、256命令排队上限、断连拒绝排队。故障不记录key、账号、内容或密码。缓存JSON使用明确DTO类型及请求/内容摘要包络，拒绝结构损坏、错键和校验不符；不启用Java原生序列化或多态反序列化。活动代数最多索引4096个缓存键，超过时读取MySQL并等待TTL回收，不无界占用Redis。

## 验收

真实固定 Redis/MySQL：命中及白名单、负缓存/TTL 抖动、热点租约 fencing、事务回滚、旧事件与乱序重放、清空/停止/恢复、同键并发、单机及跨实例共享限流、缓存损坏、普通撤销及管理员降级/撤销、API/Worker SQL 权限。再运行固定 Linux All，并以同一 JAR 重放现有 Worker/公共库/学习/班级/作业/审核/运维权限与浏览器路径。证据关联源码、构件、运行输入和精确资源清理。上述第5步门禁已通过，机器证据为VERIFIED；完整M4保持IN_PROGRESS。

## 只读官方题单的维护规约

开发环境按`.env.example`配置API的`FORGEOJ_REDIS_*`，默认`FORGEOJ_REDIS_ENABLED=false`。需要启用时为Redis设置私有密码，并启动`docker compose -f deploy/compose.dev.yml --profile redis up -d redis`；环境变量必须交给API进程，Compose不会替IDE配置环境。Redis默认loopback6379、128MB/noeviction、不持久化；服务未启用时原MySQL/RabbitMQ开发配置不要求Redis密码。空密码只在Redis容器启动时拒绝，避免未启用profile也阻断原流程。不要向Worker传Redis凭据。

维护者使用其原有授权身份，在同一 MySQL 连接/事务修改公开数据后执行以下片段；不向 API/Worker 开放题单写权限。仅操作可销毁 fixture，本单元不运行真实数据维护。

```sql
START TRANSACTION;
-- 此处为审核过的题单/公开元数据维护 DML
SELECT revision INTO @old_cache_revision FROM cache_epoch WHERE namespace='public' FOR UPDATE;
UPDATE cache_epoch SET revision=revision+1 WHERE namespace='public';
INSERT INTO cache_invalidation_outbox(namespace,old_revision) VALUES('public',@old_cache_revision);
COMMIT;
```
