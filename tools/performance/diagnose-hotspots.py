#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Bounded, owned SQL/JFR diagnosis. No business optimization or production mutation."""
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
import threading

sys.dont_write_bytecode = True
from dataset import fixture
from diagnostic_queries import queries

repo = pathlib.Path(__file__).resolve().parents[2]
run = (repo / sys.argv[1]).resolve(); docker = sys.argv[2]
assert run.is_relative_to(repo / 'target') and re.fullmatch(r'forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}', run.name)
state = json.loads((run / 'validation-state.json').read_text(encoding='utf-8-sig'))
assert re.fullmatch('forgeoj-m5check-[a-f0-9]{12}', state['project']) and state['status'] == 'RUNTIME_SMOKE_PASS'
assert pathlib.Path(state['package']).resolve() == run / 'package' and pathlib.Path(state['instance']).resolve() == run / 'instance.env'
spec = importlib.util.spec_from_file_location('diagnostic_backup', repo / 'deploy/production/scripts/backup.py')
backup = importlib.util.module_from_spec(spec); spec.loader.exec_module(backup)
instance = backup.Instance(state['package'], state['instance'], docker)
assert instance.project == state['project']
compose = instance.compose
resume = '--resume-capture' in sys.argv[3:]
out = run / 'diagnostics'
if resume:
    assert (out / 'facts-before.json').exists() and not (out / 'verification.json').exists()
else:
    out.mkdir()
NODE = 'node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c'
EVENTS = {'jdk.ExecutionSample', 'jdk.NativeMethodSample', 'jdk.CPULoad', 'jdk.ThreadCPULoad', 'jdk.GarbageCollection', 'jdk.GCPhasePause'}
FORBIDDEN = {'jdk.InitialEnvironmentVariable', 'jdk.InitialSystemProperty', 'jdk.JavaExceptionThrow', 'jdk.JavaErrorThrow', 'jdk.SystemProcess', 'jdk.ThreadDump', 'jdk.OldObjectSample'}
now = lambda: dt.datetime.now(dt.timezone.utc).isoformat()
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()


def call(args, data=None, limit=4*1024*1024, timeout=90):
    return backup.command(args, data=data, limit=limit, timeout=timeout)


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def root_metadata(query):
    # Root is ONLY a metadata observer, never the EXPLAIN/business identity.
    assert query.startswith('SELECT ') and ' FROM performance_schema.' in query and query.count(';') <= 1
    shell = 'export MYSQL_PWD=$(cat /run/secrets/root-password); exec mysql -uroot --binary-mode --local-infile=0 --batch --raw --skip-column-names forgeoj'
    value = call(compose + ['exec', '-T', 'mysql', 'sh', '-c', shell], query.encode())
    return [json.loads(row) for row in value.decode().splitlines() if row.strip()]


def digests():
    return root_metadata("SELECT JSON_OBJECT('digest',DIGEST,'normalized',DIGEST_TEXT,'count',COUNT_STAR,'totalPs',SUM_TIMER_WAIT,'lockPs',SUM_LOCK_TIME,'rowsExamined',SUM_ROWS_EXAMINED,'rowsSent',SUM_ROWS_SENT,'noIndex',SUM_NO_INDEX_USED,'noGoodIndex',SUM_NO_GOOD_INDEX_USED,'errors',SUM_ERRORS) FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='forgeoj' AND DIGEST IS NOT NULL AND DIGEST_TEXT NOT LIKE '%performance_schema%' ORDER BY DIGEST;")


def cpu_stat():
    return {service: dict((key, int(value)) for key, value in [line.split() for line in call(compose + ['exec', '-T', service, 'cat', '/sys/fs/cgroup/cpu.stat']).decode().splitlines()]) for service in ['api', 'mysql']}


def metrics():
    shell = 'token=$(cat /run/secrets/forgeoj.monitoring.token); printf \'header = "Authorization: Bearer %s"\\n\' "$token" | curl --config - --fail --silent --max-time 3 http://127.0.0.1:9091/metrics'
    return call(compose + ['exec', '-T', 'api', 'sh', '-c', shell]).decode()


def unlink_fixture_keys(keys):
    assert len(keys) == 300 and all(re.fullmatch('forgeoj:v1:public:[0-9]+:[a-f0-9]{64}', key) for key in keys)
    shell = 'export REDISCLI_AUTH=$(cat /run/secrets/redis-password); exec redis-cli --raw UNLINK ' + ' '.join(keys)
    return int(call(compose + ['exec', '-T', 'redis', 'sh', '-c', shell]))


baseline = json.loads((repo / 'docs/archive/evidence/m5-performance/20261028/verification.json').read_text(encoding='utf-8'))
for item in baseline['verification']['toolHashes']:
    assert sha(repo / item['path']) == item['sha256']
