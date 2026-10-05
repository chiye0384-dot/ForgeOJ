# M2 独立自测验收

2026-10-05，实施基线 `2fc7e72`，分支 `feat/m2-accounts`。独立自测与 QQ SMTP 真实投递单元 **VERIFIED**，完整 M2 保持 **IN_PROGRESS**，等待最终门禁汇总。设计见 [独立自测](M2-SELF-TEST-DESIGN.md)。

实现 V14 独立快照、短期私有 payload、运行任务、attempt 和压缩输出。自测使用自定义输入与点击时冻结的 Java 21 代码，独立消息队列和运行结果；成功为 SUCCESS，不创建正式 Submission，不读取官方测试，不影响 AC 进度或题解解锁。共享一个运行槽位与三个排队额度，排队可取消，旧租约不能加载或写结果，有限平台重试和提交后 ACK 保持原边界。

终态后 24 小时不再可读，后台有界批次物理清理代码、输入和输出；保留最少的快照元数据、job 和已关闭 attempt，避免删除沙箱清理所需的权威依据，也保证过期请求重放返回 410 而非重复执行。正式提交永久记录不变。

本地验证：

- V13→V14 升级测试通过：旧表及内容/PASSED/送审数据保留，五张新表为空，重复 migrate 执行零条且校验成功。
- API 六项实际 HTTP/MySQL 测试通过：本人/no-store/精确请求、冻结数据和冲突重放、并发单快照/job/Outbox、共享额度、取消与 RUNNING 禁取消、权限/CHECK、压缩输出完整性、过期隐藏/物理清理/保留 attempt。HTTP 终态 fixture 为生命周期测试控制，不冒充沙箱执行。
- Worker 八项真实 MySQL/RabbitMQ/Docker 测试通过：自定义输入产生真正输出且 SUCCESS、broker 重投不重复执行、编译/运行/UTF-8/超限/超时失败无输出和用户重试、空输出成功、单赢家与共享额度/运行保留、快照篡改关闭、取消消息不执行、严格消息拒绝、有限重试和死事件、过期 token 无法输出且新 attempt 真正成功。单测中控制过期时间用于状态机验证；生产 SIGKILL 的自然过期仍待下面独立回放。
- 前端新增八项交互测试通过：冻结点击数据、原 UUID/原 payload 重试、身份切换丢弃迟到结果、卸载停止轮询、排队取消、轮询上界/手动恢复、短期历史选择、额度拒绝和文本渲染。无新增依赖。
- 初次编译遗漏 API 的自测死队列常量，已补齐。初次 API 启动因 MyBatis 不支持同名注解方法重载失败，插入方法改为 insertPayload。初次 Vue 模板的内联多语句经格式化后不可解析，改为命名函数。Worker 首次测试发现 SELECT * 的 record 字段顺序不匹配，改为精确列白名单。修复后 API 六项与 Worker 八项均零失败/错误/跳过。前端格式检查发现测试文件格式尚未更新，已重新格式化；以接下来的固定 Linux 全量结果为最终门禁。

首次固定 Linux 全量 `forgeoj-linux-20261005-171729-5c6cbe71` 未通过：API181 零失败/错误/跳过，Worker132 中五项旧进程故障测试失败。旧测试独立列举迁移仅到 V11，新增共享额度查询需要 V14 的 self_test_job，实际 SQL 权限/缺表错误导致旧正式任务无法认领；已给该测试补齐 V12～V14，重新运行完整门禁。该失败记录不作为验收。API 结束时还出现 Surefire 30 秒 fork 退出警告，不能将“测试零失败”描述成日志无错误。

## 最终固定 Linux 与产物

- 全量：`target/forgeoj-linux-20261005-174012-b069321c`，API **181**（36 套）+ Worker **132**（23 套）= **313**，零失败、错误、跳过，Maven BUILD SUCCESS。旧五项进程故障测试已通过。API 与 Worker 结束仍有上述 Surefire 退出警告；同类警告在上一版已验收日志中也存在，不宣称日志无错误。
- 前端全量原本已通过 69 项及类型/lint/格式/构建。用户实际激活后发现禁用按钮仍显示等待光标，定位为 `button:disabled { cursor: wait }`。只给真正 busy 的按钮设置等待光标，普通禁用按钮显示不可操作；重新在 `target/forgeoj-linux-20261005-182241-40614bbd` 完成 **69**（14 套）及所有门禁。最终浏览器对应文件 SHA 与本次前端产物输入一致。
- API JAR SHA256：`6d990fa0529a692402b1b52f07c552147a2ef85967bd7f9724f11efda26988cc`。
- Worker JAR SHA256：`7b51a8057afca665cbc1b3861bd186e1a974349edb7f44c6ce8f507abebc2f01`。
- 229 个后端/契约输入逐字节匹配后端构建快照。最终前端快照的 300 个可执行/测试/契约/验证工具输入中 **299 未变**；唯一后改是 `Replay-FixedLinux.ps1` 的空 SQL 结果导出：PowerShell 空管道不生成文件，改为显式写空文件。修正后的工具已直接执行最终实际 Audit，未改业务、测试、JAR 或判题事实。哈希及每套结果见 [verification.json](evidence/m2-self-test/verification.json)。生产 JAR 不含 testinfra/Testcontainers/直连测试适配。

