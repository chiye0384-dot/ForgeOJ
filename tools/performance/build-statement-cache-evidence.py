#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Build a dated report and allowlisted, credential-scanned A/B evidence archive."""
import datetime as dt
import hashlib
import json
import pathlib
import re
import shutil
import sys
import uuid
import zipfile

repo=pathlib.Path(__file__).resolve().parents[2];run=(repo/sys.argv[1]).resolve()
assert run.is_relative_to(repo/'target')
out=run/'statement-cache-ab'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
write=lambda p,v:p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
s=read(out/'summary.json');v=read(out/'verification.json');cleanup=read(run/'cleanup-result.json')
assert s['status']==v['status']=='PASS' and s['independentRawRecalculation']
state=read(run/'validation-state.json');assert cleanup['project']==state['project']
assert cleanup['containers']==cleanup['volumes']==cleanup['networks']==0
for item in v['toolHashes']:assert sha(repo/item['path'])==item['sha256']
for item in read(out/'source-inputs.json')['inputs']:assert sha(repo/item['path'])==item['sha256']
source=read(out/'source-inputs.json')
rows=['# M5 语句解析缓存隔离 A/B 报告','',
      '2026-11-13，六阶段对照与逐请求复核有限单元 **VERIFIED**。完整M5/性能计划仍 **IN_PROGRESS**；没有自动修改生产连接参数、正式Release或云容量结论。','',
      '## 结果','',
      f"正式24个测量阶段共 **{s['measuredRequests']:,}次请求**，状态/网络/超时/内容错误0，原始NDJSON全部独立重算并与HTTP计数一致。另有18个JVM预热阶段，测量请求{s['jvmWarmupMeasurementRequestsExcluded']:,}次全部排除在正式吞吐之外；各客户端的额外预热行也独立核对。没有新增判题提交。",'',
      '| 场景 | 三对吞吐变化中位数 | 三对P95变化中位数 | 三对均吞吐升/P95降 | A/B吞吐范围重叠 |',
      '|---|---:|---:|---|---|']
for c in s['comparisons']:
    rows.append(f"| {c['case']} | {c['pairedMedianRpsChangePct']:+.2f}% | {c['pairedMedianP95ChangePct']:+.2f}% | {'是' if c['allPairsThroughputUpAndP95Down'] else '否'} | {'是' if c['throughputRangesOverlap'] else '否'} |")
