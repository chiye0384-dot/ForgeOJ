# M4 管理身份单元验收

2026-10-08，**M4第2步管理身份单元VERIFIED**。只覆盖M4第2步；完整M4仍IN_PROGRESS，第3步审核治理及运维/Redis/ES/监控未开始。设计见 [身份合约](M4-ADMIN-IDENTITY-DESIGN.md)、D-043与 [实施计划](M4-IMPLEMENTATION-PLAN.md)，实际CLI使用见 [操作说明](M4-ADMIN-CLI.md)。实现基线0e213e1，feat/m2-accounts，目录 `D:\Java项目\ForgeOJ`。

## 1. 交付边界

- V18追加独立管理账号、策略fence、session、refresh摘要、创建幂等与审计六表；旧表不改写，没有默认管理员。API只获得必要列级权限，Worker没有管理表权限；审计禁止API UPDATE/DELETE，初始化永久标记禁止API修改。
- 独立JWT密钥/issuer/audience、Cookie路径/命名、CSRF、principal、HTTP认证链和前端服务；普通身份和班级OWNER不会转为管理员。每次认证读当前MySQL，敏感事务在fence下再次复核状态、角色、session撤销及绝对期限。
- 单账号单角色，SUPER维护其他管理员；强制首次改密，创建重放不重置密码，维护使用版本CAS、理由及确认；角色调整、停用、恢复、重置和改密撤销旧会话。最后一个有效SUPER不能停用/降级，并发互相停用至多一方成功。
- 刷新旋转与重用撤销、当前/全部退出、8小时绝对会话期限；成功写和审计同事务，失败回滚503。敏感读取先写审计；401/403/409有界拒绝记录，跨站拒绝也留后台审计，HTTP事件关联服务端requestId。初始化/恢复成功记录独立CLI关联ID。
- `/admin/login`、`/admin`、`/admin/accounts`、`/admin/audit`最小页面：先改密、有限角色提示、账号操作和审计筛选；身份失效/撤权清空数据，abort/revision丢弃晚到响应；没有后续业务的虚假占位数据。

## 2. 直接回归与历史失败

最初匿名管理session合约失败：期望200，基线实际401，日志 `target/m4-admin-red.log`。随后Java测试编译的var复合声明/Jackson包名错误、PowerShell参数引用、sandbox Docker访问及Vite临时目录重命名问题分别保留在早期日志，修正后检查继续，没有删掉断言。

原生管理HTTP首次14项仅到期fixture失败：fixture把expires_at设在当前时间之后1秒，快速请求仍有效。修正为实际过去时间，保持数据库expires_at>created_at约束，新增过滤器通过后事务前撤销竞争，最终HTTP15+JWT6全部通过（`target/m4-admin-final-focused.log`）。升级fixture修正repeatable迁移数量19（17个versioned+2个dev种子）、旧表字段owner_id及受限维护用户在容器初始化时创建；原断言和额外版本数量/旧数据快照比较保留，升级/初始化竞争/恢复审计失败回滚1项通过（`target/m4-admin-migration-fourth.log`）。全部数据库为一次性真实MySQL，不使用mock权限或生产root身份做业务操作。

初始固定Linux全量 `target/forgeoj-linux-20261008-144735-22c5d4d9`：API245零失败/错误/跳过，Worker133中1项FAIL——`startingAnotherWorkerPreservesLiveSandboxThenRecoversItAfterCrash`任务已达到FINISHED/AC，随后两队列归零等待超时。该报告没有被接受，具体原因未由旧的通用超时信息确定。只给故障测试增加两队列最后观测值和查询不可用(-1)诊断，仍要求两个队列0，原20秒界限、任务/attempt/孤儿清理/保密断言不变。其API也早于管理同源拒绝及重放审计补充，不作为最终运行构件。

## 3. 最终检查与运行证据

最终机器审计 [verification.json](evidence/m4-admin-identity/verification.json) 为 `allPassed:true`。来源分别保留，不混用旧失败构件：

