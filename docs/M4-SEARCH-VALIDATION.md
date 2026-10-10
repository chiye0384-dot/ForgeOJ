# M4 第6步公共搜索验收

2026-10-10 20:30：本技术单元 **VERIFIED**。完整M4和E-04仍IN_PROGRESS，第7步监控、M5、生产部署、PR/main/tag/Release未开始。当前权威[机器证据](evidence/m4-search/verification.json)由实际构建、同JAR重放、浏览器与SQL/消息/清理证据校验生成。16:25的[构建检查点](evidence/m4-search/build-checkpoint.json)是历史IN_PROGRESS记录，不改写为最终验收。

## 实现及边界

官方Elasticsearch9.5.3免费Basic/ELv2，仅作ForgeOJ内部公共题搜索；原LICENSE/NOTICE及摘要见[上游记录U-012](UPSTREAM-AND-LICENSE.md)。ForgeOJ自有代码Apache-2.0，现有JDK21 HttpClient/Jackson3适配，无新增ES客户端Maven依赖、插件、付费服务或云费用。默认搜索关闭，启用时HTTPS和受限命名空间；临时证书仅供测试。

V24追加公开版本、独立搜索Outbox、租约/有限重试/死信、受审计管理重建和独立有限死信补发。MySQL事务产生版本事件并保留全部失败事实；旧消息以版本/代数及租约隔离，收到Rabbit确认且SQL终态确认前不得ACK。索引仅含公共题白名单，私有题、源码、隐藏测试、题解和个人学习信息不入索引。ES候选经当前MySQL公开状态与版本复核；滞后、错误、数量/UUID/内容不匹配或超限整体回退标题搜索，固定提示正文暂不可用。运维仅OPS/SUPER访问，当前角色、改密、会话、CSRF/Origin、幂等与审计均复核。

上限与剩余限制见[L-044](KNOWN_LIMITATIONS.md)：1000候选、256KiB ES响应、100种标签拼写，有界数据库复核；高亮只包含当前公开文本的字面片段，由Vue文本渲染。没有容量/HA/性能数字、自动历史删除、生产证书或完整M4发布承诺。

## 接受构建与输入

- 构建：target/forgeoj-linux-20261010-154710-14efe696，完整固定Linux退出0。API321/54 suites、Worker133/23 suites，零失败/错误/跳过；前端129/25及类型/lint/格式/构建全部通过。
- API SHA256：df19b0564c886c9f195454880f616a62f789b8984c0f07a5d2416d6757721c9c。
- Worker SHA256：87ed560123d3338bb6df0570f932712e3e384eb9a8bc3d6e57c149a0ba3ee430。
- 412业务/Worker/前端输入与接受快照一致，84前端输入与独立前端、挂载及实际提供内容一致；后端摘要不被前端门禁覆盖。原Redis目录23项解析依赖中22个实际嵌套JAR摘要相同，空starter不打包。见[source-inputs](evidence/m4-search/source-inputs.json)、[测试统计](evidence/m4-search/test-suites.json)及压缩backend/frontend日志。
- 搜索专项32项：ES19、投影4、迁移1、客户端8。原公共审核、账号/班级/作业/自测与Worker门禁均保留；旧V17 seed兼容修复后的最终全量已通过。

## 同JAR主环境及真实浏览器

主环境target/forgeoj-e2e-20261010-192354-eaaa4bc7开启Redis与HTTPS搜索，实际API/副本/Worker只读JAR与上述摘要一致；API无Docker socket/ES私钥，Worker无搜索、Redis、迁移或管理员CLI配置。真实ES9.5.3/Basic/插件0、未认证401和运行账号访问无关索引403通过。[运行证据](evidence/m4-search/search-runtime.json)。

- 搜索73项HTTP：匿名401、普通/审核员403、OPS/SUPER允许；正文关键词“有符号整数”、标题“ 两数之和 ”、筛选分页/白名单/纯文本高亮、私密哨兵缺失、输入边界、幂等重放与hash冲突409。
- 暂停准确ES容器后标题“ 两数 ”降级及固定提示、正文缺失、归档404且无搜索泄漏；同期间正式Worker真实AC。2个事件各5次失败进入DEAD_LETTER，保留INDEX_UNAVAILABLE与旧终态。恢复与内容清空后，受审计新代数重建恢复全文。
- SQL水位最终12/12、activeJob空、私有版本0；3重建SUCCEEDED，每项queued/terminal审计各1。14真实搜索SQL拒绝（API无DELETE、Worker无7表SELECT）；9队列全空。
- 死信检查4条持久四字段消息、2个唯一事件，符合至少一次投递；先检查并重新入队，后仅消费确认MySQL死信终态的本次事件，数据库失败事实不删除。提交终态后ACK日志链通过。
- Redis107项HTTP回归：共享预算/幂等、数据库权限失败503空body/no-store并恢复授权、暂停时真实AC、未过期JWT撤销/降级/停用拒绝、归档不可见、保守429、失效Outbox恢复及清空后原回执保留。最终4项Redis SQL拒绝通过。
- 基础8真实判定（AC/WA/CE/RE/TLE/OLE/MLE/SECURITY_VIOLATION）、所有权/Origin/退出/取消、公共题库/学习、班级与私有题/作业/教师、后台身份和公共审核HTTP回归通过；作业真实截止前接受、截止后完成。