rows+=['','**本轮结论：候选不作为当前生产优化采用，保留默认关闭。** 并发4下三对吞吐/P95方向一致，但变化与轮间区间重叠；并发8列表三对吞吐均下降，详情也没有一致的联合改善。没有建立全场景稳定收益，不能把第一对较大的差异当作部署后的提升比例。退化的唯一原因未被本实验确认。', '',
       '变化均为(B/A-1)，P95负值表示降低。三对中位数只是本轮描述，不是置信区间、统计显著性或未来提升承诺。两组区间/每轮结果全部展示，不能以最快值代表一组。', '',
       '若各对方向不一致，或差异与轮间波动重叠，则没有建立稳定的联合改善；即使某场景方向一致，也仅覆盖本机共享环境的小数据量、短时闭环负载。是否采用应结合下面的CPU成本、尾延迟和复测风险，不能套用到未购买的服务器。', '',
       '## 条件与生效验证','',
       'A明确cachePrepStmts=false，B明确true；两组prepStmtCacheSize=64、prepStmtCacheSqlLimit=4096、useServerPrepStmts=false。每阶段替换为同一镜像的新API容器，其他JVM、连接池、身份、事务、Redis/ES/消息与MySQL配置不变。API和MySQL各2CPU，内存分别768MiB/1024MiB；负载客户端1CPU/512MiB。资源只在这份测试包固定，不是2核2G整机模拟。','',
       '顺序A/B、B/A、A/B，共三对六个JVM。每阶段list/detail/list并发4各20秒预热，再测list/detail并发4/8各20秒；测量顺序相同。详情阶段只删除300个已知合成题缓存键，然后先预热全部300题。没有全局flush、改身份预算或放宽限流。每次只有一个负载容器，经过证书验证的Nginx HTTPS、同一Linux桥网络，keep-alive/无自动重试/5秒请求超时。','',
       '在实际镜像、API角色和同一JDBC URL建立单独只读连接，读取驱动生效参数并以实际QueryInfo对象验证：B复用短SQL解析对象，A不复用；超过4096字符不复用；同一SQL的不同参数分别得到1和2。该探针证明驱动参数和解析缓存行为，不是直接查看应用连接池内部对象，也不是缓存查询结果。探针不计入HTTP测量。','',
       '本轮不开JFR，避免上次采样混杂；仍存在共享宿主负载、GC、JIT、CPU调度等影响，未测GC暂停或分配率。cgroup前后差值包含客户端的少量请求预热；节流累计微秒不能直接当墙钟等待百分比。没有SQL摘要精确次数结论，上一诊断中的摘要计数差异仍未解释。','',
       '数据集仍为1个合成普通用户、301公共题、301判题版本、903用例、无班级和大历史；合成题面短/中/长各100。数据摘要09d4eaa246747eb77d0525b7aecdebba7db045f44b5ff5eb6592bccd8d0a682b。fixture只在本轮新空库安装。','',
       '## 全部配对结果','',
       '| 场景/对 | 顺序 | A req/s | B req/s | 吞吐变化 | A P95 ms | B P95 ms | P95变化 | A P99 ms | B P99 ms |',
       '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|']
for c in s['comparisons']:
    for p in c['pairs']:
        a,b=p['A'],p['B']
        rows.append(f"| {c['case']}/{p['pair']} | {p['order']} | {a['rps']:.2f} | {b['rps']:.2f} | {p['rpsChangePct']:+.2f}% | {a['latencyMs']['p95Ms']:.3f} | {b['latencyMs']['p95Ms']:.3f} | {p['p95ChangePct']:+.2f}% | {a['latencyMs']['p99Ms']:.3f} | {b['latencyMs']['p99Ms']:.3f} |")
rows+=['','## CPU和内存观测','',
       '每请求CPU为进程cgroup usage增量除以本阶段全部请求（含30或300次客户端预热）；它不是某条SQL/函数的精确成本。内存为前后点采样，不是峰值。完整微秒/节流周期与内存原值见机器归纳。','',
       '| 阶段 | API CPU μs/请求 | MySQL CPU μs/请求 | API结束内存MiB | API节流周期 | MySQL节流周期 |',
       '|---|---:|---:|---:|---:|---:|']
for row in s['measuredCells']:
    a,b=row['cpu']['api'],row['cpu']['mysql']
    rows.append(f"| {row['arm']}/{row['case']} | {a['usageUsecPerRequestIncludingWarmup']:.1f} | {b['usageUsecPerRequestIncludingWarmup']:.1f} | {a['memoryAfterBytes']/1024**2:.1f} | {a['deltaIncludingRequestWarmup']['nr_throttled']} | {b['deltaIncludingRequestWarmup']['nr_throttled']} |")