| 验收项 | 实际结果 | 证据 |
|---|---|---|
| 当前固定Linux全量Backend | API245/45 suites、Worker133/23 suites，0失败/错误/跳过，BUILD SUCCESS | `target/forgeoj-linux-20261008-153916-11d8804d/backend.log` 和 [逐suite数量](evidence/m4-admin-identity/test-suites.json) |
| 当前固定Linux前端 | 20文件107项，type/lint/format/build PASS | `target/forgeoj-linux-20261008-144420-e6317968/frontend.log` |
| 当前源码/运行输入 | 184 API、110 Worker/shared、72 frontend输入逐字节匹配；实际浏览器只读容器72输入匹配；5个直接执行辅助工具匹配 | [输入清单](evidence/m4-admin-identity/source-inputs.json)、[运行捕获](evidence/m4-admin-identity/admin-runtime.json) |
| 独立身份真实HTTP | 86次请求；首改密拦截/撤销、两有限角色403、普通账号403、两域同ID1独立共存、真实普通AC、refresh旧令牌重用撤销、审计 | [HTTP](evidence/m4-admin-identity/admin-http.json) |
| 实际浏览器 | 首次改密提示及重新登录、SUPER账号列表/单角色表单、REVIEWER/OPS直访accounts/audit无表、停用后清空、恢复审计筛选及详情 | [浏览器记录](evidence/m4-admin-identity/admin-browser.json) 与8份DOM/4张截图 |
| 真实交互CLI | 空库0账号/标记0；初始化exit0、重跑exit1、恢复既有SUPER5 exit0、无终端exit2；目标ACTIVE/mustChange/version4、旧session0、审计各1；API容器ID及PID6141不变 | [CLI](evidence/m4-admin-identity/admin-cli.json)、[SQL事实](evidence/m4-admin-identity/admin-cli-facts.json) |
| 最小数据库权限 | Worker管理六表SELECT拒绝；API删除账号/改初始化标记/审计UPDATE、DELETE拒绝，合计10；API无Docker/Worker或维护凭据 | [拒绝与边界](evidence/m4-admin-identity/admin-denials.json) |
| 持久审计 | 67事件，无密码/hash/令牌；包括BOOTSTRAP/RECOVER/CREATE/CREATE_REPLAY/PASSWORD_CHANGE/DISABLE/REFRESH_REUSE_REVOKED/ACCESS_DENIED；HTTP成功事件关联实际requestId日志 | [审计事实](evidence/m4-admin-identity/admin-audit-facts.jsonl)、实际API日志 |
| 原流程回归 | 8种真实verdict及WebSocket协议+另1普通AC+1排队取消，10正式/9完成/1取消；task/version/lease/attempt/outbox/commit/ACK日志对应；班级62检查、无新增正式提交；7空队列 | [回归](evidence/m4-admin-identity/admin-regression.json)、formal事实/queues/matrix/permissions/classroom记录 |
| 精确清理 | 2026-10-08 17:17:57 +08，本轮容器/卷/网络/镜像、builders/Testcontainers/managed sandboxes全0 | [清理](evidence/m4-admin-identity/admin-cleanup.json) |

这轮实际构件为：

```text
API SHA256    45fb87b26526b9ff32ff3a0b1cc1c13096aaa2b69bfb37271cd4d0892ad56e8e
Worker SHA256 a0a63dc74444269ae0b9dbc9184e6c9344577505a0a2c30488842f9fa0621055
Replay        target/forgeoj-e2e-20261008-170446-46f5ab43
```

运行证据由 `Verify-M4AdminReplay.ps1`、`replay-admin-identity.mjs`、`replay-admin-audit.mjs`直接执行，`Verify-M4AdminEvidence.mjs`重新读取实际XML、日志、源文件、CLI事实、浏览器DOM及截图和SQL元数据关联后导出。审计脚本第一次把MySQL JSON子查询返回的整数1当布尔true，随后误把直接IS NULL表达式的true也统一当1，两次均失败且日志保留；最终逐字段按实际SQL类型断言，发布=1且leaseCleared=true，未放宽任务/队列断言。`target/m4-admin-runtime-audit-third.log`、`target/m4-admin-evidence-final.log`为最后通过记录，初始辅助脚本摘要也保留在replay目录。

最终Backend历时1小时17分钟，API约1小时10分钟。终态仍包含Surefire等待30秒后关闭测试fork的警告和Hikari fixture连接清理警告；实际XML零失败/错误/跳过、Maven成功及后续精确清理均保留，不把警告抹去或推断长耗时原因。前一轮Worker队列超时具体原因仍未确认，这轮原断言通过只证明本次成功。

浏览器没有输入或提交新密码，也没有提交新账号凭据：真实密码变化和账号创建通过独立HTTP回放验证，浏览器观察首改密门禁/改密后失效/已有密码重登、表单与权限。Vue组件验证了改密提交参数、单角色/版本/理由/二次确认、失权清空及晚到响应丢弃；这不是人工完成一次浏览器改密/创建提交的证据。普通浏览器WebSocket故障回退沿用历史证据，本轮只重新运行实际HTTP/WebSocket协议matrix，不伪造旧审计需要的两次浏览器AC。CLI恢复是一次性环境的操作者演练，不声称真实发生所有SUPER失去登录能力；没有新的外部SMTP或生产Worker SIGKILL演练。

## 4. 限制与保护

参见 [L-040](KNOWN_LIMITATIONS.md)：管理fence串行化、有界账号/分页、单进程限流、持久元数据累积、短时本机DB维护身份及数据库管理员信任边界。本单元没有真实管理员初始化、部署/密钥保管、分布式性能、防数据库管理员篡改或完整M4审核/运维权限结论。E-04/E-05仍PLANNED，没有Release/RESUME_READY。

旧未跟踪M2交接和忽略SMTP文件摘要保持原值；未读/复制/暂存SMTP配置。没有关闭、重启或更新Codex/ChatGPT，不关闭内置自动化标签。失败、清理和最终构件关联已如实保留。第2步完成后停止，第3步公共题审核治理留待用户继续；当前提交/推送以Git日志和远端同名分支为准。
