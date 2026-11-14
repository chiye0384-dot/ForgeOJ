#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Owned, bounded Connector/J parse-cache A/B. Never a production configuration change."""
import datetime as dt
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

sys.dont_write_bytecode=True
from dataset import fixture

repo=pathlib.Path(__file__).resolve().parents[2]
run=(repo/sys.argv[1]).resolve();docker=sys.argv[2]
assert run.is_relative_to(repo/'target') and re.fullmatch(r'forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}',run.name)
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
write=lambda p,v:p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
state=read(run/'validation-state.json')
assert state['status']=='RUNTIME_SMOKE_PASS' and re.fullmatch('forgeoj-m5check-[a-f0-9]{12}',state['project'])
assert pathlib.Path(state['package']).resolve()==run/'package' and pathlib.Path(state['instance']).resolve()==run/'instance.env'
spec=importlib.util.spec_from_file_location('ab_backup',repo/'deploy/production/scripts/backup.py')
backup=importlib.util.module_from_spec(spec);spec.loader.exec_module(backup)
instance=backup.Instance(state['package'],state['instance'],docker);assert instance.project==state['project']
base=instance.compose
out=run/'statement-cache-ab';out.mkdir()
assert instance.sql('SELECT COUNT(*) FROM user_account;').strip()=='0'
assert instance.sql('SELECT COUNT(*) FROM submission;').strip()=='0'
manifest=read(run/'package/package-manifest.json');inputs=read(repo/manifest['compiledInputManifest'])['inputs']
assert len(inputs)==437
for i in inputs:assert sha(repo/i['path'])==i['sha256'],i['path']
assert sha(repo/manifest['compiledInputManifest'])==manifest['compiledInputManifestSha256']
shutil.copyfile(repo/manifest['compiledInputManifest'],out/'source-inputs.json')
frozen=out/'frozen-tools';frozen.mkdir()
for name in ['load.mjs','dataset.py','StatementCacheProbe.java','run-statement-cache-ab.py']:
    shutil.copyfile(repo/'tools/performance'/name,frozen/name)
