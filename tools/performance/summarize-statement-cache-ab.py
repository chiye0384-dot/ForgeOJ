#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Recompute every request row and report all paired observations, never a promised gain."""
import collections
import hashlib
import json
import math
import pathlib
import re
import statistics
import sys

repo=pathlib.Path(__file__).resolve().parents[2];run=(repo/sys.argv[1]).resolve()
assert run.is_relative_to(repo/'target')
out=run/'statement-cache-ab'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
write=lambda p,v:p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
verification=read(out/'verification.json');assert verification['status']=='PASS'
progress=read(out/'progress.json');assert len(progress)==42
assert [read(out/(f'{i:02d}-'+g)/'complete.json')['group'] for i,g in enumerate('ABBAAB',1)]==list('ABBAAB')
for item in verification['toolHashes']:
    assert hashlib.sha256((repo/item['path']).read_bytes()).hexdigest()==item['sha256']

def metric(text,name):
    match=re.search(r'^'+re.escape(name)+r' ([0-9]+)$',text,re.MULTILINE)
    return int(match[1]) if match else 0
def p(values,percent):return sorted(values)[math.ceil(len(values)*percent)-1]
rows=[];warm=[]
for record in progress:
    path=out/record['path'];summary=read(path/'summary.json')
    raw=[json.loads(line) for line in (path/'requests.ndjson').read_text().splitlines()]
    measured=[row for row in raw if row['phase']=='measurement'];warmup=[row for row in raw if row['phase']=='warmup']
    assert len(measured)==summary['measurementCount'] and len(warmup)==summary['warmupCount']
    assert len(raw)==len(measured)+len(warmup)
    assert all(r['status']==200 and r['valid'] and r['error'] is None for r in raw)
    assert summary['errors']==summary['networkErrors']==summary['timeouts']==summary['semanticErrors']==0
    expected=300 if summary['mode']=='detail' else 30;assert len(warmup)==expected
    for q,key in [(.5,'p50Ms'),(.95,'p95Ms'),(.99,'p99Ms')]:
        assert p([r['latencyMs'] for r in measured],q)==summary['allLatency'][key]==summary['successLatency'][key]
    assert math.isclose(len(measured)/(summary['elapsedMs']/1000),summary['successfulRps'],rel_tol=1e-12)
    assert 20000<=summary['elapsedMs']<30000 and len(measured)<100000
    expression='forgeoj_http_requests_total{route="library",status_class="2xx"}'
    before=(path/'before-api.prom').read_text();after=(path/'after-api.prom').read_text()
    assert metric(after,expression)-metric(before,expression)==len(raw)==record['serverHttpDelta']
    b=read(path/'resources-before.json');a=read(path/'resources-after.json');cpu={}
    for service in ['api','mysql']:
        delta={key:a[service]['cpu'][key]-value for key,value in b[service]['cpu'].items()}
        assert all(value>=0 for value in delta.values())
        cpu[service]={'deltaIncludingRequestWarmup':delta,'usageUsecPerRequestIncludingWarmup':delta['usage_usec']/len(raw),'memoryBeforeBytes':b[service]['memoryCurrentBytes'],'memoryAfterBytes':a[service]['memoryCurrentBytes']}
    row={'arm':record['arm'],'group':record['group'],'case':record['case'],'requestCount':len(measured),'warmupRequests':len(warmup),'rps':summary['successfulRps'],'latencyMs':summary['successLatency'],'elapsedMs':summary['elapsedMs'],'clientCpuPercent':summary['clientCpuPercent'],'clientEventLoopP99Ms':summary['clientEventLoopP99Ms'],'cpu':cpu}
    (rows if record['measured'] else warm).append(row)
    write(path/'independent-analysis.json',{'status':'PASS','rawRowsRecomputed':True,**row})
assert len(rows)==24 and len(warm)==18

comparisons=[]
for mode in ['list','detail']:
    for c in [4,8]:
        case=mode+'-c'+str(c);pairs=[]
        for first,second in [(1,2),(3,4),(5,6)]:
            pair=[row for row in rows if row['case']==case and int(row['arm'][:2]) in [first,second]]
            assert len(pair)==2
            a=next(row for row in pair if row['group']=='A');b=next(row for row in pair if row['group']=='B')
            pairs.append({'pair':(first+1)//2,'order':''.join(row['group'] for row in sorted(pair,key=lambda r:r['arm'])),'A':a,'B':b,'rpsChangePct':(b['rps']/a['rps']-1)*100,'p95ChangePct':(b['latencyMs']['p95Ms']/a['latencyMs']['p95Ms']-1)*100,'p99ChangePct':(b['latencyMs']['p99Ms']/a['latencyMs']['p99Ms']-1)*100})
        ar=[pair['A']['rps'] for pair in pairs];br=[pair['B']['rps'] for pair in pairs]
        overlap=min(max(ar),max(br))>=max(min(ar),min(br))
        directional=all(pair['rpsChangePct']>0 and pair['p95ChangePct']<0 for pair in pairs)
        comparisons.append({'case':case,'pairs':pairs,'pairedMedianRpsChangePct':statistics.median(pair['rpsChangePct'] for pair in pairs),'pairedMedianP95ChangePct':statistics.median(pair['p95ChangePct'] for pair in pairs),'AThroughputRange':[min(ar),max(ar)],'BThroughputRange':[min(br),max(br)],'throughputRangesOverlap':overlap,'allPairsThroughputUpAndP95Down':directional,'interpretation':'consistent_direction_in_this_small_sample_only' if directional and not overlap else 'no_stable_joint_improvement_established'})

summary={'status':'PASS','scope':'same current JAR and fixture, three interleaved pairs, no statistical or cloud capacity claim','independentRawRecalculation':True,'measuredRequests':sum(row['requestCount'] for row in rows),'jvmWarmupMeasurementRequestsExcluded':sum(row['requestCount'] for row in warm),'requestPrewarmRows':sum(row['warmupRequests'] for row in rows+warm),'requestErrors':0,'measuredCells':rows,'jvmWarmupStages':warm,'comparisons':comparisons,'productionConfigurationChanged':False,'driverProbeScope':'Separate connection with the actual API URL/driver/role; not an inspection of API pool internals','gcProfile':'No new JFR or GC pause capture; no claim to exclude GC or host interference','fullM5':'IN_PROGRESS','fullPerformancePlan':'IN_PROGRESS'}
write(out/'summary.json',summary)
print(json.dumps({k:summary[k] for k in ['status','measuredRequests','requestErrors','independentRawRecalculation']}))
for c in comparisons:print(json.dumps({k:v for k,v in c.items() if k!='pairs'}))