rows+=['','## 当前构件与部署准备修正','',
       '使用当前6bb1c29业务源码的验收构件：API6f79f700c2d4c17843172c1f4aeb66a25ad7f44a57016ba7b75b706e56b1be52、Worker2be34c53d39560ddd955e761fcad7fb06a6cd19d8ea2720b36b3d8990d7a5761。437个当前输入逐文件匹配：342个最近API/前端输入+95个未变Worker输入；前端本轮159 tests/30 suites及全部检查、构建通过。后端沿用按日期接受的58项针对性API及139项Worker证据，不冒充本轮完整后端重建或全套回归。','',
       '旧构建器固定428项历史输入，会正确拒绝更新后的源码；本轮增加显式输入清单，仍逐项核对、记录清单摘要，默认历史路径保持。重复路径、越界路径、错误摘要、空前端目录四个实际拒绝通过。原业务源码/schema/grants未改。','',
       '首个组装与异步前端重建重叠，复制时静态目录为空。保留该包与记录并标为NOT_ACCEPTED，没有拿它进行A/B。旧烟测仅检查HTTP200，误通过了Nginx自带欢迎页；新增应用id/JS资源门禁后，对同一空前端运行实际拒绝。已精确清理第一项目，再顺序组装37个实际前端文件，新的18项HTTPS/页面/权限检查通过。没有隐藏此前假阳性，原历史报告也不改写为新门禁已执行。','',
       '## 数据、安全与边界','',
       '所有实验阶段前后表结构/数据/授权/Flyway摘要相同，实验正式提交数始终0。原网站项目保持停止，其11月8日恢复时核对过的51条提交、3份草稿所在的数据卷未操作；本轮未重新启动原库查行数。共享Docker数据目录配置、冷备份与学习Redis保持原安排。只清理两个本轮拥有者项目及准确负载容器和镜像别名，不做全局prune/WSL关闭/客户端重启。','',
       '完整逐请求记录、参数探针、配对归纳和失败包元数据以白名单打包；私密env、密码、证书、数据库dump与上游二进制不进入证据包。实际随机秘密/完整私钥PEM/JWT扫描、ZIP CRC和逐文件摘要应由交付收据确认。','',
       '本轮不是整机2核2G容量或两个项目共存试验；没有长稳、开放到达模型、大历史、多用户写负载或故障负载结论。真实主机、HTTPS域名、SMTP、异地备份/密钥保管、版本回滚与用户试用仍需后续完成。先前部署ZIP保持绑定原构件，不以本次性能工具作为正式Release。','',
       '设计见 [M5-STATEMENT-CACHE-AB-DESIGN.md](M5-STATEMENT-CACHE-AB-DESIGN.md)，独立归纳和交付收据见 [summary.json](evidence/m5-statement-cache-ab/20261113/summary.json)、[verification.json](evidence/m5-statement-cache-ab/20261113/verification.json)。']
report=repo/'docs/archive/M5-STATEMENT-CACHE-AB-REPORT.md';report.write_text('\n'.join(rows)+'\n',encoding='utf-8')

stamp=dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
name='forgeoj-statement-cache-ab-'+stamp+'-'+uuid.uuid4().hex[:8];delivery=repo/'target'/name;package=delivery/'package';package.mkdir(parents=True)
files={}
def add(path,name):
    assert path.is_file() and not path.is_symlink() and name not in files;files[name]=path
for n in ['summary.json','verification.json','environment.json','source-inputs.json','facts-before.json','dataset.json','progress.json','load-containers.json']:
    add(out/n,'raw/'+n)
for arm in sorted(out.glob('[0-9][0-9]-[AB]')):
    for n in ['api-override.yml','api-start.log','runtime.json','driver-probe.json','complete.json']:add(arm/n,'raw/'+arm.name+'/'+n)
    for row in read(out/'progress.json'):
        if row['arm']!=arm.name:continue
        path=out/row['path']
        for n in ['config.json','before-api.prom','after-api.prom','resources-before.json','resources-after.json','client.log','requests.ndjson','summary.json','independent-analysis.json']:
            add(path/n,'raw/'+row['path']+'/'+n)
        if (path/'cache-preparation.json').exists():add(path/'cache-preparation.json','raw/'+row['path']+'/cache-preparation.json')
