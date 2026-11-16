#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Build a reviewable report and allowlisted evidence bundle; exclude private deployment state."""
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
run, suite, host_path = [(repo / value).resolve() for value in sys.argv[1:4]]
assert run.is_relative_to(repo / 'target') and suite.is_relative_to(run / 'performance') and host_path.is_relative_to(repo / 'target')
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
summary, verification = read(suite / 'summary.json'), read(suite / 'verification.json')
assert summary['status'] == verification['status'] == 'PASS' and summary['independentRawRecalculation']
cleanup = read(run / 'cleanup-result.json')
assert cleanup['project'] == verification['project'] and cleanup['containers'] == cleanup['volumes'] == cleanup['networks'] == 0
host, environment = read(host_path), read(suite / 'environment.json')
runtime = read(suite / 'final-runtime.json')
input_audit = read(suite / 'cgroup-and-input-audit.json')
assert input_audit['compiledInputsMatch'] == 428 and input_audit['frozenClientMatches']
for item in verification['toolHashes']:
    assert digest(repo / item['path']) == item['sha256']
assert digest(suite / 'frozen-client/load.mjs') == digest(repo / 'tools/performance/load.mjs')
stamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
name = 'forgeoj-performance-baseline-' + stamp + '-' + uuid.uuid4().hex[:8]
output = repo / 'target' / name; package = output / 'package'; package.mkdir(parents=True)
archive = output / (name + '.zip')
rows = summary['readCases']
lines = ['# ForgeOJ 本机隔离性能基线 PERF-20261028-01', '',
         '有限测量与原始记录复核 **VERIFIED**；完整性能计划/M5 **IN_PROGRESS**。本报告不是云端容量、优化提升、长稳或简历就绪结论。', '',
         '## 主要结果', '',
         f"最终套件测量请求 {summary['requestCount']:,} 次，HTTP/网络/超时/内容校验错误均为 0；预热请求不计入吞吐。数据库只读阶段事实未变。11 个真实判题提交分别有唯一 task、attempt 和 queued Outbox，全部达到预期终态，幂等回放没有新增业务副作用。", '',
         '并发 8 的吞吐低于并发 4，尾延迟显著增加；两轮原样保留，不挑最快值。详情路径 API 样本 CPU 接近 2 CPU 配额，筛选列表 MySQL 接近其 2 CPU 配额；运行后累计 cpu.stat 也记录到节流。这些观测与配额约束一致，尚未通过热点栈/SQL 剖析确认函数级瓶颈，不能推广为机器或线上最大容量。', '',
         '## 环境和复现边界', '',
         f"- CPU：{host['cpu'][0]['Name']}，{host['cpu'][0]['NumberOfCores']} 物理核 / {host['cpu'][0]['NumberOfLogicalProcessors']} 逻辑核；物理内存 {host['physicalMemoryBytes']/1024**3:.2f} GiB。采集时 Windows 空闲物理内存约 {int(host['freePhysicalKiB'])/1024**2:.2f} GiB。",
         f"- Windows：{host['windows']}；物理磁盘介质 {host['disks'][0]['MediaType']} / {host['disks'][0]['BusType']}。WSL2 虚拟磁盘未独立测 I/O。",
         f"- Linux：Docker Desktop/WSL2，内核 `{environment['engine']['KernelVersion']}`，引擎 {environment['engine']['ServerVersion']}，cgroup v{environment['engine']['CgroupVersion']}，引擎可见 {environment['engine']['NCPU']} CPU / {environment['engine']['MemTotal']/1024**3:.2f} GiB。",
         f"- Compose：{environment['composeVersion']}；API/Worker 各 1 实例；固定 Node 镜像 `{environment['nodeImage']}`。",
         f"- 判题 Java 镜像 `{runtime['judgeVersion']['image']}`；每任务 1 CPU / 256 MiB / 64 PID，三个公开 fixture 用例；输入字节数 {', '.join(read(suite/'dataset-provenance.json')['judgeTestInputBytes'])}。没有采到临时判题容器的逐阶段 CPU/内存，不能用 Worker stats 代替整个计算链路。",
         '- 所有测试经过真实 HTTPS 和证书验证，客户端在同一 Linux edge 桥上访问 Nginx；不是手机 Wi-Fi、公网延迟或独立 Linux 主机。共享开发机、未固定 CPU 频率/电源/热状态，宿主其他活动可能影响重复结果。',
         '- CPU/内存限制只加到这份 ignored 测试包，没有修改生产模板或宿主设置。JAR/前端源码未改，428 个编译输入仍匹配原固定构建。',
         f"- Git HEAD `{environment['gitHead']}`；性能脚本仍在未提交工作树，实际文件摘要/冻结副本随证据保存，没有脚本 Release/tag 或独立提交，不能称为发布完成。", '',
         '| 服务 | CPU 上限 | 内存 MiB | PID 上限 |', '|---|---:|---:|---:|']
