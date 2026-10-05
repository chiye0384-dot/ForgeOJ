# M2 参考输出预览与确认

2026-10-04，基线5105cf6，单元VERIFIED（2026-10-04实际验收，2026-10-05整理），依据Requirements7.1；完整M2仍IN_PROGRESS。

作者对当前已保存草稿显式生成输出。执行复用独立内容job/snapshot/attempt/Outbox和共享quota，新增不可变execution_kind=OUTPUT_PREVIEW，现有VALIDATE保持原行为与结果字段。预览仅运行参考程序，忽略原正确输出，不执行题解；预览终态只保留referenceResult，validationStatus/solutionResult永久NULL，不能进入验证列表或送审门槛。四字段消息仍指向冻结job，Worker从数据库获取不可变用途，API无Docker。

冻结参考程序、已保存输入/原输出及资源，最多100组；每个生成输出最多1MiB，输入加生成输出最多16MiB，限制同时受原输出额度约束。严格UTF-8、禁止NUL；任一用例编译/运行/资源/编码失败，整体无可确认输出。全部成功输出在持有有效lease/attempt下与终态同事务压缩保存，写回后ACK；失败重试和旧attempt清理复用既有机制，禁止旧lease写输出。

POST本人草稿/output-previews精确{expectedVersion,requestId}，owner用途/版本绑定幂等；GET列表/详情no-store，仅本人能看输入/旧输出/生成输出。预览不写可变测试，也不制造Submission/AC/PASSED。POST/{job}/accept精确{expectedVersion}是显式确认：仅当前同版本DRAFT且无待审、自然FINISHED参考AC且完整可校验输出。按quota→draft锁顺序原子替换全套测试、递增版本并记录唯一确认receipt；重复确认仅重放原receipt，不覆盖后续修改。旧版本/归档/待审返回409，失败预览不能保存；历史永久保留且引用草稿不能物理删除。

前端显式生成、查看/刷新、展示逐组输入/原输出/生成输出，确认按钮清楚说明替换全部已保存答案并使旧验证失效。请求不确定复用requestId，不自动接受/覆盖409；切身份/草稿/版本丢弃迟到私有响应。未保存编辑不参与执行，确认后保留本地题面/代码，重新载入测试与权威版本。

验证覆盖V12→V13旧数据/CHECK/权限、真实HTTP owner/CAS/用途隔离/幂等/确认原子性、Worker真实多例输出/失败/UTF8/额度/lease栅栏/ACK/恢复、前端确认与迟到隔离、全新固定Linux、精确构件实际页面和既有判题回归、日志/队列/权限/精确清理。本轮真实结果见 [验收](M2-OUTPUT-PREVIEW-VALIDATION.md)；自测、SMTP服务商和M4受控审核发布不在本单元。
