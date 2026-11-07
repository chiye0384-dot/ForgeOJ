#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Run a bounded baseline only on an explicitly owned, empty disposable deployment."""
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
import time

sys.dont_write_bytecode = True
from dataset import fixture

repo = pathlib.Path(__file__).resolve().parents[2]
run = (repo / sys.argv[1]).resolve()
docker = sys.argv[2]
assert run.is_relative_to(repo / 'target') and re.fullmatch(r'forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}', run.name)
state = json.loads((run / 'validation-state.json').read_text(encoding='utf-8-sig'))
assert re.fullmatch('forgeoj-m5check-[a-f0-9]{12}', state['project']) and state['status'] == 'RUNTIME_SMOKE_PASS'
assert pathlib.Path(state['package']) == run / 'package'
perf = run / 'performance'
if '--resume-read-only' in sys.argv[3:]:
    original_perf = perf
    perf = original_perf / ('suite-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S'))
    perf.mkdir()
    shutil.copyfile(original_perf / 'resource-plan.json', perf / 'resource-plan.json')
assert (perf / 'resource-plan.json').exists() and not (perf / 'dataset.json').exists()
spec = importlib.util.spec_from_file_location('performance_backup', repo / 'deploy/production/scripts/backup.py')
backup = importlib.util.module_from_spec(spec); spec.loader.exec_module(backup)
instance = backup.Instance(state['package'], state['instance'], docker)
compose = instance.compose
NODE = 'node:24.14.1-bookworm-slim@sha256:b506e7321f176aae77317f99d67a24b272c1f09f1d10f1761f2773447d8da26c'
SERVICES = ['api', 'worker', 'mysql', 'rabbitmq', 'redis', 'search', 'web', 'prometheus', 'grafana']
now = lambda: dt.datetime.now(dt.timezone.utc).isoformat()
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()


def call(args, data=None, timeout=60):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('Owned baseline command failed; private diagnostics withheld')
    return result.stdout


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def metric(service):
    port = '9091' if service == 'api' else '9092'
    shell = 'token=$(cat /run/secrets/forgeoj.monitoring.token); printf \'header = "Authorization: Bearer %s"\\n\' "$token" | curl --config - --fail --silent --max-time 3 http://127.0.0.1:' + port + '/metrics'
    return call(compose + ['exec', '-T', service, 'sh', '-c', shell]).decode()


def telemetry(directory, label):
    for service in ['api', 'worker']:
        (directory / (label + '-' + service + '.prom')).write_text(metric(service), encoding='utf-8')
    rows = instance.sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Queries','Threads_connected','Threads_running','Slow_queries','Connections','Aborted_connects','Innodb_buffer_pool_read_requests','Innodb_buffer_pool_reads');")
    write(directory / (label + '-mysql.json'), {'at': now(), 'counters': dict(row.split('\t') for row in rows.splitlines())})


def redis_command(command, keys):
    assert command in ['EXISTS', 'UNLINK'] and all(re.fullmatch('forgeoj:v1:public:[0-9]+:[a-f0-9]{64}', key) for key in keys)
    shell = 'export REDISCLI_AUTH=$(cat /run/secrets/redis-password); exec redis-cli --raw ' + command + ' ' + ' '.join(keys)
    return int(call(compose + ['exec', '-T', 'redis', 'sh', '-c', shell]).strip())


limits = json.loads((perf / 'resource-plan.json').read_text())
infos = json.loads(call([docker, 'inspect'] + [state['project'] + '-' + service + '-1' for service in SERVICES], timeout=30))
containers = []
for service, info in zip(SERVICES, infos):
    assert info['Config']['Labels']['com.docker.compose.project'] == state['project']
    assert info['State'].get('Health', {}).get('Status') == 'healthy'
    h = info['HostConfig']
    assert h['NanoCpus'] == int(limits['cpus'][service] * 1e9)
    containers.append({'service': service, 'id': info['Id'], 'image': info['Image'], 'nanoCpus': h['NanoCpus'],
                       'memoryBytes': h['Memory'], 'pidsLimit': h['PidsLimit'], 'user': info['Config']['User']})