public=out/'public';public.mkdir();shutil.copyfile(run/'private/tls/fullchain.pem',public/'ca.pem')
sql,data=fixture();assert data['sha256']=='09d4eaa246747eb77d0525b7aecdebba7db045f44b5ff5eb6592bccd8d0a682b'
instance.sql((repo/'forgeoj-api/src/main/resources/db/devdata/R__seed_m0_development_data.sql').read_text(encoding='utf-8-sig'))
instance.sql(sql);write(out/'dataset.json',data);write(public/'dataset.json',data)
assert instance.sql('SELECT COUNT(*) FROM problem;').strip()=='301'
assert instance.sql('SELECT COUNT(*) FROM problem_test_case;').strip()=='903'
before=instance.snapshot();write(out/'facts-before.json',before)
engine=json.loads(backup.command([docker,'info','--format','{{json .}}']))
write(out/'environment.json',{'capturedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'engine':engine,'images':state['images'],'package':manifest,'design':'A B B A A B; new API JVM each arm; 60s list/detail/list warmup; 20s per measured cell; no JFR'})
NODE='node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c'
registry=[];results=[]
def call(args,data=None,limit=8*1024*1024,timeout=90):return backup.command(args,data=data,limit=limit,timeout=timeout)
def metrics(compose):
    shell='token=$(cat /run/secrets/forgeoj.monitoring.token); printf \'header = "Authorization: Bearer %s"\\n\' "$token" | curl --config - --fail --silent --max-time 3 http://127.0.0.1:9091/metrics'
    return call(compose+['exec','-T','api','sh','-c',shell]).decode()
def http_count(value):
    match=re.search(r'^forgeoj_http_requests_total\{route="library",status_class="2xx"\} ([0-9]+)$',value,re.MULTILINE)
    return int(match[1]) if match else 0
def resources(compose):
    return {s:{'cpu':dict((k,int(v)) for k,v in (line.split() for line in call(compose+['exec','-T',s,'cat','/sys/fs/cgroup/cpu.stat']).decode().splitlines())),
       'memoryCurrentBytes':int(call(compose+['exec','-T',s,'cat','/sys/fs/cgroup/memory.current']))} for s in ['api','mysql']}
epoch=instance.sql("SELECT revision FROM cache_epoch WHERE namespace='public';").strip()
keys=['forgeoj:v1:public:'+epoch+':'+hashlib.sha256(('problem:'+p['slug']).encode()).hexdigest() for p in data['problems']]
def reset_detail_cache(compose):
    assert len(keys)==300 and all(re.fullmatch('forgeoj:v1:public:[0-9]+:[a-f0-9]{64}',key) for key in keys)
    shell='export REDISCLI_AUTH=$(cat /run/secrets/redis-password); exec redis-cli --raw UNLINK '+' '.join(keys)
    return int(call(compose+['exec','-T','redis','sh','-c',shell]))
def phase(armdir,compose,tag,mode,concurrency,measured):
    directory=armdir/tag;directory.mkdir();os.chmod(directory,0o777)
    write(directory/'config.json',{'mode':mode,'concurrency':concurrency,'round':int(armdir.name[:2])})
    if mode=='detail':write(directory/'cache-preparation.json',{'onlyKnownSyntheticKeysUnlinked':reset_detail_cache(compose)})
    m0=metrics(compose);(directory/'before-api.prom').write_text(m0,encoding='utf-8')
    write(directory/'resources-before.json',resources(compose))
    name=state['project']+'-ab-'+armdir.name+'-'+tag
    identifier=call([docker,'create','--name',name,'--label','com.forgeoj.perf.project='+state['project'],
      '--network',state['project']+'_edge','--user','65534:65534','--read-only','--cap-drop','ALL',
      '--security-opt','no-new-privileges:true','--cpus','1','--memory','512m','--pids-limit','128',
      '--mount','type=bind,src='+str(frozen)+',dst=/scripts,readonly',
      '--mount','type=bind,src='+str(public)+',dst=/public,readonly',
      '--mount','type=bind,src='+str(directory)+',dst=/output',NODE,'node','/scripts/load.mjs','/output/config.json']).decode().strip()
    assert re.fullmatch('[a-f0-9]{64}',identifier)
    registry.append({'id':identifier,'name':name,'removed':False});write(out/'load-containers.json',registry)
    process=subprocess.run([docker,'start','-a',identifier],capture_output=True,timeout=90)
    (directory/'client.log').write_bytes(process.stdout+process.stderr)
    info=json.loads(call([docker,'inspect',identifier]))[0]
    assert info['Config']['Labels']['com.forgeoj.perf.project']==state['project']
    assert process.returncode==0 and info['State']['ExitCode']==0 and info['State']['Status']=='exited', 'Client failure retained; no automatic retry'
    m1=metrics(compose);(directory/'after-api.prom').write_text(m1,encoding='utf-8')
    write(directory/'resources-after.json',resources(compose))
    summary=read(directory/'summary.json');assert summary['status']=='PASS'
    rows=[json.loads(line) for line in (directory/'requests.ndjson').read_text().splitlines()]
    assert all(row['status']==200 and row['valid'] and row['error'] is None for row in rows)
    assert http_count(m1)-http_count(m0)==len(rows),'Independent HTTP count mismatch'
    call([docker,'rm',identifier]);registry[-1]['removed']=True;write(out/'load-containers.json',registry)
    record={'arm':armdir.name,'group':armdir.name[-1],'case':tag,'measured':measured,'summary':summary,'serverHttpDelta':http_count(m1)-http_count(m0),'path':str(directory.relative_to(out)).replace('\\','/')}
    results.append(record);write(out/'progress.json',results)
    print(json.dumps({k:record[k] for k in ['arm','case','measured']}|{'requests':summary['measurementCount'],'rps':round(summary['successfulRps'],2),'p95Ms':round(summary['successLatency']['p95Ms'],3),'errors':summary['errors']}),flush=True)

for index,group in enumerate('ABBAAB',1):
    armdir=out/(str(index).zfill(2)+'-'+group);armdir.mkdir()
    url='jdbc:mysql://mysql:3306/forgeoj?cachePrepStmts='+str(group=='B').lower()+'&prepStmtCacheSize=64&prepStmtCacheSqlLimit=4096&useServerPrepStmts=false'
    override=out/'api-override.yml';override.write_text('services:\n  api:\n    environment:\n      SPRING_DATASOURCE_URL: '+json.dumps(url)+'\n',encoding='utf-8')
    compose=base+['-f',str(override)]
    shutil.copyfile(override,armdir/'api-override.yml')
    (armdir/'api-start.log').write_bytes(call(compose+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','120','api'],timeout=150))
    infos=json.loads(call([docker,'inspect',state['project']+'-api-1',state['project']+'-mysql-1']))
    api=infos[0];assert api['Config']['Labels']['com.docker.compose.project']==state['project']
    assert api['Image']==state['images'][0]['id'] and api['Config']['User']=='10001:10001'
    assert 'SPRING_DATASOURCE_URL='+url in api['Config']['Env']
    assert all(info['HostConfig']['NanoCpus']==2000000000 for info in infos)
    write(armdir/'runtime.json',{'apiId':api['Id'],'apiImage':api['Image'],'jdbcUrl':url,'resources':[{'service':i['Config']['Labels']['com.docker.compose.service'],'cpuQuota':i['HostConfig']['NanoCpus'],'memoryLimit':i['HostConfig']['Memory'],'pidsLimit':i['HostConfig']['PidsLimit']} for i in infos]})
    probe='/tmp/StatementCacheProbe.java';call(compose+['exec','-T','api','sh','-c','set -C; cat > '+probe],(frozen/'StatementCacheProbe.java').read_bytes())
    raw=call(compose+['exec','-T','api','java','-cp','/app/lib/*',probe],timeout=45)
    probe_result=json.loads(raw);assert probe_result['cachePrepStmts']==(group=='B');write(armdir/'driver-probe.json',probe_result)
    # Nginx uses Docker DNS; let its bounded resolver cache expire after API replacement.
    time.sleep(6)
    for tag,mode in [('warm-list-1','list'),('warm-detail','detail'),('warm-list-2','list')]:phase(armdir,compose,tag,mode,4,False)
    for concurrency in [4,8]:
        for mode in ['list','detail']:phase(armdir,compose,mode+'-c'+str(concurrency),mode,concurrency,True)
    assert not json.loads(call([docker,'inspect',api['Id']]))[0]['State']['OOMKilled']
    assert instance.snapshot()==before,'Business facts/grants changed'
    write(armdir/'complete.json',{'status':'PASS','group':group,'businessFactsAndGrantsUnchanged':True})
assert instance.snapshot()==before and instance.sql('SELECT COUNT(*) FROM submission;').strip()=='0'
for i in inputs:assert sha(repo/i['path'])==i['sha256']
write(out/'verification.json',{'status':'PASS','scope':'bounded six-arm client-side statement parsing cache comparison','arms':'ABBAAB','measurements':24,'warmupStages':18,'factsAndGrantsUnchanged':True,'submissionsCreated':0,'compiledInputsMatch':437,'datasetSha256':data['sha256'],'toolHashes':[{'path':str(p.relative_to(repo)).replace('\\','/'),'sha256':sha(p)} for p in [repo/'tools/performance'/n for n in ['run-statement-cache-ab.py','StatementCacheProbe.java','load.mjs','dataset.py']]],'fullM5':'IN_PROGRESS','fullPerformancePlan':'IN_PROGRESS','productionChanged':False})
print('All six arms captured; independently reduce before claiming a result.',flush=True)
