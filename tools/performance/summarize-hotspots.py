#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Independently reconcile requests, normalized SQL deltas, cgroups and JFR samples."""
import collections
import datetime as dt
import json
import math
import pathlib
import re
import sys

repo=pathlib.Path(__file__).resolve().parents[2]
run=(repo/sys.argv[1]).resolve();assert run.is_relative_to(repo/'target')
out=run/'diagnostics'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
verified=read(out/'verification.json');assert verified['status']=='PASS'


def percentile(values,p):
    return sorted(values)[math.ceil(len(values)*p)-1]


def duration_seconds(value):
    match=re.fullmatch(r'PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?',value)
    assert match,value
    return float(match[1] or 0)*3600+float(match[2] or 0)*60+float(match[3] or 0)


def family(text):
    sql=re.sub(r'\s+',' ',text.replace('`','')).lower()
    if sql.startswith(('set ','commit','rollback','start transaction','begin')):return 'transaction-control'
    if 'select @@session' in sql and 'transaction_isolation' in sql:return 'connection-state-probe'
    if re.match(r"select revision from cache_epoch where namespace\s*=",sql) and 'for update' not in sql:return 'detail-epoch'
    # The long detail projection is truncated by the server's digest length limit.
    if sql.startswith('select p . slug') and 'statement_text' in sql and 'publicsamplesjson' in sql:return 'detail-load-warmup'
    if 'problem_judge_version' in sql:
        if re.match(r'select count\s*\(',sql):return 'list-count'
        if 'content_validation_snapshot' in sql:return 'detail-load-warmup'
        if 'order by p' in sql:return 'list-page'
    if 'problem_tag' in sql and 'problem_judge_version' not in sql:return 'list-tags'
    return 'background-or-observer'


def method(frame):
    m=frame.get('method') or {};t=m.get('type') or {}
    return t.get('name','unknown').replace('/','.')+'.'+m.get('name','unknown')


def text_samples(path):
    blocks=[];current=None
    for line in path.read_text().splitlines():
        if line in ['jdk.ExecutionSample {','jdk.NativeMethodSample {']:
            current={'type':line.split()[0],'frames':[],'inStack':False}
        elif current is not None:
            if line=='}':
                blocks.append(current);current=None;continue
            stripped=line.strip()
            match=re.match(r'startTime = (\d\d:\d\d:\d\d\.\d+) \((\d{4}-\d\d-\d\d)\)',stripped)
            if match:current['time']=dt.datetime.fromisoformat(match[2]+'T'+match[1]+'+00:00')
            if stripped.startswith('sampledThread = '):current['thread']=stripped.split('"')[1]
            if stripped=='stackTrace = [':current['inStack']=True
            elif stripped==']':current['inStack']=False
            elif current['inStack'] and '(' in stripped:current['frames'].append(stripped.split('(')[0])
    return blocks


def top(counter,limit=12):
    return [{'name':name,'samples':count} for name,count in counter.most_common(limit)]