infos = json.loads(call([docker, 'inspect'] + [state['project']+'-'+s+'-1' for s in ['api','worker','mysql','redis','web']]))
assert all(info['Config']['Labels']['com.docker.compose.project'] == state['project'] and info['State']['Health']['Status'] == 'healthy' for info in infos)
assert infos[0]['Config']['User'] == '10001:10001' and infos[0]['HostConfig']['NanoCpus'] == 2000000000
assert infos[2]['HostConfig']['NanoCpus'] == 2000000000
environment = {'capturedAt': now(), 'engine': json.loads(call([docker,'info','--format','{{json .}}']))['KernelVersion'],
      'images':state['images'],'resources':[{'service':info['Config']['Labels']['com.docker.compose.service'],'nanoCpus':info['HostConfig']['NanoCpus'],'memoryBytes':info['HostConfig']['Memory'],'pidsLimit':info['HostConfig']['PidsLimit']} for info in infos],
      'baselineArchiveSha256':baseline['archive']['sha256'],'probeEffect':'JFR and metadata polling enabled; not an optimization comparison'}
if resume:
    assert json.loads((out / 'environment.json').read_text())['images'] == state['images']
else:
    write(out / 'environment.json', environment)
sql, data = fixture()
if resume:
    before = json.loads((out / 'facts-before.json').read_text())
    assert instance.snapshot() == before and instance.sql('SELECT COUNT(*) FROM submission;').strip() == '0'
    assert json.loads((out / 'dataset.json').read_text()) == data
else:
    assert instance.sql('SELECT COUNT(*) FROM user_account;').strip() == '0' and instance.sql('SELECT COUNT(*) FROM submission;').strip() == '0'
    instance.sql((repo / 'forgeoj-api/src/main/resources/db/devdata/R__seed_m0_development_data.sql').read_text(encoding='utf-8-sig'))
    instance.sql(sql)
assert data['sha256'] == baseline['verification']['datasetSha256']
assert instance.sql('SELECT COUNT(*) FROM problem;').strip() == '301'
assert instance.sql('SELECT COUNT(*) FROM problem_test_case;').strip() == '903'
if not resume:
    write(out / 'dataset.json', data)
    before = instance.snapshot(); write(out / 'facts-before.json', before)

instrumentation = root_metadata("SELECT JSON_OBJECT('name',NAME,'enabled',ENABLED) FROM performance_schema.setup_consumers WHERE NAME='statements_digest';")
assert instrumentation == [{'name':'statements_digest','enabled':'YES'}]
if not resume: write(out / 'instrumentation.json', instrumentation)
plans = out / 'sql-plans'
if not resume: plans.mkdir()
for name, query in ([] if resume else queries().items()):
    assert query.startswith('SELECT ') and ';' not in query
    (plans / (name + '.sql')).write_text(query + ';\n', encoding='utf-8')
    estimated = instance.sql('EXPLAIN FORMAT=JSON ' + query + ';', role='api')
    write(plans / (name + '.json'), json.loads(estimated))
    actual = instance.sql('EXPLAIN ANALYZE ' + query + ';', role='api')
    (plans / (name + '.analyze.txt')).write_text(actual, encoding='utf-8')
for table in ([] if resume else ['problem','problem_tag','problem_judge_version','cache_epoch']):
    (plans / (table + '.indexes.tsv')).write_text(instance.sql('SHOW INDEX FROM ' + table + ';', role='api'), encoding='utf-8')
print('Actual API-role SELECT plans and existing Performance Schema digest instrumentation captured.', flush=True)

frozen = out / 'frozen-client'
public = out / 'public'
if not resume:
    frozen.mkdir(); public.mkdir()
    shutil.copyfile(repo / 'tools/performance/load.mjs', frozen / 'load.mjs')
    shutil.copyfile(run / 'private/tls/fullchain.pem', public / 'ca.pem')
    shutil.copyfile(out / 'dataset.json', public / 'dataset.json')
assert sha(frozen / 'load.mjs') == sha(repo / 'tools/performance/load.mjs')
jfc = '/tmp/forgeoj-diagnostic-' + run.name[-8:] + '.jfc'
if resume:
    assert call(compose+['exec','-T','api','sha256sum',jfc]).decode().split()[0] == sha(repo/'tools/performance/diagnostic.jfc')
else:
    call(compose + ['exec','-T','api','sh','-c','set -C; cat > ' + jfc], (repo / 'tools/performance/diagnostic.jfc').read_bytes())