for p in (out/'frozen-tools').iterdir():add(p,'tools/frozen/'+p.name)
for n in ['run-statement-cache-ab.py','StatementCacheProbe.java','summarize-statement-cache-ab.py','build-statement-cache-evidence.py','README.md']:add(repo/'tools/performance'/n,'tools/performance/'+n)
for n in ['Build-ProductionPackage.ps1','smoke-https.mjs']:add(repo/'tools/deployment'/n,'tools/deployment/'+n)
for n in ['M5-STATEMENT-CACHE-AB-DESIGN.md','M5-STATEMENT-CACHE-AB-REPORT.md']:add(repo/'docs/archive'/n,'docs/archive/'+n)
for n in ['cleanup-result.json','https-smoke.json','static-assets-smoke.json']:add(run/n,'run/'+n)
for n in ['not-accepted.json','cleanup-result.json','package/package-manifest.json']:
    add(repo/'target/forgeoj-deployment-20261113-110113-a3df0e34'/n,'failed-empty-frontend/'+n)
for n in ['statement-cache-ab-20261113-builder-negatives.json','statement-cache-ab-20261113-empty-frontend-rejected.log','statement-cache-ab-20261113-frontend.log']:
    add(repo/'target'/n,'checks/'+n)
for n in ['LICENSE','NOTICE']:add(repo/n,n)
readme=delivery/'README.md'
readme.write_text('ForgeOJ 语句解析缓存有限A/B证据。先读 docs/archive/M5-STATEMENT-CACHE-AB-REPORT.md，raw/ 保存原始请求与独立归纳，delivery.json 保存逐文件摘要。\n这是审阅包，复现需完整ForgeOJ源码、固定构件与新建测试环境；报告中的历史报告/仓库证据链接需在完整仓库查看。\n不包含私密配置、证书、数据库dump或上游运行时，不是生产安装器或收益承诺。\n',encoding='utf-8')
add(readme,'README.md')
secrets=[p.read_bytes().strip() for p in (run/'private/secrets').iterdir() if p.is_file() and p.stat().st_size]
assert len(secrets)==13
manifest=[]
for dest,path in sorted(files.items()):
    raw=path.read_bytes();assert not any(secret in raw for secret in secrets),dest
    assert not re.search(rb'-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\s\S]{24,}?-----END [A-Z0-9 ]*PRIVATE KEY-----',raw),dest
    assert not re.search(rb'eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}',raw),dest
    target=package/dest;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,target)
    manifest.append({'path':dest,'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
receipt={'status':'VERIFIED','scope':s['scope'],'decision':'retain default cache-disabled configuration; no stable whole-workload improvement established','fullM5':'IN_PROGRESS','fullPerformancePlan':'IN_PROGRESS','productionConfigurationChanged':False,'project':state['project'],'run':run.relative_to(repo).as_posix(),'measurementRequests':s['measuredRequests'],'requestErrors':0,'independentRawRecalculation':True,'compiledInputsMatch':437,'cleanup':cleanup,'secretScan':'PASS actual random test secret bytes + private PEM/JWT patterns','files':manifest}
write(package/'delivery.json',receipt)
archive=delivery/(name+'.zip')
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(package.rglob('*')):
        if p.is_file():z.write(p,p.relative_to(package).as_posix())
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None and len(z.namelist())==len(manifest)+1
    for item in manifest:assert hashlib.sha256(z.read(item['path'])).hexdigest()==item['sha256']
receipt['archive']={'path':archive.relative_to(repo).as_posix(),'bytes':archive.stat().st_size,'sha256':sha(archive),'files':len(manifest)+1,'crcAndHashes':'PASS'}
write(delivery/'archive-receipt.json',receipt);(delivery/(archive.name+'.sha256')).write_text(receipt['archive']['sha256']+'  '+archive.name+'\n')
evidence=repo/'docs/archive/evidence/m5-statement-cache-ab/20261113';evidence.mkdir(parents=True,exist_ok=False)
write(evidence/'verification.json',receipt);shutil.copyfile(out/'summary.json',evidence/'summary.json');shutil.copyfile(out/'source-inputs.json',evidence/'source-inputs.json')
print(json.dumps(receipt['archive']))
