# M4 首单元：独立管理员身份设计

2026-10-08，第一步设计已定稿，第2步独立身份单元已[VERIFIED](M4-ADMIN-IDENTITY-VALIDATION.md)，M4整体仍 `IN_PROGRESS`。用户已确认单账号单角色与首次强制改密/本机恢复方案，见D-043。实现前基线 `0e213e1`，实际目录 `D:\Java项目\ForgeOJ`，当前分支 `feat/m2-accounts`。依据Requirements 4.2/12、D-016、Roadmap M4及E-05。单元实现不等同完整M4验收。

## 1. 范围与当前事实

交付独立管理账号、登录/刷新/退出/改密、三种当前角色、一次性首SUPER初始化、账号创建/停用/恢复/改角色/重置密码、持久审计和最小后台页面。首单元不实现审核发布、死信重试、Redis/ES、监控；这些业务权限在各自单元直接测试，不用虚假的占位接口宣称已经验证完整M4。

| 实现前已实际阅读的基线文件（0e213e1） | 实现要求 |
|---|---|
| `forgeoj-api/src/main/java/com/forgeoj/api/config/SecurityConfig.java` | 只有普通身份认证链，后台须明确划分请求，不能给普通principal增加平台角色 |
| `forgeoj-api/src/main/java/com/forgeoj/api/auth/AccountJwt.java` | 普通issuer=forgeoj/audience=forgeoj-browser，JWT5分钟；管理JWT另密钥/issuer/audience |
| `forgeoj-api/src/main/java/com/forgeoj/api/auth/AccountAuthenticationFilter.java`、`AccountMapper.java` | 每请求查普通会话与ACTIVE；管理员查独立表，敏感事务再次复核 |
| `forgeoj-api/src/main/java/com/forgeoj/api/auth/AccountCookies.java`、`AccountCsrfRepository.java`、`SameOriginFilter.java` | 普通Cookie在根路径；管理Cookie/CSRF独立命名和路径，保留精确同源检查 |
| `frontend/src/services/forgeojApi.ts` | 401会自动刷新普通会话；后台必须用独立service与独立跨标签刷新锁 |
| `frontend/src/router/index.ts` | 尚无/admin；兼容添加，不替换原普通账号入口 |
| `forgeoj-api/src/main/resources/db/migration/V6__ordinary_accounts.sql`、`V17__classroom_assignments.sql` | 最新版本V17，迁移追加；API列级grants，Worker不获得账号/管理表权限 |
| `forgeoj-api/src/main/java/com/forgeoj/api/ForgeojApiApplication.java` | 目前只有HTTP应用入口；初始化命令需要独立模式，不随Web启动自动执行 |

## 2. 已确定的边界

已接受：管理身份独立、无普通注册/班级/做题能力；CONTENT_REVIEWER审核、OPS_ADMIN运维、SUPER_ADMIN全后台及账号治理；普通用户后台403；首SUPER用一次性命令、无默认密码；管理动作持久审计；不能手工改WA为AC或修改用户源码。班级OWNER与平台角色完全无关。

| 已确认项 | 最终规则 | 当前状态 |
|---|---|---|
| Q1 同一管理账号能否兼任 | 每账号一种角色；SUPER覆盖全部。一人兼任审核和运维时分配两个账号 | 用户2026-10-08明确确认；role为单一受约束值，不建多角色关系表 |
| Q2 新建/重置密码及恢复 | 新建/重置后首次登录强制改密码；SUPER重置其他管理员；全体SUPER无法登录时，授权本机CLI恢复指定既有SUPER并留审计；无公开邮件找回 | 用户2026-10-08明确确认；bootstrap也须首次改密；本轮只创建一次性验收账号，不创建真实管理员 |

首版实现数值：用户名3–32 ASCII字母数字下划线、大小写不敏感独立唯一；密码沿用普通域12字符/至多72 UTF-8字节、不trim、BCrypt，无额外邮箱依赖；JWT5分钟、管理会话绝对期限8小时、不提供“记住我”。公开注册为不存在的接口，不接普通找回令牌。

## 3. 身份与权限矩阵

仅凭管理员Cookie、JWT声明或前端按钮不足以授权。所有受保护后台请求必须从当前MySQL读取有效管理账号、session和角色；JWT不携带可独立生效的权限快照。SUPER拥有所有后台能力，但仍受业务资源、生命周期、用途及审计约束。