epoch = instance.sql("SELECT revision FROM cache_epoch WHERE namespace='public';").strip()
keys = ['forgeoj:v1:public:' + epoch + ':' + hashlib.sha256(('problem:'+p['slug']).encode()).hexdigest() for p in data['problems']]
registry=json.loads((out/'load-containers.json').read_text()) if resume else []
results=[]


def export_recording(directory, remote):
    jfr_summary=(directory/'jfr-summary.txt').read_text() if (directory/'jfr-summary.txt').exists() else call(compose+['exec','-T','api','jfr','summary',remote]).decode()
    if not (directory/'jfr-summary.txt').exists(): (directory/'jfr-summary.txt').write_text(jfr_summary,encoding='utf-8')
    counts={match[0]:int(match[1]) for match in re.findall(r'^\s*(\S+)\s+(\d+)\s+\d+\s*$',jfr_summary,flags=re.MULTILINE)}
    assert all(counts.get(event,0)==0 for event in FORBIDDEN)
    positive={event for event,count in counts.items() if count}
    assert positive<=EVENTS|{'jdk.Checkpoint','jdk.Metadata'},'Unexpected JFR events; retain locally, do not export'
    assert counts.get('jdk.ExecutionSample',0)>0
    if not (directory/'api.jfr').exists():
        # The daemon archive endpoint cannot see this runtime's tmpfs file; read as its owner, in binary pipes.
        payload=call(compose+['exec','-T','api','cat',remote],limit=16*1024*1024)
        remote_digest=call(compose+['exec','-T','api','sha256sum',remote]).decode().split()[0]
        assert hashlib.sha256(payload).hexdigest()==remote_digest
        (directory/'api.jfr').write_bytes(payload)
    assert (directory/'api.jfr').stat().st_size<=16*1024*1024
    assert sha(directory/'api.jfr') == call(compose+['exec','-T','api','sha256sum',remote]).decode().split()[0]
    if not (directory/'events-compact.json').exists():
        rendered=call(compose+['exec','-T','api','jfr','print','--json','--stack-depth','8','--events',','.join(sorted(EVENTS)),remote],limit=96*1024*1024,timeout=90)
        parsed=json.loads(rendered); assert all(event['type'] in EVENTS for event in parsed['recording']['events'])
        (directory/'events-compact.json').write_bytes(rendered)
    if not (directory/'stacks.txt').exists():
        text=call(compose+['exec','-T','api','jfr','print','--stack-depth','32','--events','jdk.ExecutionSample,jdk.NativeMethodSample',remote],limit=32*1024*1024,timeout=90)
        (directory/'stacks.txt').write_bytes(text)
    write(directory/'event-safety.json',{'allowedPositiveTypes':sorted(positive),'jsonStackDepth':8,'textStackDepth':32,'binaryDigest':sha(directory/'api.jfr'),'forbiddenEventCounts':{event:counts.get(event,0) for event in sorted(FORBIDDEN)}})
    return positive


def finish_case(directory, identifier, summary):
    positive=export_recording(directory,'/tmp/forgeoj-'+directory.name+'.jfr')
    entry=[row for row in registry if row['id']==identifier]; assert len(entry)==1
    if not entry[0]['removed']:
        actual=json.loads(call([docker,'inspect',identifier]))[0]
        assert actual['State']['Status']=='exited' and actual['State']['ExitCode']==0 and actual['Config']['Labels'].get('com.forgeoj.perf.project')==state['project']
        call([docker,'rm',identifier]);entry[0]['removed']=True;write(out/'load-containers.json',registry)
    results.append({'case':directory.name,'requests':summary['measurementCount'],'errors':summary['errors'],'positiveJfrTypes':sorted(positive)})
    print(json.dumps(results[-1]),flush=True)