engine = json.loads(call([docker, 'info', '--format', '{{json .}}']))
environment = {'capturedAt': now(), 'gitHead': call(['git', '-C', str(repo), 'rev-parse', 'HEAD']).decode().strip(),
               'workingTree': 'uncommitted deployment/performance tools; compiled inputs hash-checked separately',
               'release': None, 'engine': {key: engine.get(key) for key in ['ServerVersion', 'OperatingSystem', 'OSType', 'Architecture', 'KernelVersion', 'NCPU', 'MemTotal', 'Driver', 'CgroupVersion']},
               'composeVersion': call([docker, 'compose', 'version', '--short']).decode().strip(),
               'nodeImage': NODE, 'nodeImageId': json.loads(call([docker, 'image', 'inspect', NODE]))[0]['Id'],
               'jdkVersion': subprocess.run(compose + ['exec', '-T', 'api', 'java', '-version'], capture_output=True, timeout=15).stderr.decode(),
               'containers': containers, 'apiInstances': 1, 'workerInstances': 1,
               'judgeModel': 'sequential formal submissions; one queue consumer, prefetch=1; not aggregate concurrency across job kinds',
               'network': 'same owned Linux edge bridge -> Nginx HTTPS:443 -> API; verified .test certificate; not public RTT',
               'hostShared': True, 'datasetScope': 'public read fixture and sequential judge observations only'}
write(perf / 'environment.json', environment)
seed = repo / 'forgeoj-api/src/main/resources/db/devdata/R__seed_m0_development_data.sql'
sql, blueprint = fixture()
if '--resume-read-only' in sys.argv[3:]:
    assert json.loads((original_perf / 'dataset.json').read_text()) == blueprint
    assert instance.sql('SELECT COUNT(*) FROM user_account;').strip() == '1'
    assert instance.sql('SELECT COUNT(*) FROM submission;').strip() == '0'
    actual_rows = instance.sql('SELECT slug,title,SHA2(statement_text,256),difficulty FROM problem WHERE id BETWEEN 1001 AND 1300 ORDER BY id;').splitlines()
    assert actual_rows == ['\t'.join([p['slug'], p['title'], p['statementSha256'], p['difficulty']]) for p in blueprint['problems']]
else:
    assert instance.sql('SELECT COUNT(*) FROM user_account;').strip() == '0'
    assert instance.sql('SELECT COUNT(*) FROM submission;').strip() == '0'
    instance.sql(seed.read_text(encoding='utf-8-sig'))
    instance.sql(sql)
assert instance.sql('SELECT COUNT(*) FROM problem;').strip() == '301'
assert instance.sql('SELECT COUNT(*) FROM problem_judge_version;').strip() == '301'
assert instance.sql('SELECT COUNT(*) FROM problem_test_case;').strip() == '903'
assert instance.sql('SELECT COUNT(*) FROM user_account;').strip() == '1'
assert instance.sql('SELECT COUNT(*) FROM submission;').strip() == '0'
write(perf / 'dataset.json', blueprint)
write(perf / 'dataset-provenance.json', {'datasetSha256': blueprint['sha256'], 'generatorSha256': sha(repo / 'tools/performance/dataset.py'),
      'publicFixtureSha256': sha(seed), 'initialCounts': {'users': 1, 'problems': 301, 'versions': 301, 'tests': 903, 'submissions': 0, 'classrooms': 0},
      'judgeTestInputBytes': instance.sql('SELECT input_size_bytes FROM problem_test_case WHERE judge_version_id=1 ORDER BY ordinal;').splitlines()})
public = perf / 'public'; public.mkdir()
frozen = perf / 'frozen-client'; frozen.mkdir()
shutil.copyfile(repo / 'tools/performance/load.mjs', frozen / 'load.mjs')
write(perf / 'frozen-client-input.json', {'path': 'tools/performance/load.mjs', 'sha256': sha(frozen / 'load.mjs')})
shutil.copyfile(run / 'private/tls/fullchain.pem', public / 'ca.pem')
shutil.copyfile(perf / 'dataset.json', public / 'dataset.json')
cases = perf / 'cases'; cases.mkdir()
epoch = instance.sql("SELECT revision FROM cache_epoch WHERE namespace='public';").strip()
keys = ['forgeoj:v1:public:' + epoch + ':' + hashlib.sha256(('problem:' + row['slug']).encode()).hexdigest() for row in blueprint['problems']]
reset = redis_command('UNLINK', keys) if '--resume-read-only' in sys.argv[3:] else 0
assert redis_command('EXISTS', keys) == 0
write(perf / 'cold-cache-proof.json', {'knownSyntheticDetailKeys': 300, 'existingBeforeFirstReads': 0, 'selectivelyRemovedBeforeReplay': reset, 'epoch': epoch, 'flushUsed': False})
read_before = instance.snapshot()
write(perf / 'readonly-before.json', read_before)
registry = []
results = []