results=[]
for case in ['list-c4','detail-c4','list-c8','detail-c8']:
    path=out/case;s=read(path/'summary.json');assert s['status']=='PASS' and s['errors']==0
    rows=[json.loads(line) for line in (path/'requests.ndjson').read_text().splitlines() if line.strip()]
    measured=[row for row in rows if row['phase']=='measurement']
    assert len(measured)==s['measurementCount'] and all(row['status']==200 and row['valid'] and row['error'] is None for row in rows)
    for key,p in [('p50Ms',.5),('p95Ms',.95),('p99Ms',.99)]:assert percentile([r['latencyMs'] for r in measured],p)==s['allLatency'][key]
    expression=r'^forgeoj_http_requests_total\{route="library",status_class="2xx"\} ([0-9]+)$'
    http=[int(re.search(expression,(path/(when+'-api.prom')).read_text(),flags=re.MULTILINE)[1]) for when in ['before','after']]
    assert http[1]-http[0]==len(rows)
    before={row['digest']:row for row in read(path/'sql-before.json')};sql=[]
    for row in read(path/'sql-after.json'):
        previous=before.get(row['digest'],{})
        delta={key:row[key]-previous.get(key,0) for key in ['count','totalPs','lockPs','rowsExamined','rowsSent','noIndex','noGoodIndex','errors']}
        assert all(value>=0 for value in delta.values())
        if delta['count']:
            sql.append({'family':family(row['normalized']),'digest':row['digest'],'normalized':row['normalized'],**delta,
                        'totalElapsedMs':delta['totalPs']/1e9,'meanElapsedMs':delta['totalPs']/delta['count']/1e9,
                        'meanRowsExamined':delta['rowsExamined']/delta['count']})
    aggregates=collections.defaultdict(lambda:{'count':0,'totalPs':0,'rowsExamined':0})
    for row in sql:
        for key in ['count','totalPs','rowsExamined']:aggregates[row['family']][key]+=row[key]
    expected_sql={name:len(rows) for name in (['list-count','list-page','list-tags'] if s['mode']=='list' else ['detail-epoch'])}
    if s['mode']=='detail': expected_sql['detail-load-warmup']=300
    sql_coverage={name:{'expectedFromRequestAndMapper':expected,'observedDigestDelta':aggregates[name]['count'],
                        'difference':aggregates[name]['count']-expected,'ratio':aggregates[name]['count']/expected}
                  for name,expected in expected_sql.items()}
    assert all(v['observedDigestDelta']>0 for v in sql_coverage.values())
    sql.sort(key=lambda row:row['totalPs'],reverse=True)
    first=read(path/'cgroup-before.json');last=read(path/'cgroup-after.json')
    cgroups={name:{key:last['stats'][name][key]-first['stats'][name][key] for key in ['usage_usec','user_usec','system_usec','nr_periods','nr_throttled','throttled_usec']} for name in ['api','mysql']}
    assert all(value>=0 for entry in cgroups.values() for value in entry.values())
    start=dt.datetime.fromisoformat(s['startedAt']);end=dt.datetime.fromisoformat(s['finishedAt'])
    events=read(path/'events-compact.json')['recording']['events']
    java=collections.Counter();request_java=collections.Counter();native=collections.Counter();request_native=collections.Counter()
    java_count=request_java_count=native_count=request_native_count=0;gc=[]
    for event in events:
        value=event['values'];at=dt.datetime.fromisoformat(value['startTime'])
        if event['type']=='jdk.GCPhasePause':
            until=at+dt.timedelta(seconds=duration_seconds(value['duration']))
            overlap=(min(until,end)-max(at,start)).total_seconds()*1000
            if overlap>0:gc.append(overlap)
        if not start<=at<=end:continue
        if event['type'] not in ['jdk.ExecutionSample','jdk.NativeMethodSample']:continue
        frames=(value.get('stackTrace') or {}).get('frames') or []
        leaf=method(frames[0]) if frames else 'unavailable'
        is_request=(value.get('sampledThread') or {}).get('javaName','').startswith('http-nio-8080-exec-')
        if event['type']=='jdk.ExecutionSample':
            java[leaf]+=1;java_count+=1
            if is_request:request_java[leaf]+=1;request_java_count+=1
        else:
            native[leaf]+=1;native_count+=1
            if is_request:request_native[leaf]+=1;request_native_count+=1
    application=collections.Counter();inclusive=collections.Counter();text_count=0;missing_app=0
    for sample in text_samples(path/'stacks.txt'):
        if sample['type']!='jdk.ExecutionSample' or not sample.get('thread','').startswith('http-nio-8080-exec-') or not start<=sample['time']<=end:continue
        text_count+=1;frames=sample['frames']
        app=next((f for f in frames if f.startswith('com.forgeoj.')),None)
        if app:application[app]+=1
        else:missing_app+=1
        for label,prefixes in {'json':('tools.jackson.','com.fasterxml.jackson.'),'jdbc-mybatis':('com.mysql.','org.apache.ibatis.','org.mybatis.'),
                               'logging':('ch.qos.logback.','org.springframework.boot.logging.'),'redis-netty':('io.lettuce.','io.netty.'),
                               'digest':('java.security.MessageDigest','sun.security.provider.SHA')}.items():
            if any(frame.startswith(prefixes) for frame in frames):inclusive[label]+=1
    assert abs(text_count-request_java_count)<=2
    assert request_java_count>=100,'Too few request Java samples for a hotspot conclusion'
    safety=read(path/'event-safety.json');assert all(v==0 for v in safety['forbiddenEventCounts'].values())
    result={'case':case,'requestSummary':s,'serverConfirmedIncludingWarmup':len(rows),'sqlCountCoverage':sql_coverage,
            'sqlStatements':sql,'sqlFamilies':dict(aggregates),'cpuDeltaIncludingWarmupAndProbeControl':cgroups,
            'jfr':{'measurementJavaSamples':java_count,'requestJavaSamples':request_java_count,'topJavaLeaf':top(java),
                   'topRequestJavaLeaf':top(request_java),'measurementNativeSamples':native_count,'requestNativeSamples':request_native_count,
                   'topNativeLeafNotCpuTime':top(native),'topRequestNativeLeafNotCpuTime':top(request_native),
                   'requestJavaTextSamples':text_count,'topClosestApplicationFrame':top(application),'missingApplicationFrameIn32':missing_app,
                   'inclusiveFamiliesMayOverlap':dict(inclusive),'gcPauseCount':len(gc),'gcPauseOverlapMs':sum(gc),'maxGcPauseOverlapMs':max(gc,default=0)},
            'eventSafety':safety}
    write_path=path/'analysis.json';write_path.write_text(json.dumps(result,indent=2)+'\n')
    results.append(result)
summary={'status':'PASS','scope':'probe-enabled diagnosis only; no optimization comparison','cases':results,
         'requests':sum(r['requestSummary']['measurementCount'] for r in results),'businessFactsUnchanged':verified['businessFactsAndGrantsUnchanged']}
summary['digestCountLimitation']='Digest deltas differ from request/Mapper counts in load phases; cause unconfirmed. Calibration is retained, no exact per-request SQL accounting claim.'
summary['counterCalibration']=read(out/'counter-calibration.json')
(out/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
for r in results:
    print(json.dumps({'case':r['case'],'requests':r['requestSummary']['measurementCount'],'p95Ms':r['requestSummary']['allLatency']['p95Ms'],
                      'sqlCounts':{k:v['count'] for k,v in r['sqlFamilies'].items()},'requestJavaSamples':r['jfr']['requestJavaSamples'],
                      'gcPauseMs':r['jfr']['gcPauseOverlapMs'],'cpu':r['cpuDeltaIncludingWarmupAndProbeControl']}))