实际命令（DockerCommand 指定本机 Docker Desktop 的实际 CLI）：

```powershell
& tools/validation/Verify-FixedLinux.ps1 -Scope All -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
& tools/validation/Verify-FixedLinux.ps1 -Scope Frontend -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
& tools/validation/Verify-SelfTestRecovery.ps1 -RunDirectory target/forgeoj-e2e-20261005-182028-1c3fcd89 -DockerCommand 'C:/Users/Lenovo/AppData/Local/Programs/DockerDesktop/resources/bin/docker.exe'
```

## 相同 JAR 的真实页面、恢复与审计

最终回放为 `forgeoj-e2e-20261005-182028-1c3fcd89`；真实浏览器使用正常 53277 与轮询兜底 53276 端口。四个自测均由实际页面点击创建：

1. 原创错误程序产生 FINISHED/COMPILE_ERROR，无输出。
2. 输入 `8 9` 的原创求和程序成功输出 `17`。点击运行后将编辑器改为输出 999、输入改为 `99 99`，冻结代码/输入和实际输出不变，留存真实 DOM 与截图。
3. 新程序/输入 `7 8` 对应 Worker 与受管理沙箱被精确捕获。对该项目 Worker 发送真正 SIGKILL，未改数据库租约或终态；等待自然过期，旧 attempt LEASE_EXPIRED，新 attempt SUCCEEDED，输出 `15`，旧沙箱已移除。
4. 暂停本项目 Worker，从页面运行 `4 5` 并取消排队。状态 CANCELLED、零 attempt；重启后消息关闭且无执行。选择第二个历史记录仍显示冻结 `8 9` 和输出 `17`。

自测 HTTP 验证在正式判题矩阵之前执行：本人/他人/匿名隔离、no-store、字段白名单、原 UUID 原 payload 返回原记录、改变输入409、跨 Origin403、分页限制均通过；本人正式记录仍 **0**、AC 进度 **0**、官方题解仍 LOCKED。题解为原创 disposable fixture，不冒充审核发布。

正式八 verdict 矩阵、题库、学习记录/草稿冲突/权限、正常第二题 AC 与无 WebSocket 转发的旧根入口轮询 AC 均重新通过。最终 **11** 正式提交（10完成、1取消）/11发布 Outbox；**4** 自测/5发布 Outbox，包含真实恢复后的第二次投递。关联日志确认冻结任务、终态提交在 ACK 之前；七队列均无 ready/unacked。API 无 Docker socket/Worker 或 migrator 配置；**29** 项真实 MySQL 权限拒绝通过，含自测不可变快照、私有 payload、输出和 attempt 边界。未在本轮伪造或重做作者 content/review/output 运行记录（均0）；其历史验收独立保留，相关最新代码仍通过本轮全量测试。

首次回放 `forgeoj-e2e-20261005-180405-4dbca424` 的前端 npm 下载 ECONNRESET，未完成完整启动流程；人工恢复后 API 重建使早期创建日志缺失，因此不作为最终自测日志审计。另建上述完整回放后从页面重新执行四个任务并完成审计。首次自然恢复证据保留在 ignored target；最终证据仅取最终回放。首次和最终审计还发现空文件导出与手工浏览器证据对象/数组契约问题，分别修正导出工具和观察文件格式后重跑，最终 Audit 返回成功。

两个回放均使用所有权校验后的精确 Stop，清理本人临时容器、卷、网络和 Worker 镜像，不执行 prune。活着的 Docker Server **29.8.0** 上已核实 managed sandboxes/Testcontainers/所属资源为0。用户未跟踪交接文件 SHA256 保持 `E08ACD3D26A8CD27ACA394BDAC91828E13B9455A86F62133C5BAFC038B18C054`。没有 PR/main/tag/Release。

## QQ SMTP 外部投递

用户选择 QQ 邮箱、指定同一发件/收件地址并开启 SMTP，在本机隐藏输入授权码。脚本用当前 Windows 用户 DPAPI 加密保存到 Git 忽略的 `.smtp.qq.local`，并在当前进程加载 API 环境变量；地址、授权码和邮件令牌没有进入公开证据。配置见 [说明](QQ-SMTP-LOCAL-SETUP.md)。

先直接验证 `smtp.qq.com:465` TLS1.3、正常证书与 220 greeting；不关闭证书校验。随后临时 smtp-probe API 使用上述相同生产 API JAR、已有 SmtpAccountMail、限制后的 API 数据库角色和 implicit TLS，创建真实临时账号并投递激活邮件；发送接口202且无 mail_delivery_failed。用户明确确认收到“ForgeOJ 邮箱验证”，打开实际邮件链接并点击确认。只读数据库核实账号 **ACTIVE**、email_verified_at 非空，前端 API 200，因此实际接收与激活链路均已通过。原始收件地址、授权码、激活令牌与临时测试密码没有进入 Git；smtp-probe 随后精确移除，临时数据库在确认后清理。

范围是本机少量 QQ SMTP 注册激活投递；不是生产域名、多收件人投递率、退信运营、批量发送或性能验收。找回/绑定的协议与状态机仍由已有全量测试支撑，本轮没有额外声称外部服务商逐项投递。M4 受控审核/发布和生产运营不纳入本单元。脱敏 SMTP 事实见 [smtp-delivery.json](evidence/m2-self-test/smtp-delivery.json)。