实际浏览器5份DOM与5张原始JPEG：[健康正文搜索](evidence/m4-search/search-browser-healthy-public.jpg)、[健康运维](evidence/m4-search/search-browser-healthy-ops.jpg)、[故障题库](evidence/m4-search/search-browser-outage-public.jpg)、[撤权前](evidence/m4-search/search-browser-before-revocation.jpg)、[撤权后](evidence/m4-search/search-browser-revoked.jpg)。真实停用browser_ops后显示登录失效，水位/表/原因输入及草稿消失；该页面原本不显示账号名，不声称账号名清空。截图API返回JPEG，原错误png扩展名改为jpg，字节摘要不变，未制作或编辑截图。tab3最终about:blank并markDeliverable保留，未关闭标签或Codex/ChatGPT。

## 整个索引删除后重建的补充证据

主环境清空使用delete_by_query，仅证明内容清空；因此另起target/forgeoj-e2e-20261010-200754-1690c514补充整个索引删除。相同准确JAR、只读挂载、真实MySQL/Rabbit/HTTPS ES；先核对项目标签、SQL代数、mapping forgeojRebuildId及索引UUID，再DELETE准确整个索引并确认旧_settings返回404。真实HTTP证明正文查询降级、标题仍可用，OPS受审计幂等重建SUCCEEDED，新索引名称及UUID不同、正文恢复，公开题行数2保持不变，queue/terminal审计各1。恢复阶段11项HTTP通过，准备阶段HTTP另有原始记录。见[补充机器证据](evidence/m4-search/search-index-deletion-verification.json)、[删除事实](evidence/m4-search/search-index-deletion-facts.json)、[HTTP](evidence/m4-search/search-index-deletion-http.json)及11个冻结辅助摘要。

补充环境首次frontend npm ci因registry.npmjs.org ECONNRESET及npm Exit handler never called失败，未接受该前端。保留target/search-delete-replay-start.log与其frontend-npm-logs；复用已缓存固定Node镜像（pull=never、无npm下载），在拥有者私有网络直接访问API进行后端补充。第一次直接请求Origin仍为localhost被既有同源规则403拒绝，改为请求自身api:8080后真实登录/首次改密/重建通过；未放宽Origin、CSRF或权限。主环境实际提供的前端及真实浏览器验收独立保持有效。失败说明见[index-deletion-environment-failure](evidence/m4-search/index-deletion-environment-failure.json)。

## 失败记录与证据修正

- 143149-44edfc56构建期间源码变化，核验归属后停止，不接受产物。search-dead-recovery.log并行ES专项超时（2失败/7错误），后续串行单项与最终全量通过，保留原日志。
- 150111-d4758f37在Maven Central下载Testcontainers2.0.5时中断，17,789,146字节仅收到10,702,528；未进入测试，不改依赖/断言。150816-c3ff4ef3搜索32项通过但全API旧V17种子迁移错误，Worker/前端未运行，不接受；修复后的154710最终全量通过。
- 主HTTP辅助首次错把已认证普通账号403期望为401、用非标题词“整数”验证标题降级、读错mapping元数据键；分别按已有真实合约修正。清空后旧管理凭据过期401，真实重新登录；严格核对原清空回执/代数/UUID/零数量后续跑，未再次删除或改失败事实。五份历史冻结manifest保留，仅两个重放辅助及证据适配器允许记录差异，业务/前端不变。[修正记录](evidence/m4-search/replay-helper-corrections.json)。
- 旧Admin HttpAudit要求1条admin-recover事件，本搜索环境未执行该CLI，实际0，因此该额外旧审计不适用；保留其原断言，不制造恢复事件，不宣称新CLI恢复验收。后台Setup/ReadyBrowsers与搜索独立审计均通过。
- 末期Redis缓存正常TTL到期导致第一次Audit缺缓存；通过真实HTTP登录/公共查询填充后立即重审通过，不修改TTL/预算/回执/数据库事实。
- 证据适配器在运行捕获后修正本地审计规则；source-inputs同时记录原冻结和当前摘要，不覆盖真实运行manifest或声称适配器进入API/Worker。

## 清理、复核和交付

两个环境的拥有者容器/卷/网络/Worker镜像、builder、Testcontainers、managed sandbox全0；主旧前端URL实际不可用。原保护文件仅核对摘要、不读取内容、不暂存；LICENSE/NOTICE摘要保持原样。辅助验证stdout的浅层JSON展示曾有Depth2提示，持久证据以Depth15保存并经最终Node审计读取完整数组，未截断持久事实。

最终关联命令（先拥有者精确清理，再读取证据；不重新执行删除）：

~~~powershell
./tools/validation/Verify-M4SearchIndexDeletion.ps1 -Action Verify -RunDirectory target/forgeoj-e2e-20261010-200754-1690c514
& 'D:/Node.js/node.exe' tools/validation/Verify-M4SearchEvidence.mjs target/forgeoj-linux-20261010-154710-14efe696 target/forgeoj-e2e-20261010-192354-eaaa4bc7 docs/evidence/m4-search
~~~

最终审计实际退出0，所有输入/构件/角色/SQL/日志/浏览器/补充删除与清理事实均被校验。暂存白名单复核排除保护文件和target；自有文件git diff --cached --check通过。原始上游NOTICE.txt自带230处空白检查提示，作为唯一排除路径保留原文，并核对工作文件及暂存blob的原SHA256，不为格式检查改写第三方许可告知。首次暂存时text/eol属性会归一化NOTICE原换行，摘要核验拒绝；仅该版本许可文件改用-text禁止转换，重新暂存后原始字节摘要匹配。

提交推送范围仅feat/m2-accounts，第6步完成后停；具体提交及远端状态以实际Git核验为准。下一个授权单元是第7步监控与完整M4总门禁，需要先检查额度，不能将当前技术子范围提升为完整M4或RESUME_READY。