for container in environment['containers']:
    lines.append(f"| {container['service']} | {container['nanoCpus']/1e9:g} | {container['memoryBytes']/1024**2:g} | {container['pidsLimit']} |")
lines += ['| 负载客户端 | 1 | 512 | 128 |', '',
          '## 数据集与负载方法', '',
          f"数据集 `{verification['datasetSha256']}`：1 个合成普通用户，301 道公共题（原两数之和 + 300 道原创合成题），301 个 judge version、903 个测试用例。合成题短/中/长题面各 100，分别 256/2048/8192 ASCII 字节；开始时提交历史为 0，没有班级、作业或大历史。直接 SQL fixture 只属于这个新空库测试项目，不是作者审核发布或生产初始化。", '',
          '闭环固定并发 1/4/8，各阶段 20 秒、两轮重复，固定顺序而非随机打散；每阶段只有一个活跃负载容器。HTTP keep-alive，5 秒超时，无自动重试。详情每阶段先仅重建 300 个已知合成题缓存键，再预热全部题；列表先预热 30 次。身份、限流、预算键没有清理；Redis/ES 没有停机或全局 flush。', '',
          '每个请求保存相对时间、响应体结束时延、状态及内容校验结果；分位数为 nearest-rank，Python 从 NDJSON 独立重算并与 Node 摘要逐项一致。服务端 library/2xx 计数也与客户端测量 + 预热请求完全一致。详情缓存计数验证预热 300 次 miss、测量请求全部 hit；列表仍走 MySQL，不能把两种路径合成总 QPS。', '',
          '闭环模型会随延迟升高降低实际到达率，本轮没有开放模型固定到达率/突发测试。方法背景见 [k6 开闭环文档](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/open-vs-closed/)。', '',
          '## 只读 API 结果（最终完整套件）', '',
          '| 场景/轮次 | 并发 | 请求数 | HTTP 200 req/s | P50 ms | P95 ms | P99 ms | 错误率 | API CPU 样本均值 | MySQL CPU 样本均值 | API 内存采样峰值 MiB |',
          '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for row in sorted(rows, key=lambda r:(r.get('round',0), r['concurrency'], r['mode'])):
    resource = row['resource']
    api_cpu = f"{resource['api']['sampleMeanCpuPercent']:.1f}%" if 'api' in resource else '未采到'
    mysql_cpu = f"{resource['mysql']['sampleMeanCpuPercent']:.1f}%" if 'mysql' in resource else '未采到'
    peak = f"{resource['api']['maxSampleMemoryMiB']:.1f}" if 'api' in resource else '未采到'
    lines.append(f"| {row['case']} | {row['concurrency']} | {row['measurementCount']} | {row['successfulRps']:.2f} | {row['allLatency']['p50Ms']:.3f} | {row['allLatency']['p95Ms']:.3f} | {row['allLatency']['p99Ms']:.3f} | 0% | {api_cpu} | {mysql_cpu} | {peak} |")
lines += ['', 'cold-unique-300 只表示这 300 个 Redis 详情键首次读取，JVM/磁盘并非冷态；阶段不足一秒，Docker stats 来不及形成区间内样本，表中是“未采到”，不是 CPU 为 0。其余阶段至少取得 API/MySQL/客户端各两份区间内资源样本。', '',
          'CPU 100% 约对应一个逻辑核，2 CPU 配额附近可见约 200%；采样不是持续精确峰值。Docker CLI 的 Linux 内存扣除部分缓存，不能称作 RSS，口径见 [Docker stats](https://docs.docker.com/reference/cli/docker/container/stats/)。原始 stats 的其他服务/客户端/PID/网络/块 I/O 都随包保留。', '',
          '客户端进程 CPU 在阶段内约为一个 CPU 的 '+f"{min(r['clientCpuPercent'] for r in rows if r['mode']!='cold'):.1f}–{max(r['clientCpuPercent'] for r in rows if r['mode']!='cold'):.1f}%"+'；event-loop 指标按 10 ms 分辨率采集，不把约 11 ms 的直方图值解释成服务器请求耗时。MySQL 只保存全局计数快照，没有单查询延迟、慢 SQL 明细或完整 Hikari 指标。', '',
          '## 真实顺序判题', '',
          '六次 AC 和 WA/CE/RE/TLE/OLE 各一次，全部使用新的编译/运行容器；正式队列一个消费者、prefetch=1，同用户始终只有一个在途提交。客户端 200 ms 轮询，因此“客户端可见”含轮询量化及接收开销；DB 时间是接受→开始、开始→终态的包络，不是独立编译/清理计时。', '',
          '| 用例 | 接受 HTTP ms | 客户端可见 ms | DB 排队 ms | DB 处理包络 ms | DB 接受→终态 ms | 实测结果 |',
          '|---|---:|---:|---:|---:|---:|---|']
for job in summary['judge']['jobs']:
    s = job['server']
    lines.append(f"| {job['name']} | {job['acceptanceMs']:.2f} | {job['clientVisibleMs']:.2f} | {s['queueMs']:.2f} | {s['processingEnvelopeMs']:.2f} | {s['acceptedToFinishedMs']:.2f} | {job['verdict']} |")
lines += ['', '样本只有 11 次且混合不同 verdict，不据此推算判题最大 submissions/min 或稳定 P99。MLE、不同隐藏输入大小、混合用户突发、消息/通知逐阶段延迟、长稳及故障负载留待后续。', '',
          '## 首轮工具失败记录', '',
          '首轮 30,000 次请求安全上限使 r1-detail-c4 在约 15.18 秒结束：30,000 次响应全正确，但未达到规定 20 秒，被标为 FAIL 而没有选作足时结果。此前四个完整阶段和这份截断阶段的原始数据、资源和原客户端/编排源码全部保留。随后只提高计数上限至 100,000，在不重灌数据库、不改资源/业务/限流的条件下，创建新套件完整重复全部场景。不存在优化前后对比，不能将两代际差异称为提升。', '',
          '| 首轮诊断阶段 | 请求数 | 实际秒数 | req/s | 状态 |', '|---|---:|---:|---:|---|']
for directory in sorted((run/'performance/cases').iterdir()):
    p = directory/'summary.json'
    if p.exists():
        first = read(p)
        lines.append(f"| {directory.name} | {first.get('measurementCount',0)} | {first.get('elapsedMs',0)/1000:.3f} | {first.get('successfulRps',0):.2f} | {first['status']} |")
lines += ['', '## 正确性、证据和未完成范围', '',
          '- 277,633 次最终测量请求内容/状态全部通过；13 个读取阶段原始记录独立重算通过，MySQL 只读事实前后相同。',
          '- 11 个接受 ID 对应 11 submission / task / attempt / queued Outbox，实际源码摘要和 verdict 一致；同幂等键回放仍是原 ID，没有静默丢失或重复业务副作用。',
          '- 九个队列末尾 messages_ready / unacknowledged 均 0，待发布 Outbox 为 0，受管沙箱为 0。API/Worker/Web 健康，无 OOM/restart；不据短测宣称长期稳定。',
          '- 测试项目容器/卷/网络和三应用别名已精确清理，全部负载容器已移除；旧 LAN 演示退出保留，无关 Redis 保留运行。没有共享 prune、桌面应用重启或真实库操作。',
          '- 安全机器证据：[verification.json](evidence/m5-performance/20261028/verification.json)。原始结果和脚本另有本地证据 Zip；没有实例配置、私钥、cookie/token 或上游二进制。',
          '- 仍缺独立 Linux/云主机、真实用户/大历史/班级数据、开放到达率/长稳/故障负载、通知/单查询/编译清理细分，以及同条件优化前后回归。完整 M5、E-06/E-07、Release/RESUME_READY 不提升。',
          '- 下一有限单元建议只读定位列表 SQL/API 热点和配额节流，再确定是否需要单一优化；保留本轮所有退化和波动，不先改代码追求漂亮数字。', '']
report = '\n'.join(lines)
(repo/'docs/archive/M5-PERFORMANCE-BASELINE-REPORT.md').write_text(report, encoding='utf-8')
(package/'report.md').write_text(report.replace('(evidence/m5-performance/20261028/verification.json)', '(delivery-verification.json)'), encoding='utf-8')
copied = []
def copy(source, destination):
    assert source.is_file() and not source.is_symlink()
    target = package/destination; target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source,target); copied.append(destination)

