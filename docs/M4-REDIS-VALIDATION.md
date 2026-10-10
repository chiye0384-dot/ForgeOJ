# M4 第5步 Redis 与故障降级验收

2026-10-10，**VERIFIED（仅第5步）**。完整M4仍IN_PROGRESS；设计见[M4 Redis设计](M4-REDIS-DESIGN.md)、D-046，安全边界见L-043。

固定Linux接受构建：`target/forgeoj-linux-20261010-081555-deb02bb5`，API289/50 suites、Worker133/23 suites，零失败/错误/跳过；前端124/24及全部检查通过。390业务/前端输入匹配，82个前端输入同时匹配挂载和实际提供内容。API SHA256 `4a13a56a97d531ef3457216e10fabfdaa3f903e0027563b1f009faa54188959d`；Worker `e142de6b117ad1edc0a1554c4c71db0a720d4e5100930b0379240a0985f9e3e2`。

接受同JAR重放：`target/forgeoj-e2e-20261010-090029-fb7dc71a`。Redis专项108项HTTP、双API共享预算、7个同键回执仅1个Submission/task/event、Redis健康及暂停期间各一次真实AC、撤销/降级/停用时旧JWT仍未过期但均被拒绝、暂停期间至少2个失败失效事件恢复后全部交付、清空后旧回执保持且本机预算不重置。权限DB失败真实503空body/no-store且finally恢复授权。4项SQL拒绝、7队列全空、原作业截止前接受/截止后完成、Worker提交后ACK通过。浏览器6张截图/5份页面快照：OPS表、撤权后账号/表清空、暂停期间匿名题库、降级审核页可用及运维页拒绝；原异常列表为空，没有选中任务详情，不宣称详情清空。

所有拥有者容器/卷/网络/镜像、builder、Testcontainers和managed sandbox精确清理全0。旧URL不可用，tab1保留在about:blank并标记deliverable，不能关闭它或Codex/ChatGPT。实际22个嵌套依赖JAR摘要匹配23项解析依赖目录（空starter不打包）。最终机器证据 `docs/evidence/m4-redis/verification.json`。仅证据审计器在回放后修正published标量子查询的数值1类型，原冻结脚本与当前摘要均保留；其余运行辅助及全部业务输入未变化。首次健康页只有截图，缺失文本未列入交付，未补造页面事实。

默认关闭Redis，MySQL决定当前权限、正式提交幂等及配额。只缓存匿名公共题/官方题单与username；故障回退MySQL并保留各实例本机限流，权限数据库失败503。Worker无Redis凭据，API/Redis无Docker socket。本次不证明Redis集群、性能、生产部署或完整M4。

| 门禁 | 结果 |
|---|---|
| 固定Redis/MySQL专项 | 11项通过，包含真实权限REVOKE、503/no-store/匿名200、缓存JSON null与损坏、同键并发/配额、失效回滚/乱序、暂停恢复；在最终All再次通过 |
| Linux全量 | API289/50、Worker133/23，零失败/错误/跳过；前端124/24及type/lint/format/test/build通过 |
| 同JAR故障/恢复 | 108项HTTP全通过，双API、两次真实AC、2个失败失效事件全部重放、原同键回执保持 |
| 浏览器 | 6张实拍/5份实际快照，OPS撤权清空、匿名题库故障可用、降级审核允许/运维拒绝 |
| 持久事实/运行边界 | 原作业时间/绑定/资源/租约、提交后ACK、4项SQL拒绝、7队列空、390输入/82实际前端/同JAR/22依赖摘要一致 |
| 精确清理 | 拥有者容器/卷/网络/镜像/builder/Testcontainers/managed sandbox均0；保留about:blank标签 |

## 复现

完整日志保留在接受构建的backend.log/frontend.log、target/m4-redis-linux-all-header-final.log及回放原事实；交付证据仅白名单文件、脱敏日志gzip、截图和哈希，不导出私有Cookie/凭据。

```powershell
& tools/validation/Verify-FixedLinux.ps1 -Scope All -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
# 读取新成功构建的真实摘要后启动独立可销毁栈：
& tools/validation/Replay-FixedLinux.ps1 -Action Start -EnableRedis -BuildDirectory <fresh-build> -ApiSha256 <actual-hash> -WorkerSha256 <actual-hash>
# Matrix→WorkerStop→Permissions→WorkerStart→Library→Learning→Classroom→PrivateProblems
# Assignment Setup/Queue/原UTC截止后Finish→Teacher Setup→真实登录预算静默窗口
# Redis Setup→fixture交互Bootstrap→Admin Setup→Healthy→立即GuardFailure→实际OPS浏览器
# Pause→Outage→Pending→撤权清空与匿名页面→ResumeFlush→Recovered→降级浏览器
# Warm→Audit→CaptureRuntime→about:blank标记保留→Replay Stop→CleanupSnapshot
& 'D:/Node.js/node.exe' tools/validation/Verify-M4RedisEvidence.mjs target/forgeoj-linux-20261010-081555-deb02bb5 target/forgeoj-e2e-20261010-090029-fb7dc71a docs/evidence/m4-redis
```