def execute(name, mode, concurrency, round_number):
    directory = cases / name; directory.mkdir(); os.chmod(directory, 0o777)
    write(directory / 'config.json', {'mode': mode, 'concurrency': concurrency, 'round': round_number})
    removed = redis_command('UNLINK', keys) if mode == 'detail' else 0
    write(directory / 'cache-preparation.json', {'removedKnownSyntheticDetailKeys': removed,
          'scope': 'only these 300 public fixture keys; no auth/rate keys, budgets or global flush'})
    telemetry(directory, 'before')
    container_name = state['project'] + '-perf-' + name
    identifier = call([docker, 'create', '--name', container_name, '--label', 'com.forgeoj.perf.project=' + state['project'],
                       '--network', state['project'] + '_edge', '--user', '65534:65534', '--read-only', '--cap-drop', 'ALL',
                       '--security-opt', 'no-new-privileges:true', '--cpus', '1', '--memory', '512m', '--pids-limit', '128',
                       '--mount', 'type=bind,src=' + str(frozen) + ',dst=/scripts,readonly',
                       '--mount', 'type=bind,src=' + str(public) + ',dst=/public,readonly',
                       '--mount', 'type=bind,src=' + str(directory) + ',dst=/output', NODE,
                       'node', '/scripts/load.mjs', '/output/config.json']).decode().strip()
    assert re.fullmatch('[a-f0-9]{64}', identifier)
    registry.append({'name': container_name, 'id': identifier, 'case': name, 'removed': False})
    write(perf / 'load-containers.json', registry)
    info = json.loads(call([docker, 'inspect', identifier]))[0]
    assert info['Config']['User'] == '65534:65534' and info['HostConfig']['ReadonlyRootfs']
    assert info['HostConfig']['NanoCpus'] == 1000000000 and info['HostConfig']['Memory'] == 512*1024*1024
    assert all(m['Destination'] != '/var/run/docker.sock' for m in info['Mounts'])
    write(directory / 'generator.json', {'id': identifier, 'image': info['Image'], 'nanoCpus': info['HostConfig']['NanoCpus'],
          'memoryBytes': info['HostConfig']['Memory'], 'user': info['Config']['User'], 'socketMounted': False})
    stopped = threading.Event()
    def sample():
        with (directory / 'resources.ndjson').open('x', encoding='utf-8') as stream:
            while not stopped.is_set():
                try:
                    raw = call([docker, 'stats', '--no-stream', '--format', '{{json .}}'] + [item['id'] for item in containers] + [identifier], timeout=15)
                    value = {'at': now(), 'containers': [json.loads(line) for line in raw.decode().splitlines() if line.strip()]}
                except Exception:
                    value = {'at': now(), 'error': 'owned resource sample failed'}
                stream.write(json.dumps(value) + '\n'); stream.flush()
                stopped.wait(3)
    thread = threading.Thread(target=sample, daemon=True); thread.start()
    try:
        result = subprocess.run([docker, 'start', '-a', identifier], capture_output=True, timeout=240 if mode == 'judge' else 90)
        (directory / 'client.log').write_bytes(result.stdout + result.stderr)
        actual = json.loads(call([docker, 'inspect', identifier]))[0]
        assert actual['State']['Status'] == 'exited' and actual['State']['ExitCode'] == 0 and result.returncode == 0
        summary = json.loads((directory / 'summary.json').read_text())
        assert summary['status'] == 'PASS'
    except Exception:
        actual = json.loads(call([docker, 'inspect', identifier]))[0]
        assert actual['Config']['Labels'].get('com.forgeoj.perf.project') == state['project']
        if actual['State']['Running']:
            call([docker, 'stop', '--time', '10', identifier])
        raise
    finally:
        stopped.set(); thread.join(timeout=20)
    telemetry(directory, 'after')
    call([docker, 'rm', identifier]); registry[-1]['removed'] = True
    write(perf / 'load-containers.json', registry)
    results.append({'case': name, 'summary': summary})
    write(perf / 'progress.json', {'status': 'IN_PROGRESS', 'completedCases': [r['case'] for r in results]})
    print(json.dumps({'case': name, 'status': summary['status'], 'requests': summary.get('measurementCount'), 'rps': summary.get('successfulRps')}), flush=True)
    return summary