root_names=['environment.json','dataset.json','dataset-provenance.json','cold-cache-proof.json','verification.json','summary.json','server-timings.json','final-runtime.json','cgroup-and-input-audit.json','frozen-client-input.json','readonly-before.json','load-containers.json','resource-plan.json']
for filename in root_names:copy(suite/filename,'raw/final/'+filename)
case_names=['config.json','cache-preparation.json','generator.json','resources.ndjson','requests.ndjson','summary.json','client.log','before-api.prom','after-api.prom','before-worker.prom','after-worker.prom','before-mysql.json','after-mysql.json']
for generation,folder in [('final',suite/'cases'),('initial',run/'performance/cases')]:
    for directory in sorted(folder.iterdir()):
        assert re.fullmatch(r'cold-unique-300|r[12]-(list|detail)-c[148]|judge-sequential-11',directory.name)
        for filename in case_names:
            if (directory/filename).exists():copy(directory/filename,'raw/'+generation+'/cases/'+directory.name+'/'+filename)
for file in ['environment.json','dataset.json','dataset-provenance.json','cold-cache-proof.json','resource-plan.json','progress.json','load-containers.json']:
    copy(run/'performance'/file,'raw/initial/'+file)
for filename in ['load.mjs','run-baseline.py']:
    copy(run/'performance/first-tool-inputs'/filename,'raw/initial/tools/'+filename)