最后命令已实际退出0；回放目录已清理，仅保留事实供重审。新回放必须使用新目录及实际构件，不能恢复本次旧URL。Bootstrap只使用唯一fixture迁移账号，所有登录均公开测试凭据。没有真实数据库、SMTP或管理员初始化变更。官方题单没有写API，维护SQL须按设计同事务递增代数/追加失效事件。

## 未接受运行与修复记录

- 2026-10-10恢复审批后，首次启动因Docker尚未运行失败，未创建栈；启动已安装Docker后重试。`target/forgeoj-e2e-20261010-075701-5cb10e09`权限回放遗漏先暂停Worker的夹具前提，提前完成的提交使取消返回409，并令后续固定学习记录数从9变10；该栈全部弃用和清理，不改变原断言。清理辅助脚本补齐可选Redis profile，实际清理快照全部0。重新使用同一已通过Linux JAR启动干净夹具，业务源码未变化。
- 教师Setup先将第三人设为ASSISTANT，随后作业Setup原本要求该人尚未加入，导致真实200与预期404冲突；通过新增仅用于可销毁夹具的ResetAssistant真实退出接口恢复LEFT，退出后的教学接口应404（新辅助检查初版错误预期403已修正）。原作业权限、成绩和晚加入断言保留。首次作业已真实完成的尝试保留，不删除历史制造通过。推荐干净复现先完成Assignment，再Teacher Setup，避免这两个夹具的前提相互影响。
- 多次原账号登录触发真实300秒预算429；等待原窗口，不增加倍率、不刷新预算。Bootstrap初版尝试跨容器root连接被拒绝，改用隔离栈已存在的迁移维护账号；没有给root新增远程授权，没有将维护凭据交给API/Worker。CLI公开fixture密码通过交互隐藏输入，未放入命令行或证据。
- 作业Finish辅助脚本按PowerShell自动解析的DateTime保留Kind构造DateTimeOffset，避免UTC字符串转本地字符串后误解为提前8小时。实际使用Node UTC时间确认原截止已过后才恢复Worker；数据库接受/完成时间仍需独立证据审计。
- 最新构件回放`target/forgeoj-e2e-20261010-084754-1cfd24d5`基础/私有题/作业/教师通过，但等待窗口安排在Admin Setup之后，普通learner登录在该阶段已达到预算，实际返回429，管理流程已有部分改密不能直接重跑。该夹具弃用并精确清理；保持当前通过的构件，下一次把真实300秒静默窗口移至Teacher与Admin Setup之间，不放宽或刷新计数、不用SQL重置改密/审计事实来伪造首次流程。
- `target/forgeoj-e2e-20261010-080112-15ffe30c`完成干净基础回归、教师131项、作业117+9+35项、管理61项及Redis健康50项。正向身份缓存下撤回会话表SELECT实际返回503，但缺少no-store，回放如实失败；终于在AccountAuthenticationFilter的DB异常路径补no-store/清空上下文，并在原Redis专项中追加实际HTTP503空body/响应头/匿名公开200断言。该栈精确清理、旧构件不再接受用于最终交付，重新执行完整Linux All，不删或跳过失败断言。Redis夹具准备改为存在时不覆盖账号/配额锁，便于辅助工具重跑。
- 初始数据库触发器迁移遇到MySQL1419；弃用触发器，治理业务事务显式递增代数/写失效事件。未授予SUPER、未改变全局binlog信任设置。旧迁移失败构件不接受。
- 管理员夹具补齐原有必填时间；旧username可含连字符，显示身份校验支持既有数据库身份。退出后清空CSRF的浏览器请求按403断言，保留旧JWT及CSRF的独立客户端按401断言；没有删除撤销检查。
- 源码复核发现身份缓存JSON null可触发500；补空值检查，并以真实缓存null/损坏的请求验证回退。旧全量 `target/forgeoj-linux-20261009-152816-95e1474c` 主动中断，只停止核对过的本次builder；未接受其未完成结果。最新全量从修正源码重新构建。
- 空密码探针首轮直接执行Compose序列化的双美元符号，转义错误导致未退出；该无网络唯一容器精确停止/移除。修正为Compose运行时的单美元展开后，空密码拒绝且无残留。没有在真实开发栈上试启动。

依赖来源及原始POM/JAR/告知摘要见[23项依赖事实](evidence/m4-redis/redis-dependencies.json)、U-011。实际Redis7.2.16镜像固定摘要，默认仅开发/fixture用途；不把本次记录当完整Release依赖、安全或许可证审计。
