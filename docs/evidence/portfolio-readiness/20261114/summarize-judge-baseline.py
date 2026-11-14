#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Independently join original client, database and CLI records; never manufactures verdicts."""
import collections
import datetime as dt
import hashlib
import json
import math
import pathlib
import re
import sys
import zipfile

repo = pathlib.Path(__file__).resolve().parents[2]
run = (repo / sys.argv[1]).resolve()
assert run.is_relative_to(repo / 'target') and re.fullmatch(r'forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}', run.name)
out = run / 'judge-performance'
read = lambda name: json.loads((out / name).read_text(encoding='utf-8-sig'))
lines = lambda name: [json.loads(line) for line in (out / name).read_text(encoding='utf-8').splitlines() if line]
assert read('run-result.json')['status'] == 'PASS'
assert read('client-result.json')['status'] == 'PASS'
jobs = lines('jobs.ndjson')
finished = [r for r in jobs if r['event'] == 'finished']
assert len(finished) == 55
assert len({r['requestId'] for r in finished}) == len({r['submissionId'] for r in finished}) == 55
events = collections.Counter(r['event'] for r in jobs)
assert events == {'intent': 55, 'accepted': 55, 'finished': 55}
by_event = {}
for event in events:
    rows = [r for r in jobs if r['event'] == event]
    by_event[event] = {r['requestId']: r for r in rows}
    assert len(by_event[event]) == 55
assert set(by_event['intent']) == set(by_event['accepted']) == set(by_event['finished'])
for request_id, intent in by_event['intent'].items():
    accepted, terminal = by_event['accepted'][request_id], by_event['finished'][request_id]
    assert (intent['stage'], intent['index']) == (accepted['stage'], accepted['index']) == (terminal['stage'], terminal['index'])
    assert intent['sourceSha256'] == terminal['sourceSha256']
    assert accepted['submissionId'] == terminal['submissionId']
server_rows = read('server-jobs.json')
server = {r['id']: r for r in server_rows}
assert len(server_rows) == len(server) == 55
assert set(server) == {r['submissionId'] for r in finished}
stages = lines('stages.ndjson')
assert [(r['name'], r['count'], r['concurrency']) for r in stages] == [('warmup',5,1),('sequential',20,1),('backlog-1',10,3),('backlog-2',10,3),('backlog-3',10,3)]
for stage in stages:
    wall = (dt.datetime.fromisoformat(stage['finishedAt'])-dt.datetime.fromisoformat(stage['startedAt'])).total_seconds()*1000
    assert abs(wall-stage['elapsedMs']) < 2000, 'Suspension/clock drift invalidates this stage'
trace_files = sorted((out / 'commands').glob('command-*.json'))
assert trace_files, 'Per-invocation command records required'
trace = [json.loads(p.read_text(encoding='utf-8')) for p in trace_files]
created = [r for r in trace if r['phase'] == 'create']
assert len(created) == 55 and len({r['containerId'] for r in created}) == 55
task_container = {}
for row in created:
    match = re.fullmatch(r'forgeoj-([a-f0-9]{32})-([a-f0-9]{32})', row['name'])
    assert match and row['exitCode'] == 0 and re.fullmatch(r'[a-f0-9]{64}', row['containerId'])
    task_container[match[1]] = row['containerId']
assert {r['taskId'].replace('-', '') for r in server_rows} == set(task_container)
by_container = collections.defaultdict(list)
for row in trace:
    assert 0 <= row['durationNs'] < 30_000_000_000
    by_container[row['containerId']].append(row)
joined = []
for job in finished:
    fact = server[job['submissionId']]
    assert fact['sourceSha256'] == job['sourceSha256'] and fact['verdict'] == job['verdict'] == 'AC'
    assert fact['taskStatus'] == 'FINISHED' and fact['attempts'] == 1
    assert fact['endToEndMs'] >= fact['processingMs'] > 0
    identifier = task_container[fact['taskId'].replace('-', '')]
    commands = by_container[identifier]
    for phase, count in [('create',1),('start',1),('compile',1),('run',3),('rm',1)]:
        selected = [r for r in commands if r['phase']==phase]
        assert len(selected) == count and all(r['exitCode']==0 for r in selected), (phase,len(selected))
    joined.append({**job, **fact, 'containerId': identifier, 'cliMs': {phase: sum(r['durationNs']/1e6 for r in commands if r['phase']==phase) for phase in ['create','start','compile','run','rm']}})

def stats(values):
    values = sorted(values)
    assert values
    return {'n': len(values), 'p50Ms': values[math.ceil(len(values)*.5)-1], 'p95Ms': values[math.ceil(len(values)*.95)-1], 'meanMs': sum(values)/len(values)}

measure = [r for r in joined if r['stage'] != 'warmup']
summary = {'status':'VERIFIED', 'checkedAt':dt.datetime.now(dt.timezone.utc).isoformat(), 'measured':len(measure), 'warmup':5, 'actualAc':55, 'stages':[], 'cli':{p:stats([r['cliMs'][p] for r in measure]) for p in ['create','start','compile','run','rm']}, 'method':'nearest-rank; homogeneous A+B program/3 tests; 200ms polling excluded from DB latency; short closed-loop observations, not maximum or cloud capacity'}
for stage in stages[1:]:
    rows = [r for r in joined if r['stage'] == stage['name']]
    assert len(rows) == stage['count']
    summary['stages'].append({**stage, 'completedPerMinute':len(rows)*60000/stage['elapsedMs'], 'dbEndToEnd':stats([r['endToEndMs'] for r in rows]), 'dbQueue':stats([r['queueMs'] for r in rows]), 'dbProcessing':stats([r['processingMs'] for r in rows]), 'clientVisible':stats([r['clientVisibleMs'] for r in rows])})
(out / 'joined-jobs.json').write_text(json.dumps(joined,indent=2)+'\n',encoding='utf-8')
(out / 'summary.json').write_text(json.dumps(summary,indent=2)+'\n',encoding='utf-8')
# Keep original independent files in a compact archive, plus a clearly derived NDJSON view.
command_manifest = [{'path': p.name, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in trace_files]
(out / 'command-records.json').write_text(json.dumps(command_manifest,indent=2)+'\n',encoding='utf-8')
with zipfile.ZipFile(out / 'command-records.zip','x',zipfile.ZIP_DEFLATED) as archive:
    for p in trace_files: archive.write(p,p.name)
with zipfile.ZipFile(out / 'command-records.zip') as archive:
    assert archive.testzip() is None
    for item in command_manifest:
        assert hashlib.sha256(archive.read(item['path'])).hexdigest() == item['sha256']
(out / 'docker-commands.ndjson').write_text(''.join(json.dumps(r)+'\n' for r in sorted(trace,key=lambda r:r['startNs'])),encoding='utf-8')
manifest = [{'path':p.relative_to(out).as_posix(),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in out.rglob('*') if p.is_file() and p.name!='checksums.json']
(out / 'checksums.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print(json.dumps(summary,ensure_ascii=False,indent=2))