| 当前身份 | 自己的管理会话/改密/退出 | 账号治理/审计查询 | 审核案件能力（后续） | 判题运维能力（后续） |
|---|---|---|---|---|
| 匿名/失效管理session | 401；公开认证入口可用 | 401 | 401 | 401 |
| 有效普通用户，包括班级OWNER/ASSISTANT | 后台接口403 | 403 | 403 | 403 |
| ACTIVE CONTENT_REVIEWER | 可 | 403 | 仅案件所需，受严格版本关联 | 403 |
| ACTIVE OPS_ADMIN | 可 | 403 | 403 | 仅用途允许的任务/脱敏诊断 |
| ACTIVE SUPER_ADMIN | 可 | 可 | 仍需案件关联 | 仍需重试资格 |
| DISABLED/被撤销/过期管理员 | 401 | 401 | 401 | 401 |
| 必须改密状态 | 仅认证状态、刷新、改密及退出可用 | 403 | 403 | 403 |

两域可在同一浏览器分别登录；普通操作只使用普通身份，后台操作只使用管理身份。管理ID和普通user_id允许数值相同，但不得强制转换principal或使用共享账号表。没有管理凭据且普通身份有效时，受保护后台返回403；失效/伪造的管理凭据不得通过普通身份回退而变成管理权限。普通域不接收管理JWT/refresh/CSRF。管理员只可匿名看公开题库，不能凭管理身份创建Submission、学习记录、作者草稿或成员关系。

## 4. 数据与数据库权限

下一实现单元追加 `V18__independent_admin_identity.sql`；本轮不创建SQL，不改V1～V17字节，不实际迁移数据库。最小关系：

- `admin_account`：独立id、username、password_hash、ACTIVE/DISABLED、单一role（仅CONTENT_REVIEWER/OPS_ADMIN/SUPER_ADMIN）、must_change_password、version、created_at/updated_at；状态/角色CHECK及用户名唯一约束。
- `admin_login_session`：随机sid、admin_id、created_at、绝对expires_at、revoked_at；FK仅指admin_account。
- `admin_refresh_token`：随机256-bit refresh的SHA-256、session_id、created_at、consumed_at；FK只指管理session。
- `admin_policy_fence`：单一预置行，永久bootstrap完成事实；串行化首次初始化、账号权限修改和最后SUPER保护，不借用班级/作业fence。
- `admin_audit_event`：随机event_id、动作、执行时间、actor类型ADMIN/BOOTSTRAP/LOCAL_RECOVERY/AUTH_ANONYMOUS/DENIED_USER、actor_admin_id（非管理执行者可NULL）、目标类型/id、结果、理由及白名单前后状态/role/version、内部requestId或CLI correlationId；无密码、散列、Token、Cookie、SQL或源码字段。普通身份拒绝事件不伪造admin_id，匿名失败不保存原始用户名。
- 创建等可重放写使用调用者+clientRequestId唯一约束和规范请求摘要，响应丢失后重试返回原元数据；摘要必须排除密码。携带相同幂等键重放时不再次修改密码；创建含密码请求先查已提交记录，再处理新凭据，不保存原密码。对不同目标/角色等公共字段复用键返回409。

API只授管理表必要SELECT/INSERT和明确UPDATE列；session只更新revoked_at，refresh只更新consumed_at；审计只SELECT/INSERT、不UPDATE/DELETE。fence只SELECT与可锁定id列权限，bootstrap标记由短时初始化/恢复DB身份维护，不能由Web任意修改。Worker和普通数据库业务账号不得获得管理表权限；既有API禁止改判题结果/隐藏测试的约束不放宽。

新管理表时间一律按UTC定义，SQL使用UTC_TIMESTAMP(6)，Java按显式UTC解释；会话到期和数据库比较来自同一UTC事实。不得为了管理域改变全局数据库时区、重写既有Submission/作业时间或默默把旧数据当UTC。管理员事务失败与旧数据保留、UTC/JWT期限边界需要真实MySQL回归。

审计持久化不意味着对拥有DB迁移权限的人不可篡改；实现期验证API/Worker真实grants，保留该信任边界。暂不做审计删除、自动清理或导出。

## 5. 事务、撤销与并发

所有管理状态变化按统一顺序：管理fence → 按id排序锁定actor/target账号 → 会话 → refresh/幂等记录 → 审计。普通认证读取无需fence；管理登录、刷新、改密、退出、账号治理在敏感事务里再次检查当前状态/角色/session期限，避免过滤器通过后被撤权的竞争。事务外认证读取不是授权提交点。

