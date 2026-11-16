#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Fresh owned HTTPS stack only; bounded 5 warmup + 20 sequential + 3x10 backlog AC."""
import datetime as dt
import hashlib
import importlib.util
import json
import pathlib
import platform
import re
import shutil
import subprocess
import sys
import threading
import time

sys.dont_write_bytecode = True
repo = pathlib.Path(__file__).resolve().parents[2]
run = (repo / sys.argv[1]).resolve()
docker = sys.argv[2]
assert run.is_relative_to(repo / 'target') and re.fullmatch(r'forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}', run.name)
state = json.loads((run / 'validation-state.json').read_text(encoding='utf-8-sig'))
project = state['project']
assert re.fullmatch(r'forgeoj-m5check-[a-f0-9]{12}', project) and state['status'] == 'RUNTIME_SMOKE_PASS'
assert pathlib.Path(state['package']) == run / 'package'
out = run / 'judge-performance'
assert not out.exists(), 'Existing original requests are not automatically replayed'
out.mkdir()
frozen = out / 'frozen-tools'
frozen.mkdir()
tool_inputs = []
for tool in ['judge-load.mjs', 'judge-lifecycle-docker.sh', 'Dockerfile.judge-probe', 'run-judge-baseline.py', 'summarize-judge-baseline.py']:
    data = (repo / 'tools/performance' / tool).read_bytes()
    (frozen / tool).write_bytes(data)
    tool_inputs.append({'path': tool, 'sha256': hashlib.sha256(data).hexdigest()})
(out / 'tool-inputs.json').write_text(json.dumps(tool_inputs, indent=2)+'\n', encoding='utf-8')