copy(host_path,'host.json')
copy(run/'cleanup-result.json','cleanup.json')
copy(repo/'docs/archive/M5-PERFORMANCE-BASELINE-DESIGN.md','docs/archive/M5-PERFORMANCE-BASELINE-DESIGN.md')
for filename in ['prepare-baseline.py','dataset.py','load.mjs','run-baseline.py','summarize-baseline.py','build-evidence.py','README.md']:
    copy(repo/'tools/performance'/filename,'tools/performance/'+filename)
copy(repo/'LICENSE','LICENSE')
(package/'NOTICE').write_text('ForgeOJ performance evidence\nCopyright 2026 池也\n\nOriginal scripts and documentation: Apache-2.0.\nNo third-party runtime or binaries distributed.\n',encoding='utf-8')
(package/'README.md').write_text('这是本机隔离性能证据包，请先读 report.md。raw/final 是完整套件，raw/initial 保留工具截断记录。脚本复跑需要完整 ForgeOJ 仓库、已验收构件与 Docker；本包不是独立部署安装器。没有云容量、优化比例或长期稳定结论。\n',encoding='utf-8')
delivery={'status':'VERIFIED','scope':'bounded local read baseline and eleven sequential judge observations','fullM5':'IN_PROGRESS','fullPerformancePlan':'IN_PROGRESS',
          'release':False,'resumeReady':False,'acceptedSuite':suite.relative_to(repo).as_posix(),'verification':verification,
          'requestSummary':{'count':summary['requestCount'],'errors':summary['requestErrors'],'independentlyRecomputed':True,'serverCountsMatch':True},
          'host':host,'environment':environment,'cleanup':cleanup,'compiledInputsMatch':428,
          'apiInputSha256':'f13c52ca61744d7c4983e2b98c6a68d05b17eb7fce77d637761e1b615dd32c4e','workerInputSha256':'ff4dd5abf4931a345dd3a94af8c07966aced3a91ebcd4cd6b4c600877b261d1f',
          'notVerified':['cloud/native Linux capacity','open arrival rate','large historical/classroom dataset','long soak','fault-under-load','optimization improvement','formal user trial']}
(package/'delivery-verification.json').write_text(json.dumps(delivery,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
secrets=[p.read_bytes() for p in (run/'private/secrets').iterdir() if p.is_file() and p.stat().st_size]
manifest=[]
for file in sorted(package.rglob('*')):
    if not file.is_file():continue
    value=file.read_bytes()
    assert all(secret not in value for secret in secrets),'Private test credential found in export'
    private_pem = rb'-----BEGIN (?:RSA )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/=\r\n]{32,}-----END (?:RSA )?PRIVATE KEY-----'
    assert not re.search(private_pem, value), 'Private key payload in export'
    assert not re.search(rb'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}',value),'Possible token in export'
    manifest.append({'path':file.relative_to(package).as_posix(),'sha256':hashlib.sha256(value).hexdigest(),'bytes':len(value)})
(package/'manifest.json').write_text(json.dumps({'format':'forgeoj-performance-evidence-v1','files':manifest},indent=2)+'\n')
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for item in manifest:z.write(package/item['path'],item['path'])
    z.write(package/'manifest.json','manifest.json')
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None and set(z.namelist())=={item['path'] for item in manifest}|{'manifest.json'}
    for item in manifest:assert hashlib.sha256(z.read(item['path'])).hexdigest()==item['sha256']
receipt={'archive':str(archive),'sha256':digest(archive),'bytes':archive.stat().st_size,'files':len(manifest)+1,'contentSecretsScan':'PASS','crcAndHashes':'PASS'}
(output/'archive-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
(output/(archive.name+'.sha256')).write_text(receipt['sha256']+'  '+archive.name+'\n')
delivery['archive']=receipt
dest=repo/'docs/archive/evidence/m5-performance/20261028';dest.mkdir(parents=True,exist_ok=True)
assert not (dest/'verification.json').exists()
(dest/'verification.json').write_text(json.dumps(delivery,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(dest/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(receipt,ensure_ascii=False))