def case(mode, concurrency):
    name=mode+'-c'+str(concurrency); directory=out/name
    if resume and directory.exists():
        for file in ['summary.json','requests.ndjson','sql-before.json','sql-after.json','cgroup-before.json','cgroup-after.json','jfr-stop.txt','jfr-summary.txt']:
            assert (directory/file).exists(), 'Incomplete capture is not automatically reconstructed'
        summary=json.loads((directory/'summary.json').read_text()); assert summary['status']=='PASS'
        entry=[row for row in registry if row['name']==state['project']+'-diag-'+name]; assert len(entry)==1
        finish_case(directory,entry[0]['id'],summary);return
    directory.mkdir(); os.chmod(directory,0o777)
    write(directory/'config.json',{'mode':mode,'concurrency':concurrency,'round':0})
    if mode=='detail':
        write(directory/'cache-preparation.json',{'removedOnlyKnownSyntheticDetailKeys':unlink_fixture_keys(keys)})
    write(directory/'sql-before.json',digests())
    (directory/'before-api.prom').write_text(metrics(),encoding='utf-8')
    write(directory/'cgroup-before.json',{'at':now(),'stats':cpu_stat()})
    recording='forgeoj-'+name; remote='/tmp/forgeoj-'+name+'.jfr'
    started=call(compose+['exec','-T','api','jcmd','1','JFR.start','name='+recording,'settings='+jfc,'duration=60s','maxsize=16m','filename='+remote])
    (directory/'jfr-start.txt').write_bytes(started)
    assert b'Started recording' in started
    container_name=state['project']+'-diag-'+name
    identifier=call([docker,'create','--name',container_name,'--label','com.forgeoj.perf.project='+state['project'],
        '--network',state['project']+'_edge','--user','65534:65534','--read-only','--cap-drop','ALL',
        '--security-opt','no-new-privileges:true','--cpus','1','--memory','512m','--pids-limit','128',
        '--mount','type=bind,src='+str(frozen)+',dst=/scripts,readonly',
        '--mount','type=bind,src='+str(public)+',dst=/public,readonly',
        '--mount','type=bind,src='+str(directory)+',dst=/output',NODE,'node','/scripts/load.mjs','/output/config.json']).decode().strip()
    assert re.fullmatch('[a-f0-9]{64}',identifier)
    registry.append({'name':container_name,'id':identifier,'removed':False});write(out/'load-containers.json',registry)
    stop=threading.Event()
    def sampler():
        with (directory/'samples.ndjson').open('x',encoding='utf-8') as stream:
            while not stop.is_set():
                try:
                    stats=[json.loads(line) for line in call([docker,'stats','--no-stream','--format','{{json .}}',infos[0]['Id'],infos[2]['Id'],identifier],timeout=15).decode().splitlines() if line.strip()]
                    row={'at':now(),'cpuStat':cpu_stat(),'containers':stats}
                except Exception:
                    row={'at':now(),'error':'Owned diagnostic sample unavailable'}
                stream.write(json.dumps(row)+'\n');stream.flush();stop.wait(3)
    thread=threading.Thread(target=sampler,daemon=True);thread.start()
    successful=False
    try:
        process=subprocess.run([docker,'start','-a',identifier],capture_output=True,timeout=90)
        (directory/'client.log').write_bytes(process.stdout+process.stderr)
        actual=json.loads(call([docker,'inspect',identifier]))[0]
        assert process.returncode==0 and actual['State']['ExitCode']==0 and actual['State']['Status']=='exited'
        summary=json.loads((directory/'summary.json').read_text());assert summary['status']=='PASS'
        successful=True
    finally:
        stop.set();thread.join(timeout=20)
        write(directory/'cgroup-after.json',{'at':now(),'stats':cpu_stat()})
        stopped=call(compose+['exec','-T','api','jcmd','1','JFR.stop','name='+recording,'filename='+remote])
        (directory/'jfr-stop.txt').write_bytes(stopped)
        if not successful:
            actual=json.loads(call([docker,'inspect',identifier]))[0]
            assert actual['Config']['Labels'].get('com.forgeoj.perf.project')==state['project']
            if actual['State']['Running']:call([docker,'stop','--time','10',identifier])
    write(directory/'sql-after.json',digests())
    (directory/'after-api.prom').write_text(metrics(),encoding='utf-8')
    finish_case(directory,identifier,summary)


for concurrency in [4,8]:
    for mode in ['list','detail']:case(mode,concurrency)
assert instance.snapshot()==before
assert instance.sql('SELECT COUNT(*) FROM submission;').strip()=='0'
all_inputs=json.loads((repo/'docs/archive/evidence/m4-monitoring/linux-source-inputs.json').read_text())['inputs']
assert len(all_inputs)==428
for item in all_inputs:assert sha(repo/item['path'])==item['sha256']
paths=['tools/performance/'+name for name in ['diagnose-hotspots.py','diagnostic_queries.py','diagnostic.jfc','dataset.py','load.mjs']]
write(out/'verification.json',{'status':'PASS','scope':'read-only diagnostic observations, not optimization results','project':state['project'],
    'cases':results,'datasetSha256':data['sha256'],'businessFactsAndGrantsUnchanged':True,'submissionsCreated':0,'compiledInputsMatch':428,
    'fullM5':'IN_PROGRESS','fullPerformancePlan':'IN_PROGRESS','release':False,'resumeReady':False,
    'toolHashes':[{'path':p,'sha256':sha(repo/p)} for p in paths],
    'firstCaptureOrchestratorSha256':sha(out/'failed-tool-inputs/diagnose-hotspots.py') if resume else None})
print('Diagnostic capture PASS; summarize and precisely clean this owned project.',flush=True)
