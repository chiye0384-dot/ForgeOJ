#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Archive allowlisted diagnostic evidence after capture, reduction and exact cleanup."""
import datetime as dt
import hashlib
import json
import pathlib
import re
import shutil
import sys
import uuid
import zipfile

repo = pathlib.Path(__file__).resolve().parents[2]
run = (repo / sys.argv[1]).resolve()
assert run.is_relative_to(repo / 'target')
source = run / 'diagnostics'
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
write = lambda p, v: p.write_text(json.dumps(v, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
summary, capture = read(source / 'summary.json'), read(source / 'verification.json')
cleanup = read(run / 'cleanup-result.json')
assert summary['status'] == capture['status'] == 'PASS'
assert cleanup['project'] == capture['project']
assert cleanup['containers'] == cleanup['volumes'] == cleanup['networks'] == 0
assert capture['businessFactsAndGrantsUnchanged'] and capture['submissionsCreated'] == 0
inputs = read(repo / 'docs/evidence/m4-monitoring/linux-source-inputs.json')['inputs']
assert len(inputs) == 428
for item in inputs + capture['toolHashes']:
    assert sha(repo / item['path']) == item['sha256'], item['path']
assert sha(source / 'frozen-client/load.mjs') == sha(repo / 'tools/performance/load.mjs')
assert sha(source / 'DriverDefaults.java') == read(source / 'driver-and-limit-metadata.json')['probeSourceSha256']
assert sum(c['requestSummary']['measurementCount'] for c in summary['cases']) == 94862
assert all(c['requestSummary']['errors'] == 0 for c in summary['cases'])
for case in summary['cases']:
    safety = case['eventSafety']
    assert all(n == 0 for n in safety['forbiddenEventCounts'].values())
    assert sha(source / case['case'] / 'api.jfr') == safety['binaryDigest']

report = '''# M5 SQL/API 只读热点诊断 DIAG-20261028-01

本机隔离诊断有限单元 **VERIFIED**；完整性能计划/M5 **IN_PROGRESS**，E-06/E-07、Release、RESUME_READY 不提升。没有实施业务、索引、连接参数或资源优化，没有优化比例结论。原始无探针基线及其归档保持不变。

## 结果与下一步

列表路径每次有 COUNT、分页、标签三类业务 SELECT，并有事务与连接状态交互。请求线程样本较多落在 JDBC/MyBatis，其中出现 Connector/J 的 QueryInfo 构造和字符串扫描。详情缓存命中仍读取 MySQL 的 cache_epoch，并完成 Redis 缓存完整性校验、JSON 解码及 HTTP 响应编码。它们是有证据的成本位置，尚不能证明唯一瓶颈或预计收益。

镜像内实际 Connector/J 9.7 的 Driver.getPropertyInfo（未建立连接）返回 cachePrepStmts=false、prepStmtCacheSize=25、prepStmtCacheSqlLimit=256、useServerPrepStmts=false；实际 JDBC URL 没有相关覆盖，源码也没有显式设置。下一有限单元建议在新隔离项目做**仅连接级语句/查询元数据缓存**的受控 A/B，先充分预热、固定资源/数据、交错重复并检查内容与权限不变，再决定是否采用。当前没有打开缓存，也不承诺改善。保留重复读事务、代数/完整性/身份校验，不为性能降低边界。

## 实验条件

2026-10-28，拥有者项目 forgeoj-m5check-147876a386fa，原始目录 target/forgeoj-deployment-20261028-155345-36f0e4a8/diagnostics。Docker Desktop/WSL2 Linux 内核 6.18.33.2-microsoft-standard-WSL2；API/MySQL 各 2 CPU，cpu.max=200000 100000。其他限制沿用原基线，仅在 ignored 测试包设置。

同一固定 API/Worker JAR，428 个编译输入摘要匹配；API SHA256 f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e，Worker ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f。Git HEAD 058f36c771f15735a1e987a21c55074b80f511a9，工具仍在未提交工作树，没有提交/推送。

数据集 SHA256 09d4eaa246747eb77d0525b7aecdebba7db045f44b5ff5eb6592bccd8d0a682b：1 个合成用户、301 公共题、301 判题版本、903 用例、零提交，无班级/作业。短/中/长合成题面各 100。Node 固定原客户端和镜像，无新增依赖。

四阶段各 20 秒，闭环并发 4/8，无重试，同桥 HTTPS 验证证书，列表预热 30 次，详情预热 300 次。共 **94,862 次测量请求**，状态/网络/超时/内容错误均 0；原始 NDJSON 独立重算，与服务端 HTTP 计数（含预热）完全一致。没有新建判题提交，本单元不产生新的判题正确性证明。

存在 JFR、元数据采集、首次 JVM/JIT/GC 及运行顺序影响；本轮结果不能与原无探针基线直接计算优化比例。共享开发机和小数据量不代表云主机容量。

| 阶段 | 测量请求 | req/s | P95 ms | P99 ms |
|---|---:|---:|---:|---:|
'''
for case in summary['cases']:
    r = case['requestSummary']
    report += f"| {case['case']} | {r['measurementCount']} | {r['successfulRps']:.2f} | {r['successLatency']['p95Ms']:.3f} | {r['successLatency']['p99Ms']:.3f} |\n"
report += '''
## SQL 执行计划与摘要边界

使用 API 原有角色执行 EXPLAIN JSON/ANALYZE 和 SHOW INDEX，未改授权/索引/统计设置。COUNT 扫描 301 行、筛出 100，已有 problem_tag 与 judge_version 主键查找各循环 100 次，空闲观察总耗时约 0.473 ms。首分页主键扫描 59 行返回 20，总耗时约 0.101 ms；OFFSET 80 末页扫描 299 行产生 100 个候选后返回 20，总耗时约 0.365 ms。标签使用覆盖主键范围扫描，返回 40 行，约 0.039 ms。cache_epoch 是常量主键行访问。单次空闲计划耗时不等于并发端点尾延迟；301 行的小表不能仅凭扫描就判定缺索引或证明未来大表表现。

维护 root 仅只读读取 Performance Schema 的归一化 digest 元数据；未重置/启用仪器，也未导出 SQL_HISTORY。列表另观察到 SET autocommit、read only/read write、COMMIT 等约五类事务控制交互和 SELECT @@SESSION.transaction_isolation 状态探测。保留原重复读语义。

**SQL 摘要计数没有完全对齐。** 含预热的列表 C4 期望 10343 次，COUNT/page/tags 实际 10068/10320/10333；C8 期望 15944，实际 15839/15932/15933。详情 epoch C4 期望 35118、实际 35077；C8 期望 34117、实际 34098。原因尚未确认，不能用这些差值精确声称每请求 SQL 次数，也不能把差异当作业务请求丢失；客户端/HTTP 计数一致，二者分别记录。

补充只读计数校准中，串行 1000 次 SELECT 返回/摘要增量均 1000，四路并发共 2000 次均 2000；Digest_lost=0、10000 槽位使用 564。该小探针没有复现原差异，也没有解释差异，不计入 HTTP 负载。max_digest_length 和 performance_schema_max_digest_length 都为 1024，长详情摘要被截断，分类使用实际保留下来的投影字段，完整 SQL 仍由源码界定。SQL 计时是 elapsed time，含预热/少量背景，不能当纯 CPU 时间。

## Java 样本、GC 与配额

下表仅取测量 UTC 时间窗内的 http-nio 请求线程 Java 样本。JDBC、JSON、日志等采用包含栈分类，类别可能重叠。32 帧内不少栈看不到项目帧；采样计数不等于 CPU 百分比，不给每个函数分配精确开销。原生 accept/epoll/socket 样本可能是等待，不能算热点 CPU。

| 阶段 | 请求 Java 样本 | 32 帧内无项目帧 | 包含 JDBC/MyBatis | 包含 JSON | GC 暂停重叠总 ms | 最大暂停重叠 ms |
|---|---:|---:|---:|---:|---:|---:|
'''
for case in summary['cases']:
    j = case['jfr']
    # Persist the exact reducer vocabulary in raw JSON; table uses matching names.
    families = j['inclusiveFamiliesMayOverlap']
    jdbc = next(v for k, v in families.items() if 'jdbc' in k.lower())
    js = next(v for k, v in families.items() if 'json' in k.lower())
    report += f"| {case['case']} | {j['requestJavaSamples']} | {j['missingApplicationFrameIn32']} | {jdbc} | {js} | {j['gcPauseOverlapMs']:.3f} | {j['maxGcPauseOverlapMs']:.3f} |\n"
report += '''
列表的项目帧主要是 LibraryService.list；详情可见 PublicCache.read/decode 与控制器/过滤器。长 GC 暂停实际存在：list-c4 最大约 127 ms、detail-c8 最大约 107 ms，不能排除其对本轮尾延迟的贡献，也不能据此证明此前无探针基线有相同 GC 行为。未采集 heap dump。

| 阶段 | API 节流周期/总周期 | MySQL 节流周期/总周期 |
|---|---:|---:|
'''
for case in summary['cases']:
    c = case['cpuDeltaIncludingWarmupAndProbeControl']
    report += f"| {case['case']} | {c['api']['nr_throttled']}/{c['api']['nr_periods']} | {c['mysql']['nr_throttled']}/{c['mysql']['nr_periods']} |\n"
report += '''
此差值区间还包含预热/attach/控制开销；throttled_usec 是累计统计，不能直接除以墙钟推导请求被阻塞百分比。列表 C4 与 C8 受到不同进程的配额约束，首次 JIT/探针/GC 有混杂因素；没有单一因果或扩容收益结论。

## 证据、失败与安全收尾

原始 api.jfr、8 帧紧凑 JSON、32 帧文本栈、逐请求 NDJSON、归一化 SQL 前后值、cgroup/HTTP 指标、执行计划和独立归纳结果均保留。最小 JFC 只有执行/原生样本、CPU、GC，加 JFR 固有 Checkpoint/Metadata；环境变量、系统属性、异常、线程 dump、对象内容等七类敏感事件计数全部为 0。导出逐文件扫描本轮实际随机秘密、完整 PEM/JWT；私密配置、证书/密钥、cookie/token、数据库 dump、上游二进制不打包。仅保留本轮无秘密的 JFR 观测文件。

工具失败如实保留：Checkpoint 拼写校验错误、docker cp 看不到容器 tmpfs、32 帧完整 JSON 超过 96 MiB 导出上限；最终改成准确元事件名、exec 二进制流加摘要核对、紧凑 JSON 加文本栈。完成的负载没有重复运行，没有清库/重置统计。前两阶段旧完整 events.json 原件只在原始目录保留，不进入 ZIP。离线归纳器先误设摘要计数必须一致、后修正长摘要分类；失败脚本随证据保留，修正只重算离线数据。

数据/授权/Flyway 前后摘要不变，428 输入匹配。API 无残留 JFR recording，精确清理本轮容器/卷/网络与三镜像别名，负载容器移除；旧 LAN 八容器退出保留，无关 redis 保持运行。没有启动/停止桌面客户端，没有 Git 提交/推送或公网发布。

安全交付收据见 [verification.json](evidence/m5-hotspot-diagnostic/20261028/verification.json)，机器归纳见 [summary.json](evidence/m5-hotspot-diagnostic/20261028/summary.json)。ZIP 路径、SHA256、逐文件清单和 CRC 在收据中；它是诊断证据，不是新的生产部署版本。原部署/备份/回滚/性能基线 ZIP 均保留。

方法与范围见 [诊断设计](M5-PERFORMANCE-DIAGNOSTIC-DESIGN.md)、[原基线](M5-PERFORMANCE-BASELINE-REPORT.md)。真实 Linux/公网 HTTPS/SMTP、每日异地备份与密钥保管、跨业务版本回滚、长稳/开放到达负载、真实试用仍独立待验收。
'''
(repo / 'docs/M5-PERFORMANCE-DIAGNOSTIC-REPORT.md').write_text(report, encoding='utf-8')

stamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
name = 'forgeoj-hotspot-diagnostic-' + stamp + '-' + uuid.uuid4().hex[:8]
output = repo / 'target' / name
package = output / 'package'
package.mkdir(parents=True)
files = {}
def add(path, dest):
    assert path.is_file() and not path.is_symlink(), path
    assert dest not in files
    files[dest] = path

for filename in ['counter-calibration.json','dataset.json','driver-and-limit-metadata.json','DriverDefaults.java','environment.json','facts-before.json','final-recording-check.txt','instrumentation.json','load-containers.json','summary.json','verification.json','frozen-client/load.mjs']:
    add(source / filename, 'diagnostics/' + filename)
for query in ['list-count','list-page-first','list-page-last','list-tags','detail-epoch']:
    for suffix in ['.sql','.json','.analyze.txt']:
        add(source / 'sql-plans' / (query + suffix), 'diagnostics/sql-plans/' + query + suffix)
for table in ['problem','problem_tag','problem_judge_version','cache_epoch']:
    filename = table + '.indexes.tsv'
    add(source / 'sql-plans' / filename, 'diagnostics/sql-plans/' + filename)
for case in summary['cases']:
    for filename in ['after-api.prom','analysis.json','api.jfr','before-api.prom','cgroup-after.json','cgroup-before.json','client.log','config.json','event-safety.json','events-compact.json','jfr-start.txt','jfr-stop.txt','jfr-summary.txt','requests.ndjson','samples.ndjson','sql-after.json','sql-before.json','stacks.txt','summary.json']:
        add(source / case['case'] / filename, 'diagnostics/' + case['case'] + '/' + filename)
for filename in ['diagnose-hotspots.py','diagnostic_queries.py','diagnostic.jfc','summarize-hotspots.py','build-diagnostic-evidence.py','dataset.py','load.mjs','prepare-baseline.py','run-baseline.py','README.md']:
    add(repo / 'tools/performance' / filename, 'tools/performance/' + filename)
for filename in ['diagnose-hotspots.py','capture-cp-version.py','capture-full-json-version.py','summarizer-exact-count-version.py','builder-empty-smtp-version.py']:
    add(source / 'failed-tool-inputs' / filename, 'diagnostics/failed-tool-inputs/' + filename)
for filename in ['cleanup-result.json','jfr-start-help.txt','jfr-print-help.txt']:
    add(run / filename, 'run/' + filename)
for filename in ['M5-PERFORMANCE-DIAGNOSTIC-DESIGN.md','M5-PERFORMANCE-DIAGNOSTIC-REPORT.md']:
    add(repo / 'docs' / filename, 'docs/' + filename)
for filename in ['LICENSE','NOTICE']:
    add(repo / filename, filename)
readme = output / 'README.md'
readme.write_text('ForgeOJ 只读热点诊断证据包。先读 docs/M5-PERFORMANCE-DIAGNOSTIC-REPORT.md，逐文件摘要见 delivery.json。\n'
                  '这是原始观测与原创工具的审阅包，不是部署安装器；脚本复现需要完整 ForgeOJ 仓库、既有固定构件与新建拥有者测试包。\n'
                  '报告中仓库证据/旧基线链接需在完整仓库查看；本包 diagnostics/summary.json 与 verification.json 是本轮原件。\n'
                  '不包含私密配置、证书/密钥、数据库 dump 或上游运行时，未实施性能优化，SQL 摘要计数差异未解释。\n', encoding='utf-8')
add(readme, 'README.md')

secrets = []
secret_files = list((run / 'private/secrets').iterdir())
assert secret_files
for path in secret_files:
    if path.is_file():
        raw = path.read_bytes().strip()
        if not raw:
            assert path.name == 'smtp-password'  # Disabled SMTP has no credential.
            continue
        assert len(raw) >= 16
        secrets.append(raw)
assert len(secrets) == 13
pem = re.compile(rb'-----BEGIN [A-Z0-9 ]+-----[\s\S]{24,}?-----END [A-Z0-9 ]+-----')
jwt = re.compile(rb'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}')
manifest = []
for dest, path in sorted(files.items()):
    raw = path.read_bytes()
    assert not any(secret in raw for secret in secrets), dest
    assert not pem.search(raw) and not jwt.search(raw), dest
    target = package / dest
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(path, target)
    manifest.append({'path':dest,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
receipt = {'status':'VERIFIED','scope':'bounded read-only SQL/API diagnostic; no optimization implemented',
           'fullM5':'IN_PROGRESS','fullPerformancePlan':'IN_PROGRESS','release':False,'resumeReady':False,
           'run':str(run.relative_to(repo)).replace('\\','/'),'project':capture['project'],
           'measurementRequests':94862,'requestErrors':0,'compiledInputsMatch':428,
           'businessFactsAndGrantsUnchanged':True,'submissionsCreated':0,
           'sqlDigestCountGap':'unresolved; independent HTTP counts match; calibration did not reproduce',
           'secretScan':'PASS actual run secret bytes + complete PEM/JWT patterns',
           'jfrSensitiveEventCounts':'all seven forbidden types zero in all four recordings',
           'cleanup':cleanup,'files':manifest}
write(package / 'delivery.json', receipt)
archive = output / (name + '.zip')
with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for path in sorted(package.rglob('*')):
        if path.is_file():
            z.write(path, str(path.relative_to(package)).replace('\\','/'))
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for item in manifest:
        assert hashlib.sha256(z.read(item['path'])).hexdigest() == item['sha256']
receipt['archive'] = {'path':str(archive.relative_to(repo)).replace('\\','/'),'sha256':sha(archive),'bytes':archive.stat().st_size,'fileCount':len(manifest)+1,'crcAndFileHashes':'PASS'}
write(output / 'delivery-receipt.json', receipt)
(output / (archive.name + '.sha256')).write_text(receipt['archive']['sha256'] + '  ' + archive.name + '\n', encoding='ascii')
evidence = repo / 'docs/evidence/m5-hotspot-diagnostic/20261028'
evidence.mkdir(parents=True, exist_ok=True)
write(evidence / 'verification.json', receipt)
shutil.copyfile(source / 'summary.json', evidence / 'summary.json')
print(json.dumps({'status':'PASS','archive':receipt['archive']}, ensure_ascii=False))