execute('cold-unique-300', 'cold', 1, 0)
assert redis_command('EXISTS', keys) == 300
for round_number in [1, 2]:
    for concurrency in [1, 4, 8]:
        for mode in ['list', 'detail']:
            execute('r' + str(round_number) + '-' + mode + '-c' + str(concurrency), mode, concurrency, round_number)
assert instance.snapshot() == read_before, 'Read-only load changed MySQL business facts'
judge = execute('judge-sequential-11', 'judge', 1, 0)
jobs = judge['jobs']; assert len(jobs) == 11 and len({row['submissionId'] for row in jobs}) == 11
for job in jobs:
    assert re.fullmatch('[a-f0-9-]{36}', job['submissionId']) and re.fullmatch('[a-f0-9]{64}', job['sourceSha256'])
    found = instance.sql("SELECT processing_status,verdict,SHA2(source_code,256) FROM submission WHERE id='" + job['submissionId'] + "';").strip()
    assert found == 'FINISHED\t' + job['expected'] + '\t' + job['sourceSha256']
facts = instance.sql("SELECT (SELECT COUNT(*) FROM submission),(SELECT COUNT(*) FROM judge_task),(SELECT COUNT(*) FROM judge_task_attempt),(SELECT COUNT(*) FROM outbox_event WHERE event_type='JUDGE_TASK_QUEUED'),(SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL),(SELECT COUNT(*) FROM judge_task WHERE task_status<>'FINISHED');").strip().split('\t')
assert facts == ['11', '11', '11', '11', '0', '0']
server_times = instance.sql("SELECT s.id,TIMESTAMPDIFF(MICROSECOND,s.created_at,t.started_at)/1000,TIMESTAMPDIFF(MICROSECOND,t.started_at,t.finished_at)/1000,TIMESTAMPDIFF(MICROSECOND,s.created_at,s.finished_at)/1000 FROM submission s JOIN judge_task t ON t.submission_id=s.id ORDER BY s.created_at;")
write(perf / 'server-timings.json', [{'submissionId':parts[0], 'queueMs':float(parts[1]), 'processingEnvelopeMs':float(parts[2]), 'acceptedToFinishedMs':float(parts[3])} for parts in [line.split('\t') for line in server_times.splitlines()]])
queues = call(compose + ['exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_queues', '-p', '/forgeoj', 'name', 'messages_ready', 'messages_unacknowledged', '--no-table-headers']).decode()
parsed = [line.split('\t') for line in queues.splitlines()]
assert len(parsed) == 9 and all(row[1:] == ['0', '0'] for row in parsed)
managed = call([docker, 'ps', '-aq', '--filter', 'label=com.forgeoj.managed=true']).strip()
assert not managed
tool_paths = ['tools/performance/' + name for name in ['prepare-baseline.py', 'dataset.py', 'load.mjs', 'run-baseline.py']]
verification = {'status': 'PASS', 'scope': 'local fixed-image public read baseline plus small sequential judge observations',
                'fullPerformancePlan': 'IN_PROGRESS', 'fullM5': 'IN_PROGRESS', 'release': False, 'resumeReady': False,
                'project': state['project'], 'datasetSha256': blueprint['sha256'], 'readOnlyFactsUnchanged': True,
                'cases': len(results), 'requestErrors': sum(r['summary'].get('errors', 0) for r in results),
                'judgeUniqueSubmissions': 11, 'judgeTasks': 11, 'judgeAttempts': 11, 'judgeOutboxQueued': 11,
                'silentLoss': 0, 'duplicateBusinessEffects': 0, 'pendingOutbox': 0, 'emptyQueues': 9, 'managedSandboxes': 0,
                'toolHashes': [{'path': path, 'sha256': sha(repo / path)} for path in tool_paths]}
write(perf / 'verification.json', verification)
print(json.dumps(verification))