创建、修改角色、停用/恢复、重置密码都要求当前SUPER和理由，写入版本CAS；重放在重新鉴权后返回原结果，不为失效actor开放历史结果。目标身份改变后同事务撤销其全部旧session；恢复不会使旧session复活。自身改密/全部退出撤销所有旧session并清Cookie，需要重新登录；当前退出仅撤销当前sid。新密码不得与当前密码相同。

保护至少一个ACTIVE SUPER：最后一个禁止被停用或降级（409）；至少两个时可调整自己，但自身降级立即失去SUPER会话。两个SUPER并发互相停用/降级在同一fence下最多一个成功，剩余账号仍有效。账号只能软停用，不物理删除历史。避免管理员维护操作修改普通用户/题目/Task/Submission事实。

管理写与成功审计同事务；审计写失败则整个写回滚并503，不能出现状态已变但记录缺失。被拒绝操作和登录失败只保存有界白名单事实，由独立事务记录；不能记录原始账号输入或异常文本。入口限流先执行，超限请求不无限写匿名审计，以有界计数/固定日志记录；这不豁免已授权敏感阅读与处置的持久审计。没有成功授权前不写SUCCESS。敏感阅读由各业务单元在返回内容前完成关联授权和审计；本单元账号列表/审计查询本身也先写白名单访问事件，审计失败503，不返回凭据或session摘要。

## 6. 认证、Cookie与请求隔离

拟添加只匹配 `/api/v1/admin/**` 的高优先级SecurityFilterChain与独立 `AdminPrincipal`。其余请求维持普通认证链。使用独立JWT签名配置 `forgeoj.admin.jwt.secret`，开发临时随机、生产外部随机至少32字节；固定issuer=`forgeoj-admin`、audience=`forgeoj-admin-browser`，严格校验subject/sid/iat/exp和最长5分钟。生产禁止普通与管理员密钥相同；任何数据库认证错误返回503，不降级匿名后绕过校验。

Cookie命名为 `FORGEOJ_ADMIN_ACCESS`/`FORGEOJ_ADMIN_REFRESH`/`FORGEOJ_ADMIN_CSRF`，host-only、HttpOnly、SameSite=Strict，生产Secure，path=`/api/v1/admin`。不把凭据放localStorage、响应JSON或URL。CSRF经后台session响应返回headerName/token，写操作校验管理CSRF与精确Origin；普通CSRF即使有效也不通过后台请求。

refresh轮换、消费摘要永久记录至所需重用检测期限；已消费令牌重用撤销该管理sid，事务应提交撤销后再返回401，不能因为抛异常回滚撤销。单次JSON请求失败不无限重试写；刷新跨标签使用独立Web Lock，缺少支持时短JWT到期要求重登录。普通和管理退出/刷新互不影响。

首版进程限流管理域独立键空间，借现有有界算法：登录IP30/5min、规范账号标识10/5min，刷新IP60/5min、账号治理actor30/5min；不信任任意X-Forwarded-For。有限map容量满时保守429，不淘汰活跃计数以绕过；这些不是分布式保护，第5步再接Redis及故障回退。失败登录统一401，不区分账号不存在/停用/密码错。

## 7. HTTP与页面合约

以下为本单元路由，实测结论按验收文档记录。公开登录、认证状态、刷新、幂等当前退出是认证入口，不提供后台业务数据；普通用户可取得后台CSRF并使用另一个管理员账号登录。有效普通身份访问任何受保护后台业务、本人管理改密或全部退出接口均403。公开session未管理登录时仅authenticated:false/admin:null/csrf，不把普通身份当管理员；GET本身不授任何业务权限。这保留同浏览器两域并存，避免普通Cookie阻断管理员登录。

| 路由（前缀 `/api/v1/admin`） | 合约 |
|---|---|
| GET `/auth/session` | authenticated、admin{id,username,role,mustChangePassword}或null、管理csrf；无passwordHash/token/sid |
| POST `/auth/login`、`/auth/refresh` | 模糊401，成功200 session白名单和管理Cookie；普通账号凭据不认作管理员 |
| POST `/auth/logout`、`/auth/logout-all` | 当前/全部管理session撤销，204；退出幂等但仍CSRF/Origin校验 |
| POST `/auth/password/change` | 当前密码和新密码，成功撤销所有管理session、清Cookie、204；首次登录的必须改密状态可用 |
| GET `/accounts?page=1&size=20` | 当前SUPER；最多50/页，id/username/role/status/mustChangePassword/version，不返回任何密码/session资料 |
| POST `/accounts` | 当前SUPER，clientRequestId、username、role、初始密码、reason；201元数据，同键重放不重复创建或展示密码 |
| PUT `/accounts/{id}/role` | expectedVersion、role、reason，200新元数据；目标所有session撤销 |
| POST `/accounts/{id}/disable`、`/restore` | expectedVersion、reason，200元数据；最后ACTIVE SUPER保护 |
| POST `/accounts/{id}/password/reset` | expectedVersion、新临时密码、reason，204，撤销目标会话并要求改密；SUPER不能用重置代替本人当前密码校验 |
| GET `/audit-events` | 当前SUPER，有界分页和动作/actor/目标/时间过滤，最多50/页；白名单，无原始敏感输入 |