def write(name, value):
    (out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def call(args, timeout=60):
    result = subprocess.run(args, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Owned benchmark command failed; inspect retained state without printing secrets')
    return result.stdout

spec = importlib.util.spec_from_file_location('judge_backup', repo / 'deploy/production/scripts/backup.py')
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)
instance = backup.Instance(state['package'], state['instance'], docker)
compose = instance.compose
assert instance.sql('SELECT COUNT(*) FROM user_account;').strip() == '0'
assert instance.sql('SELECT COUNT(*) FROM submission;').strip() == '0'
seed = repo / 'forgeoj-api/src/main/resources/db/devdata/R__seed_m0_development_data.sql'
instance.sql(seed.read_text(encoding='utf-8-sig'))
assert instance.sql('SELECT COUNT(*) FROM user_account;').strip() == '1'
assert instance.sql('SELECT COUNT(*) FROM problem_test_case;').strip() == '3'
write('dataset.json', {'publicFixtureSha256': hashlib.sha256(seed.read_bytes()).hexdigest(), 'users': 1, 'problems': 1, 'testCases': 3, 'user': 'disposable fixture learner, not the existing demo account', 'data': instance.sql('SELECT java_image_digest,test_dataset_sha256,time_limit_ms,memory_limit_mb,output_limit_bytes FROM problem_judge_version WHERE id=1;').strip()})

original = state['images'][1]
assert original['component'] == 'worker'
assert call([docker, 'image', 'inspect', '--format', '{{.Id}}', original['tag']]).decode().strip() == original['id']
probe = project + '-judge-probe'
write('probe-image.json', {'tag': probe, 'base': original, 'state': 'BUILDING'})
context = out / 'probe-context'
context.mkdir()
for name in ['Dockerfile.judge-probe', 'judge-lifecycle-docker.sh']:
    data = (repo / 'tools/performance' / name).read_bytes().replace(b'\r\n', b'\n')
    (context / name).write_bytes(data)
build = subprocess.run([docker, 'build', '--platform', 'linux/amd64', '-f', str(context / 'Dockerfile.judge-probe'), '--build-arg', 'FORGEOJ_PERF_WORKER_BASE='+original['tag'], '-t', probe, str(context)], capture_output=True, timeout=120)
(out / 'probe-build.log').write_bytes(build.stdout+build.stderr)
assert build.returncode == 0
probe_id = call([docker, 'image', 'inspect', '--format', '{{.Id}}', probe]).decode().strip()
write('probe-image.json', {'tag': probe, 'id': probe_id, 'base': original, 'sameApplicationJar': True, 'scope': 'instrumentation image for this benchmark only'})

commands = out / 'commands'
commands.mkdir()
overlay = out / 'probe-compose.json'
overlay.write_text(json.dumps({'services': {'worker': {'image': probe_id, 'environment': {'FORGEOJ_WORKER_DOCKER_EXECUTABLE': '/app/judge-lifecycle-docker'}, 'volumes': [{'type': 'bind', 'source': str(commands), 'target': '/measure'}]}}}, indent=2)+'\n')
updated = compose + ['-f', str(overlay)]
call(updated + ['up', '-d', '--no-deps', '--force-recreate', '--no-build', '--pull', 'never', '--wait', '--wait-timeout', '90', 'worker'], timeout=110)
actual_jar = call([docker, 'exec', project+'-worker-1', 'sha256sum', '/app/worker.jar']).decode().split()[0]
expected_jar = hashlib.sha256((run / 'package/artifacts/worker.jar').read_bytes()).hexdigest()
assert actual_jar == expected_jar
infos = json.loads(call([docker, 'inspect']+[project+'-'+s+'-1' for s in ['api', 'worker', 'mysql']]))
for service in infos:
    assert service['Config']['Labels']['com.docker.compose.project'] == project
    assert service['HostConfig']['NanoCpus'] == 2000000000
engine = json.loads(call([docker, 'info', '--format', '{{json .}}']))
write('environment.json', {'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'hostPlatform': platform.platform(), 'hostProcessor': platform.processor(), 'engine': {key: engine.get(key) for key in ['ServerVersion', 'OperatingSystem', 'OSType', 'Architecture', 'KernelVersion', 'NCPU', 'MemTotal', 'CgroupVersion']}, 'services': [{'name': i['Name'], 'id': i['Id'], 'image': i['Image'], 'nanoCpus': i['HostConfig']['NanoCpus'], 'memoryBytes': i['HostConfig']['Memory'], 'pidsLimit': i['HostConfig']['PidsLimit']} for i in infos], 'workerJarSha256': actual_jar, 'transport': 'same Docker bridge, HTTPS certificate validated, keep-alive', 'instrumentation': 'Bash wrapper times real Docker CLI calls; includes CLI/daemon/clock-sampling overhead, not daemon-internal CPU time'})
public = out / 'public'
public.mkdir()
shutil.copyfile(run / 'private/tls/fullchain.pem', public / 'ca.pem')
client = out / 'frozen-client'
client.mkdir()
shutil.copyfile(repo / 'tools/performance/judge-load.mjs', client / 'judge-load.mjs')
networks = json.loads(call([docker, 'inspect', '--format', '{{json .NetworkSettings.Networks}}', project+'-web-1']))
network = next(name for name in networks if name.endswith('_ingress'))
network_info = json.loads(call([docker, 'network', 'inspect', network]))[0]
assert network_info['Labels']['com.docker.compose.project'] == project
name = project+'-judge-load'
write('load-container.json', {'name': name, 'label': project, 'state': 'STARTING'})
stopping = threading.Event()

def sample():
    with (out / 'resources.ndjson').open('a', encoding='utf-8') as stream:
        while not stopping.is_set():
            try:
                rows = call([docker, 'stats', '--no-stream', '--format', '{{json .}}']+[i['Id'] for i in infos], timeout=12).decode().splitlines()
                stream.write(json.dumps({'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'services': [json.loads(row) for row in rows]})+'\n')
                stream.flush()
            except Exception:
                stream.write(json.dumps({'at': dt.datetime.now(dt.timezone.utc).isoformat(), 'samplingFailed': True})+'\n')
                stream.flush()
            stopping.wait(2)

sampler = threading.Thread(target=sample, daemon=True)
sampler.start()
try:
    args = [docker, 'run', '--rm', '--name', name, '--label', 'com.forgeoj.judge-benchmark='+project, '--network', network, '--cpus', '1', '--memory', '256m', '--pids-limit', '128', '--mount', 'type=bind,src='+str(public)+',dst=/public,readonly', '--mount', 'type=bind,src='+str(client)+',dst=/client,readonly', '--mount', 'type=bind,src='+str(out)+',dst=/output', 'node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c', 'node', '/client/judge-load.mjs']
    result = subprocess.run(args, capture_output=True, timeout=500)
    (out / 'client.log').write_bytes(result.stdout+result.stderr)
    assert result.returncode == 0, 'Original job intents retained; do not repeat the benchmark'
finally:
    stopping.set()
    sampler.join(timeout=15)

query = "SELECT JSON_OBJECT('id',s.id,'taskId',t.id,'sourceSha256',s.source_sha256,'queueMs',TIMESTAMPDIFF(MICROSECOND,s.created_at,t.started_at)/1000,'processingMs',TIMESTAMPDIFF(MICROSECOND,t.started_at,t.finished_at)/1000,'endToEndMs',TIMESTAMPDIFF(MICROSECOND,s.created_at,s.finished_at)/1000,'verdict',s.verdict,'taskStatus',t.task_status,'attempts',t.attempt_count) FROM submission s JOIN judge_task t ON t.submission_id=s.id ORDER BY s.created_at,s.id;"
facts = [json.loads(row) for row in instance.sql(query).splitlines()]
write('server-jobs.json', facts)
assert len(facts) == 55 and all(r['verdict']=='AC' and r['taskStatus']=='FINISHED' and r['attempts']==1 for r in facts)
counts = instance.sql("SELECT (SELECT COUNT(*) FROM submission),(SELECT COUNT(*) FROM judge_task),(SELECT COUNT(*) FROM judge_task_attempt),(SELECT COUNT(*) FROM outbox_event WHERE event_type='JUDGE_TASK_QUEUED'),(SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL);").strip().split('\t')
assert counts == ['55','55','55','55','0']
assert not call([docker, 'ps', '-aq', '--filter', 'label=com.forgeoj.managed=true']).strip()
# Freeze this owned instrumentation writer before the offline reducer reads its files.
call(updated + ['stop', '--timeout', '20', 'worker'], timeout=45)
write('persisted-facts.json', {'submissions': 55, 'tasks': 55, 'attempts': 55, 'queuedEvents': 55, 'unpublishedEvents': 0})
write('run-result.json', {'status': 'PASS', 'project': project, 'checkedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'samples': 55, 'warmup': 5, 'measurement': 50, 'productionChanged': False, 'noAutomaticReplay': True})
print('PASS: 55 real AC jobs, 50 measured, one task/attempt/event each; retained raw client/DB/Docker timings.')
