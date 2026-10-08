# M3 四项总门禁证据

本轮API虽223项零失败且BUILD SUCCESS，日志仍保留数据库测试结束后的扫描/连接警告和Surefire测试JVM退出超时；verification.json记录实际日志SHA及观察值。该测试退出问题尚未修复，不能据此声称日志零警告，也不属于Codex关闭操作。

2026-10-08，4/4 PASS、完整M3 VERIFIED，实现基线4afce92。详细权限矩阵、命令、各轮次和限制见 [审计](../../M3-GATE-AUDIT.md)。

verification.json由tools/validation/Verify-M3GateEvidence.py实际读取固定Linux XML、输入清单、原真实回放构件与脱敏证据后生成，非手填通过。记录最新API223、41个M3用例、复用未变Worker133/前端97；254后端/187生产/68前端/112Worker共享输入匹配，333个新旧API运行条目一致。历史HTTP/三账号浏览器/实际判题/跨截止/提交后ACK与空队列按各单元真实日期保留，JSON中列出其路径与实际SHA，不新增复制图片或虚构本轮浏览器/Worker/SMTP/SIGKILL操作。

cleanup.json是2026-10-08T05:49:54.4853937+00:00从可用Docker现场只读采集的零残留记录；包含所执行采集器摘要，工具不会关闭应用、终止进程或删除容器。审计脚本摘要保存在verification.json；snapshot包含现行执行输入，不将构建后新增审计工具冒充先前构建源。Python只需标准库，产物和真实日志继续位于忽略的target，不公开日志、state、SMTP配置或凭据。原生测试首轮因startsAt遗漏而400，修正请求后2/2通过，日志保留在target；没有改业务或删断言。

api-source-files.sha256来自本轮API构建，runtime-production.sha256只列187个已接受运行生产输入，frontend-runtime.sha256来自原实际只读运行容器，runtime-entry-hashes.json按实际JAR解压条目对照，均保留SHA/路径不包含私有用户数据。旧未跟踪M2交接和忽略SMTP字节保留。没有PR、main合并、tag、Release或RESUME_READY，M4/M5仍PLANNED。
