# M4 第4步：运维查询与有限人工恢复

2026-10-09，本单元已[VERIFIED](M4-OPERATIONS-VALIDATION.md)，设计规则以用户接受的D-045为准。实施起点 `02b18fed1ba1c8da9da128f12acba0617c7efcba`，分支 `feat/m2-accounts`。本文件解释设计，验收证据另列。完整M4仍IN_PROGRESS，Redis/ES/监控/M5/Release不在本单元。

## 1. 已核对的真实状态机

| 用途 | 活动状态 | 平台终态 | 用户结果 | 恢复不变量 |
|---|---|---|---|---|
| 正式判题 | Task QUEUED/RUNNING/RETRYING/WAITING_RETRY；Submission以RUNNING呈现内部等待 | Task DEAD_LETTER，Submission SYSTEM_ERROR且verdict NULL | FINISHED和八种verdict，均不可人工重试 | 原Submission、源码/版本/测试/策略和接受时间保留；attempt递增、旧token失效 |
| VALIDATE | QUEUED/RUNNING/WAITING_RETRY | SYSTEM_ERROR | FINISHED/PASSED或FAILED；只有真实双AC才PASSED | job/snapshot/用途绑定；没有正式Submission/AC副作用 |
| OUTPUT_PREVIEW | 同内容任务 | SYSTEM_ERROR | FINISHED/referenceResult，validation/solution永久NULL | 只运行参考；生成输出还需作者明确确认，不自动覆盖 |
| SELF_TEST | QUEUED/RUNNING/WAITING_RETRY，QUEUED可取消 | SYSTEM_ERROR | FINISHED/SUCCESS或执行错误，不是AC | 终态payload24小时隐藏/清理；生命周期/关闭attempt保留，不能恢复不存在的payload |

Worker领取均使用用户共享quota（1运行+3排队）、有界attempt、当前lease/fence；数据库提交后ACK。内容/自测的delivery_sequence与attempt_count不同，配额延后不消耗执行次数。正式任务的死信与Submission状态不同，不能按一个字段统一改写。平台SNAPSHOT_INVALID/契约损坏不能按临时平台故障重试。

## 2. 查询和权限

OPS_ADMIN/SUPER_ADMIN允许运维查询及按资格恢复；CONTENT_REVIEWER、有效普通账号及班级OWNER/ASSISTANT/MEMBER一律403；失效管理员401、初次须改密403。沿用独立JWT/Cookie/CSRF/MySQL授权和管理fence，敏感事务内再次复核当前role/session。读和成功处置先持久审计，审计失败503且回滚；拒绝独立审计。

有界分页（默认20，最多50），类型/状态/任务ID精确筛选。只返回任务/Submission/snapshot ID、用途、状态/版本、attempt计数/上限、固定失败码、时间、租约是否过期、Outbox事件ID/类型/序号/计数/发布与失败时间。attempt列表不返回leaseToken、worker配置、failure_message；Outbox不返回payload。没有任意SQL、用户/参考源码、隐藏输入输出、Cookie/凭据、Docker控制或清理接口。API不获得修改verdict/源码/冻结数据/lease/attempt的权限；Worker不获得管理/治理/恢复请求表权限。

V20为初始attempt读取迁移，V21增加不可变凭证和恢复必需状态/预算列权限；V22撤回V20表级SELECT，只授予上述查询元数据列，API数据库身份本身也不能读取attempt的lease_token、worker_id或内部failure_message。历史迁移不重写。恢复历史有界查询保留原失败码/时间、预算、状态版本与事件关联。

默认异常集合包含平台终态、重试等待、过期运行租约及关联失败Outbox；详情用于核对某个精确任务的元数据。列表与详情均no-store，前端401/403及身份改变清空元数据、丢弃晚到响应。

## 3. 已确认的持久行为（D-045）

2026-10-09用户明确选择“三项都按推荐继续做”，接受以下规则：

1. 沿用原任务/Submission，每任务一生最多人工追加1次执行，计数不重置，旧平台失败保留在attempt/审计；最大执行次数10，不突破此安全上限。
2. 以原接受时间计算恢复后的作业AC；自然硬截止不拒绝既有提交恢复，但当前账号/成员/参与者有效，题目/班级未归档作废，作业未取消停止；过期自测payload拒绝。
3. 只恢复当前同版本DRAFT或当前PENDING案件最新VALIDATE，拒绝已改版本/归档/已结束案件的历史内容任务。

恢复只适用于耗尽自动执行次数的临时平台失败；SNAPSHOT_INVALID、用户判题结果和活动任务拒绝。OUTPUT_PREVIEW仍需作者明确确认输出。相同管理员请求键绑定用途/目标/版本/理由；同请求重放只返回原凭证，当前管理权限仍须有效。

## 4. 事务、投递和验证

人工操作必须reason、expectedVersion、clientRequestId及规范请求摘要绑定；同键同请求重放不再次执行，变更目标/版本/理由409；重放仍须当前授权。旧attempt和快照不可改写，恢复操作、唯一请求凭证、Outbox及成功审计在同一事务；失败不能留下半恢复。重复/并发只产生一个恢复事件，服务端根据当前持久状态判资格。

Outbox发布耗尽是传输失败，不能伪装成新的平台执行失败。若恢复投递，必须同一个已失败未发布事件、严格当前四字段合约、保留publish_attempts和旧失败事实、最多一次人工恢复，不修改payload、不另造任务、不抢活动lease。publisher仍负责confirm/return/CAS，Worker仍负责幂等和提交后ACK。

原有三个死信队列没有自动消费者；成功恢复不会删除旧DEAD_LETTERED事件或对应RabbitMQ消息。验收要求执行/重试队列无积压、所有队列unacked为0，并把保留死信数量与已发布的旧失败事件逐用途核对，不以清空历史死信制造成功。此单元没有死信自动清理、任意消费或删除接口。

真实一次性MySQL验证升级/最小grants、角色撤销、审计失败回滚、重放/并发/配额、各用途资格和冻结数据不变、作业时间及历史；固定Linux全量与同JAR Worker/实际多身份页面、真实失败→恢复→真实结果及消息/日志/队列/故障检查。最终source/JAR/browser/evidence关联、精确资源清理、文档和功能分支交付后只提升第4步；完整M4仍IN_PROGRESS。