无HTTP bootstrap/recover/register。鉴权错误空体401/403；有效角色请求的不存在/非法目标统一404，CAS/最后SUPER/幂等冲突409，参数400，限流429，不可用503。参数错误可能先于角色判断，权限测试必须使用有效参数。

最小前端 `/admin/login`、`/admin`、`/admin/accounts`、`/admin/audit`，独立 `adminApi.ts` 和管理状态；保留原入口。在本单元仅展示会话、改密/退出和已实现的账号/审计导航，不展示虚假的审核/重试数据。降级/停用/401/403、身份或路由变化清除后台数据并取消/丢弃晚到响应；普通页面仍使用普通service。页面隐藏按钮只是提示，服务器才是权限入口。

## 8. 一次性初始化与恢复

Java CLI模式在启动Web容器、调度器、Rabbit消费者或普通dev种子之前分流；专门连接已完成迁移的目标MySQL，验证V18成功记录。没有密码命令行参数、默认管理员或示例真实口令，不从SMTP文件读取配置。Console.readPassword双次输入、确认目标数据库；无交互控制台则明确拒绝，不回退明文stdin/参数。初始化所需的短时DB配置由操作者自己的安全环境提供，不记录连接口令。

`admin-bootstrap`：指定规范用户名，取得fence锁，要求bootstrap永久事实未完成且无管理员；原子创建首ACTIVE SUPER并must_change_password=true、初始化完成事实和BOOTSTRAP成功审计。并发两个初始化只能一个成功；重复/已有管理员拒绝，不重置密码，不清标记，不随Web应用启动补建。

`admin-recover`：本机DB维护权限、明确既有SUPER目标id、理由和确认，只重置该账号密码/恢复ACTIVE并must_change_password=true、撤销该账号所有旧session并LOCAL_RECOVERY审计；不创建第二个首SUPER、不改普通数据、不删除历史或bootstrap事实。本机DB权限是高信任运维能力，恢复命令本身不构成远程找回授权。数据库不能判断操作者是否忘记密码；因此必须由操作者确认所有SUPER无法登录、明确授权恢复，不由服务自动判定并执行。

实际命令、维护进程配置和权限说明见 [CLI操作说明](M4-ADMIN-CLI.md)。本轮不要求用户提供真实管理员密码，也不创建真实账号；实际CLI验收只在一次性数据库执行，最终证据以验收记录为准。

## 9. 实现与验收顺序

1. Q1/Q2已确认，下一实现单元追加迁移与最小grants；真实MySQL从V17升级与空库安装，验证旧数据、Worker/API越权SQL。
2. 先写直接失败回归：双域交叉Token/CSRF/相同id、普通用户后台403、当前角色与session变化、停用/恢复旧会话不复活、刷新重用撤销不回滚、最后SUPER竞争、审计失败全事务回滚、重复初始化无作用。
3. 实现CLI/身份/HTTP/账号/审计，CLI创建与恢复只使用一次性数据库；命令不产生Web监听、不启动Worker、不影响Codex。
4. 添加最小后台UI及前端权限/晚到响应/跨标签刷新测试；保持既有97项前端及普通认证/判题/班级流程。
5. 固定Linux全量后端与前端检查；实际页面使用普通用户、审核员、运维、SUPER独立账号验证登录/权限/首次改密/停用撤销与保留历史；实际公开判题和M3主要回归。
6. 记录实际构件SHA/冻结输入/HTTP矩阵/SQLgrants拒绝/持久审计/日志脱敏与零残留。页面使用已有标签或可保留标签，不关闭最后Codex内置标签，不复现客户端崩溃。

第一步设计及第2步实现已完成，真实检查及当前输入关联通过，身份单元[VERIFIED](M4-ADMIN-IDENTITY-VALIDATION.md)。V18为实际追加迁移；无新依赖、性能数字、生产初始化、外部邮件或Release结论。完整M4仍需[其余步骤](M4-IMPLEMENTATION-PLAN.md)。
