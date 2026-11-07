#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Independently recalculate measurements from request/resource NDJSON; no server access."""
import collections
import datetime as dt
import json
import math
import pathlib
import re
import sys

repo = pathlib.Path(__file__).resolve().parents[2]
run = (repo / sys.argv[1]).resolve()
assert run.is_relative_to(repo / 'target') and run.name.startswith('forgeoj-deployment-')
perf = (repo / sys.argv[2]).resolve() if len(sys.argv) > 2 else run / 'performance'
assert perf.is_relative_to(run / 'performance')
verify = json.loads((perf / 'verification.json').read_text())
assert verify['status'] == 'PASS'
project = verify['project']
read = lambda p: json.loads(p.read_text(encoding='utf-8-sig'))


def nearest(values, percentile):
    return sorted(values)[max(0, math.ceil(len(values) * percentile) - 1)]


def memory(text):
    match = re.fullmatch(r'([0-9.]+)([KMGT]?i?B)', text.strip())
    assert match, text
    return float(match[1]) * {'B': 1, 'kB': 1000, 'KB': 1000, 'MB': 1000**2, 'GB': 1000**3,
                               'KiB': 1024, 'MiB': 1024**2, 'GiB': 1024**3, 'TiB': 1024**4}[match[2]]


def event(path, name):
    expression = r'^forgeoj_api_events_total\{event="' + name + r'"\} ([0-9]+)$'
    match = re.search(expression, path.read_text(), flags=re.MULTILINE)
    assert match
    return int(match[1])


rows = []
for directory in sorted((perf / 'cases').iterdir()):
    if not directory.is_dir():
        continue
    summary = read(directory / 'summary.json')
    assert summary['status'] == 'PASS'
    requests = [json.loads(line) for line in (directory / 'requests.ndjson').read_text().splitlines() if line.strip()]
    if summary['mode'] == 'judge':
        assert sum(r['phase'] == 'judge-accept' for r in requests) == 11
        assert sum(r['phase'] == 'idempotency-probe' for r in requests) == 1
        assert all(row['valid'] and row['error'] is None for row in requests)
        continue
    measured = [row for row in requests if row['phase'] == 'measurement']
    assert len(measured) == summary['measurementCount']
    assert len(requests) - len(measured) == summary['warmupCount']
    assert all(row['latencyMs'] >= 0 and row['offsetMs'] >= 0 and
               row['offsetMs'] + row['latencyMs'] <= summary['elapsedMs'] for row in measured)
    errors = sum(not row['valid'] or bool(row['error']) or row['status'] != 200 for row in measured)
    assert errors == summary['errors'] == 0
    assert dict(collections.Counter(str(row['status']) for row in measured)) == summary['statuses']
    for key, percentile in [('p50Ms', .5), ('p95Ms', .95), ('p99Ms', .99)]:
        assert nearest([row['latencyMs'] for row in measured], percentile) == summary['allLatency'][key]
    assert math.isclose(len(measured) * 1000 / summary['elapsedMs'], summary['attemptedRps'], rel_tol=1e-12)
    samples = [json.loads(line) for line in (directory / 'resources.ndjson').read_text().splitlines() if line.strip()]
    start = dt.datetime.fromisoformat(summary['startedAt'])
    end = dt.datetime.fromisoformat(summary['finishedAt'])
    observed = collections.defaultdict(list)
    sample_errors = 0
    for sample in samples:
        if sample.get('error'):
            sample_errors += 1
            continue
        if not start <= dt.datetime.fromisoformat(sample['at']) <= end:
            continue
        for container in sample['containers']:
            name = container['Name']
            if name.startswith(project + '-perf-'):
                service = 'client'
            else:
                assert name.startswith(project + '-') and name.endswith('-1')
                service = name[len(project) + 1:-2]
            observed[service].append({'cpu': float(container['CPUPerc'].rstrip('%')),
                                      'memory': memory(container['MemUsage'].split('/')[0]), 'pids': int(container['PIDs'])})
    resource = {service: {'samples': len(values), 'sampleMeanCpuPercent': sum(v['cpu'] for v in values) / len(values),
                          'maxSampleCpuPercent': max(v['cpu'] for v in values),
                          'maxSampleMemoryMiB': max(v['memory'] for v in values) / 1024**2,
                          'maxSamplePids': max(v['pids'] for v in values)} for service, values in observed.items()}
    if summary['mode'] != 'cold':
        assert all(resource.get(name, {}).get('samples', 0) >= 2 for name in ['api', 'mysql', 'client']), 'Insufficient resource samples'
    cache = {name: event(directory / 'after-api.prom', name) - event(directory / 'before-api.prom', name) for name in ['cache_hit', 'cache_miss', 'cache_bypass']}
    http_expression = r'^forgeoj_http_requests_total\{route="library",status_class="2xx"\} ([0-9]+)$'
    http_counts = [int(re.search(http_expression, (directory / (when + '-api.prom')).read_text(), flags=re.MULTILINE)[1]) for when in ['before', 'after']]
    assert http_counts[1] - http_counts[0] == len(requests), 'Client/server request counts differ'
    if summary['mode'] == 'cold':
        assert summary['measurementCount'] == 300 and cache['cache_miss'] == 300 and cache['cache_hit'] == 0
    if summary['mode'] == 'detail':
        assert cache['cache_hit'] == summary['measurementCount'] and cache['cache_miss'] == 300
    mysql_before = read(directory / 'before-mysql.json')['counters']
    mysql_after = read(directory / 'after-mysql.json')['counters']
    mysql_delta = {key: int(mysql_after[key]) - int(mysql_before[key]) for key in ['Questions', 'Queries', 'Slow_queries', 'Connections', 'Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads']}
    rows.append({'case': directory.name, **summary, 'serverConfirmedRequestsIncludingWarmup': http_counts[1] - http_counts[0], 'resource': resource, 'resourceSampleErrors': sample_errors,
                 'cacheCountersIncludingWarmup': cache, 'mysqlCountersIncludingWarmupAndProbes': mysql_delta})
assert len(rows) == 13
judge = read(perf / 'cases/judge-sequential-11/summary.json')
server = {row['submissionId']: row for row in read(perf / 'server-timings.json')}
for row in judge['jobs']:
    assert row['submissionId'] in server
    row['server'] = server[row['submissionId']]
    assert all(server[row['submissionId']][key] >= 0 for key in ['queueMs', 'processingEnvelopeMs', 'acceptedToFinishedMs'])
result = {'status': 'PASS', 'independentRawRecalculation': True, 'readCases': rows, 'judge': judge,
          'requestCount': sum(row['measurementCount'] for row in rows),
          'requestErrors': sum(row['errors'] for row in rows),
          'scope': 'two repeats of bounded closed-loop local read stages plus eleven sequential judge observations'}
(perf / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
for row in rows:
    print(json.dumps({'case': row['case'], 'requests': row['measurementCount'], 'rps': round(row['successfulRps'], 2),
                      'p95Ms': round(row['allLatency']['p95Ms'], 3), 'errors': row['errors'],
                      'apiCpuMean': round(row['resource']['api']['sampleMeanCpuPercent'], 2) if 'api' in row['resource'] else None}))
print(json.dumps({'status': 'PASS', 'independentRawRecalculation': True, 'requests': result['requestCount']}))
