# ForgeOJ：M4 第6步设计与额度检查点

2026-10-10交付事实：第5步实现3abd1d9及检查点70042f87b6c79ff3a73c7d9c0b3032afb9ff22be已推送到chiye0384-dot/ForgeOJ的feat/m2-accounts，远端已核验；搜索设计a6d6295为本地文档检查点，交付SHA以当前Git/远端为准。

2026-10-10许可确认：在解释Elasticsearch/OpenSearch及ELv2影响后，用户明确回复“按推荐来”，已批准官方Elasticsearch9.5.3默认发行ELv2、免费Basic、仅ForgeOJ内部公共题搜索、保留原许可告知；ForgeOJ自有源码保持Apache-2.0。D-047已接受，不再询问该选择。许可阻断解除，尚未拉取/运行镜像、增加业务代码或V24。最新五小时额度已用85%（剩约15%），按照用户额度不足及时停止规则，停在已批准设计检查点；不是新的自动审批拒绝。下一次额度足够且用户继续时直接按M4-SEARCH-DESIGN的实施顺序，从V24公开版本/Outbox与真实MySQL合约测试开始，只完成第6步后交付并停。既有第5步VERIFIED、完整M4/E-04 IN_PROGRESS、第7步/M5/Release未开始。

2026-10-10，Asia/Shanghai。本文件名保留以维护既有入口；当前只读启动事实是第5步VERIFIED、完整M4 IN_PROGRESS。此前“第4步未开始”“第5步进行中”和运行中session都已过时，不恢复旧栈/URL。

实际仓库D:\Java项目\ForgeOJ，父目录D:\Java项目。先核验git status/branch/log、远端同名分支与保护文件摘要，保留无关文件。不得把旧构建通过当当前输入一致。

## 必读顺序

1. 根AGENTS.md及用户桌面连续性规则。
2. [当前交接](M4-NEXT-CHAT-HANDOFF.md)、[Redis设计](M4-REDIS-DESIGN.md)、[最终验收](M4-REDIS-VALIDATION.md)、[机器证据](evidence/m4-redis/verification.json)。
3. [七步计划](M4-IMPLEMENTATION-PLAN.md)、Requirements、Roadmap、D-046、L-043及Resume-Evidence-Matrix。

M-1～M3 VERIFIED；M4第1步设计完成，第2～5步分别VERIFIED；第6/7步未开始，完整M4 IN_PROGRESS。M5/V1.1/Release/RESUME_READY未完成。D-045的人工重试终身一次、原接受时间/当前权限复核、内容当前快照限定已确认并实现；D-046的MySQL权威、缓存/限流辅助规则已验收。

固定Linux接受构建：`target/forgeoj-linux-20261010-081555-deb02bb5`，API289/50 suites、Worker133/23 suites，零失败/错误/跳过；前端124/24及全部检查通过。390业务/前端输入匹配，82个前端输入同时匹配挂载和实际提供内容。API SHA256 `4a13a56a97d531ef3457216e10fabfdaa3f903e0027563b1f009faa54188959d`；Worker `e142de6b117ad1edc0a1554c4c71db0a720d4e5100930b0379240a0985f9e3e2`。

接受同JAR重放：`target/forgeoj-e2e-20261010-090029-fb7dc71a`。Redis专项108项HTTP、双API共享预算、7个同键回执仅1个Submission/task/event、Redis健康及暂停期间各一次真实AC、撤销/降级/停用时旧JWT仍未过期但均被拒绝、暂停期间至少2个失败失效事件恢复后全部交付、清空后旧回执保持且本机预算不重置。权限DB失败真实503空body/no-store且finally恢复授权。4项SQL拒绝、7队列全空、原作业截止前接受/截止后完成、Worker提交后ACK通过。浏览器6张截图/5份页面快照：OPS表、撤权后账号/表清空、暂停期间匿名题库、降级审核页可用及运维页拒绝；原异常列表为空，没有选中任务详情，不宣称详情清空。

所有拥有者容器/卷/网络/镜像、builder、Testcontainers和managed sandbox精确清理全0。旧URL不可用，tab1保留在about:blank并标记deliverable，不能关闭它或Codex/ChatGPT。实际22个嵌套依赖JAR摘要匹配23项解析依赖目录（空starter不打包）。最终机器证据 `docs/evidence/m4-redis/verification.json`。仅证据审计器在回放后修正published标量子查询的数值1类型，原冻结脚本与当前摘要均保留；其余运行辅助及全部业务输入未变化。首次健康页只有截图，缺失文本未列入交付，未补造页面事实。

## 下一步与授权边界

本单元起点4eb49c0e4c88bd6624bdc1a63995953d8405fa09，分支feat/m2-accounts，远端https://github.com/chiye0384-dot/ForgeOJ.git。验收完成后按既有授权提交/推送这个功能分支，然后停止；交付SHA以当前Git/远端核验，不把起点当当前提交。用户本轮已授权第6步，许可已确认，当前仅停在额度检查点；第7步监控、M5、PR/main/tag/Release/RESUME_READY、真实数据库/SMTP/管理员初始化均不启动。用户额度不足及时停止的规则继续有效。

保护文件只能Get-FileHash：docs/M2-NEXT-CHAT-HANDOFF.md e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054；.smtp.qq.local 6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907。不读/输出/复制/暂存/覆盖/删除，禁止git add .、reset、clean。桌面连续性规则始终有效。
