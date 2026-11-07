#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Maintenance-only image ENTRYPOINT rollback with identical filesystem layers.

This deliberately refuses business/schema/file changes. No database restoration,
migration, queue purge, automatic writer stop, image pull or automatic fallback.
"""
import argparse
import datetime as dt
import json
import os
import pathlib
import re
import subprocess
import sys
import uuid

sys.dont_write_bytecode = True  # Set before importing the adjacent standalone tool.
import backup

require = backup.require
APPS = ('api', 'worker', 'web')
INFRA = ('mysql', 'rabbitmq', 'redis', 'search', 'prometheus', 'grafana')
QUEUES = {'forgeoj.judge.submission.v1', 'forgeoj.judge.retry.v1', 'forgeoj.judge.submission.dead.v1',
          'forgeoj.judge.self-test.v1', 'forgeoj.judge.self-test.dead.v1',
          'forgeoj.content.validation.v1', 'forgeoj.content.validation.dead.v1',
          'forgeoj.search.public', 'forgeoj.search.public.dlq'}


def call(args, **kwargs):
    return backup.command(args, **kwargs)


def immutable(value):
    require(re.fullmatch(r'sha256:[a-f0-9]{64}', value), 'Use a local immutable image ID, not a tag')
    return value


def config_key(service):
    return 'FORGEOJ_' + service.upper() + '_IMAGE'


def container(instance, service):
    ids = call(instance.compose + ['ps', '-a', '-q', service], limit=4096).decode().split()
    require(len(ids) == 1, 'Each service must have exactly one existing owned container')
    info = json.loads(call([instance.docker, 'inspect', ids[0]], limit=1024*1024))[0]
    labels = info['Config']['Labels']
    require(labels.get('com.docker.compose.project') == instance.project and
            labels.get('com.docker.compose.service') == service, 'Service ownership mismatch')
    return info


def drained(instance):
    checks = {
        'judge_task': "task_status NOT IN ('FINISHED','CANCELLED','SYSTEM_ERROR') OR lease_token IS NOT NULL",
        'judge_task_attempt': "attempt_status='RUNNING'",
        'content_validation_job': "processing_status NOT IN ('FINISHED','SYSTEM_ERROR') OR lease_token IS NOT NULL",
        'content_validation_attempt': "attempt_status='RUNNING'",
        'self_test_job': "processing_status NOT IN ('FINISHED','CANCELLED','SYSTEM_ERROR') OR lease_token IS NOT NULL",
        'self_test_attempt': "attempt_status='RUNNING'",
        'outbox_event': 'published_at IS NULL',
        'cache_invalidation_outbox': 'delivered_at IS NULL',
        'public_search_outbox': 'published_at IS NULL',
        'public_search_delivery': "status<>'SUCCEEDED' OR lease_token IS NOT NULL",
        'public_search_dead_outbox': 'published_at IS NULL',
        'public_search_dead_recovery': 'published_at IS NULL',
        'public_search_rebuild': "status NOT IN ('SUCCEEDED','FAILED') OR lease_token IS NOT NULL",
    }
    for table, predicate in checks.items():
        require(instance.sql('SELECT COUNT(*) FROM ' + table + ' WHERE ' + predicate + ';').strip() == '0',
                'Pending task, lease, retry, dead letter or delivery requires separate recovery')
    require(instance.sql('SELECT COUNT(*) FROM public_search_control WHERE active_job IS NOT NULL;').strip() == '0',
            'Search maintenance is active')
    raw = call(instance.compose + ['exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_queues',
                                '-p', '/forgeoj', 'name', 'messages_ready', 'messages_unacknowledged',
                                'consumers', '--no-table-headers'], limit=65536).decode()
    queues = {}
    for line in raw.splitlines():
        name, ready, unacked, consumers = line.split('\t')
        queues[name] = [int(ready), int(unacked), int(consumers)]
    require(set(queues) == QUEUES and all(value == [0, 0, 0] for value in queues.values()),
            'All nine known queues, including dead letters, must be empty and have no consumers')
    return queues


def image_pair(instance, targets):
    images = {}
    changed = False
    for service in APPS:
        original = immutable(instance.config[config_key(service)])
        target = immutable(targets[service])
        pair = json.loads(call([instance.docker, 'image', 'inspect', original, target], limit=4*1024*1024))
        require(pair[0]['Id'] == original and pair[1]['Id'] == target, 'Image identity mismatch')
        require(pair[0]['Os'] == pair[1]['Os'] == 'linux' and pair[0]['Architecture'] == pair[1]['Architecture'] == 'amd64',
                'Only the pinned Linux amd64 runtime is supported')
        require(pair[0]['RootFS'] == pair[1]['RootFS'], 'Changed filesystem/business artifacts require a separately verified compatibility matrix')
        before, after = dict(pair[0]['Config']), dict(pair[1]['Config'])
        old_entry, new_entry = before.pop('Entrypoint', None), after.pop('Entrypoint', None)
        require(before == after and isinstance(new_entry, list) and new_entry,
                'Only ENTRYPOINT changes with otherwise identical image configuration are supported')
        changed |= original != target
        images[service] = {'from': original, 'to': target, 'layersSha256': backup.sha(json.dumps(pair[0]['RootFS'], sort_keys=True).encode()),
                           'fromEntrypoint': old_entry, 'toEntrypoint': new_entry}
    require(changed, 'Same-image recreation is not a rollback')
    return images


def preflight(instance, isolated):
    result = subprocess.run([sys.executable, str(instance.package / 'scripts/preflight.py'), str(instance.instance)] +
                            (['--isolated'] if isolated else []), stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, timeout=30)
    require(result.returncode == 0, 'Deployment preflight refused this instance')


def observe(instance, targets, isolated):
    instance.quiet()
    preflight(instance, isolated)
    apps = {service: container(instance, service) for service in APPS}
    for service, info in apps.items():
        require(info['State']['Status'] in ('created', 'exited') and
                info['Image'] == instance.config[config_key(service)], 'Stop each original application explicitly before planning')
    infrastructure = {service: container(instance, service) for service in INFRA}
    require(all(info['State'].get('Health', {}).get('Status') == 'healthy' for info in infrastructure.values()),
            'All existing infrastructure must be healthy')
    images = image_pair(instance, targets)
    queues = drained(instance)
    snapshot = instance.snapshot()
    require(snapshot['migrations'] and len(snapshot['migrations']) == 24 and
            all(row.endswith('\t1') for row in snapshot['migrations']), 'This tool supports only the validated V1-V24 contract')
    return {'project': instance.project, 'serverUuid': instance.server_uuid(), 'images': images, 'snapshot': snapshot,
            'schemaAndDataSha256': backup.sha(instance.dump()),
            'queues': queues, 'applicationContainers': {name: item['Id'] for name, item in apps.items()},
            'infrastructureContainers': {name: item['Id'] for name, item in infrastructure.items()},
            'instanceSha256': backup.sha(instance.instance.read_bytes()),
            'packageManifestSha256': backup.sha((instance.package / 'package-manifest.json').read_bytes())}


def exclusive(path, data):
    path = pathlib.Path(path)
    require(not path.is_symlink(), 'Output cannot be a symlink')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def encode(value):
    return (json.dumps(value, sort_keys=True, indent=2) + '\n').encode()


def prepare(args):
    instance = backup.Instance(args.package, args.instance, args.docker)
    output = pathlib.Path(args.output).absolute()
    require(not output.resolve().is_relative_to(instance.package), 'Plan must be outside the immutable package')
    targets = {service: getattr(args, service) for service in APPS}
    with backup.instance_lock(instance.instance):
        observed = observe(instance, targets, args.isolated)
        plan = {'format': 'forgeoj-entrypoint-rollback-v1', 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                'package': str(instance.package), 'instance': str(instance.instance), 'isolated': args.isolated, 'observed': observed}
        data = encode(plan)
        exclusive(output, data)
    print(json.dumps({'status': 'PLAN_ONLY', 'sha256': backup.sha(data), 'servicesStarted': False, 'scope': 'identical filesystem; ENTRYPOINT only'}))


def apply(args):
    plan_path = pathlib.Path(args.plan).resolve()
    require(plan_path.stat().st_size < 2*1024*1024 and re.fullmatch('[a-f0-9]{64}', args.sha256), 'Invalid plan size or trusted digest')
    data = plan_path.read_bytes()
    require(backup.sha(data) == args.sha256, 'Plan does not match the trusted digest')
    plan = json.loads(data)
    require(plan['format'] == 'forgeoj-entrypoint-rollback-v1', 'Unsupported rollback plan')
    require(not pathlib.Path(plan['instance']).is_symlink(), 'Instance config cannot be a symlink')
    age = (dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(plan['createdAt'])).total_seconds()
    require(0 <= age <= 1800, 'Plan expired; observe and review again')
    instance = backup.Instance(plan['package'], plan['instance'], args.docker)
    targets = {service: plan['observed']['images'][service]['to'] for service in APPS}
    journal = pathlib.Path(str(plan_path) + '.applied.json')
    saved = pathlib.Path(str(plan_path) + '.before.env')
    with backup.instance_lock(instance.instance):
        current = observe(instance, targets, plan['isolated'])
        require(current == plan['observed'], 'Instance, containers, images, schema, data, grants or configuration changed after planning')
        exclusive(journal, encode({'status': 'IN_PROGRESS', 'planSha256': args.sha256}))
        # Private credentials may occur in future env formats. Preserve privately, never print or ship.
        original = instance.instance.read_bytes()
        exclusive(saved, original)
        text = original.decode('utf-8-sig')
        for service in APPS:
            text, count = re.subn(r'^' + config_key(service) + r'=.*$', config_key(service) + '=' + targets[service], text, flags=re.MULTILINE)
            require(count == 1, 'Missing or duplicated image setting')
        temporary = pathlib.Path(str(instance.instance) + '.rollback-' + uuid.uuid4().hex)
        exclusive(temporary, text.encode())
        os.replace(temporary, instance.instance)
        try:
            # No migrator/search setup/dependency recreate or pull. Expose web only after both backends are ready.
            for service in APPS:
                call(instance.compose + ['up', '-d', '--no-deps', '--no-build', '--pull', 'never',
                                         '--wait', '--wait-timeout', '90', service], limit=65536, timeout=120)
                info = container(instance, service)
                require(info['Image'] == targets[service] and info['State'].get('Health', {}).get('Status') == 'healthy',
                        'Selected application did not become healthy')
            require(instance.server_uuid() == current['serverUuid'] and instance.snapshot() == current['snapshot'] and
                    backup.sha(instance.dump()) == current['schemaAndDataSha256'],
                    'Database schema, contents or permissions changed during rollback')
            require({service: container(instance, service)['Id'] for service in INFRA} == current['infrastructureContainers'],
                    'Infrastructure identity changed')
            result = {'status': 'READY_FOR_BUSINESS_CHECK', 'planSha256': args.sha256, 'project': instance.project,
                      'images': targets, 'dataAndGrantsMatched': True, 'infrastructurePreserved': True,
                      'scope': 'same filesystem ENTRYPOINT rollback; business smoke and operator acceptance remain required'}
        except Exception:
            # Only stop the exact selected/source applications after checking ownership again.
            for service in APPS:
                info = container(instance, service)
                require(info['Image'] in {current['images'][service]['from'], targets[service]}, 'Application ownership changed during failure')
            call(instance.compose + ['stop', '--timeout', '30', 'web', 'api', 'worker'], limit=65536, timeout=120)
            result = {'status': 'FAILED_STOPPED', 'planSha256': args.sha256,
                      'note': 'Target config remains selected; private before.env retained; inspect before a fresh plan. No automatic fallback or data restoration.'}
            journal.write_bytes(encode(result))
            raise backup.BackupRefused('Selected images failed; applications stopped and evidence retained') from None
        journal.write_bytes(encode(result))
    print(json.dumps(result))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--docker', default='docker')
    sub = parser.add_subparsers(dest='action', required=True)
    part = sub.add_parser('plan')
    for key in ['package', 'instance', 'api', 'worker', 'web', 'output']:
        part.add_argument('--' + key, required=True)
    part.add_argument('--isolated', action='store_true')
    part = sub.add_parser('apply')
    part.add_argument('--plan', required=True); part.add_argument('--sha256', required=True)
    args = parser.parse_args()
    try:
        require(not (hasattr(args, 'instance') and pathlib.Path(args.instance).is_symlink()), 'Instance config cannot be a symlink')
        {'plan': prepare, 'apply': apply}[args.action](args)
    except backup.BackupRefused as failure:
        print('Rollback refused: ' + str(failure), file=sys.stderr)
        return 1
    except Exception:
        print('Rollback refused or failed. Private output withheld; inspect the journal and keep failed services stopped.', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
