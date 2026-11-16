# M2 作者内容正式验证任务

2026-10-04，实施基线 59afa5a，本验证单元状态 VERIFIED，完整内容与 M2 仍 IN_PROGRESS。固定 Linux 283/50、真实页面双结果、实际 Worker SIGKILL 后自然租约恢复、六队列/权限/日志审计与精确清理见 [验收记录](M2-CONTENT-VALIDATION-JOBS-VALIDATION.md)。依据内容设计与 Requirements 7.2/7.3；这一步验证参考程序和独立题解，仍不提供普通用户审核或发布入口。

作者显式提交 `{expectedVersion,requestId}`，事务复核当前会话、锁同一 user_judge_quota_lock 与本人 DRAFT，检查完整题面/来源/样例/思路、两份非空 Java21 程序及非空测试。冻结 metadata、代码、压缩测试、大小/摘要、资源与固定执行策略；同一请求幂等，旧版本 409。Worker 只读取独立不可变快照，不读取可变作者表。快照的 draft 外键和显式引用检查阻止已产生记录的草稿物理删除，归档保留记录。

验证任务与正式 Submission 分开，参考和题解分别在已有正式受限 Docker sandbox 运行同一冻结测试。只有两个实际结果均 ACCEPTED 才 PASSED；用户程序不通过为 FAILED，平台异常为有限重试/最终 SYSTEM_ERROR。验证结果不制造 Submission/AC，不写公共题或题解快照；修改后的草稿不会复用旧版本 PASSED。

采用独立任务/attempt 表，复用持久 Outbox 的独立 aggregate_type 和严格四字段 CONTENT_VALIDATE 消息。增加独立 durable 内容验证/死信队列，保持原正式提交四字段契约。发布须 publisher confirm 且无 returned 后 CAS 标记；失败有限退避。Worker 事务 claim/heartbeat/finish 均绑定 job/snapshot/attempt/token/未过期租约；重复交付不重复执行，提交成功后 ACK，失败不 ACK 成功。平台恢复先永久关闭旧 attempt 再允许清理旧沙箱及下一 attempt；丢失租约不可写任何结果。

共享账号 1 RUNNING +3 pending。API 正式提交/验证创建及两类 Worker claim 都使用同一 quota 行锁，查询合并两类记录。WAITING_RETRY 占运行槽，QUEUED 受满额延后且不消耗 attempt；保留已批准无额外重试预留的规则。Worker 无 user_account/学习/可变作者权限。

作者页显式验证、查看/刷新状态，显示冻结草稿版本与两份结果，编辑器和版本竞争继续保留本地内容。验证状态只来自服务器；切草稿/退出/卸载忽略迟到私有响应。固定 Linux 全量、真实作者参考成功/题解失败与双通过、编辑后旧结果失效、归档保留、跨用户拒绝、队列/Outbox/权限/日志/沙箱清理是本单元验收范围。正式送审/撤回、输出生成预览、自测与真实 SMTP 是后续单元。
