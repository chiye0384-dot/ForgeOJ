#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Fault/recovery checks on a NEW owned empty deployment only. No automatic retry."""
import datetime as dt
import importlib.util
import json
import pathlib
import re
import subprocess
import sys
import time

sys.dont_write_bytecode = True
repo = pathlib.Path(__file__).resolve().parents[2]
run = (repo / sys.argv[1]).resolve()
docker, node = sys.argv[2:4]
assert run.is_relative_to(repo / 'target') and re.fullmatch(r'forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}', run.name)
state = json.loads((run / 'validation-state.json').read_text(encoding='utf-8-sig'))
assert re.fullmatch(r'forgeoj-m5check-[a-f0-9]{12}', state['project'])
assert pathlib.Path(state['package']) == run / 'package'
evidence = run / ('rollback-attempt-' + dt.datetime.now().strftime('%Y%m%d-%H%M%S'))
evidence.mkdir()
spec = importlib.util.spec_from_file_location('owned_rollback_backup', run / 'package/scripts/backup.py')
backup = importlib.util.module_from_spec(spec); spec.loader.exec_module(backup)
instance = backup.Instance(state['package'], state['instance'], docker)
compose = instance.compose
cli = [sys.executable, '-B', str(run / 'package/scripts/rollback.py'), '--docker', docker]
original = {item['component']: item['id'] for item in state['images']}
extra_images = json.loads((run / 'rollback-images.json').read_text()) if (run / 'rollback-images.json').exists() else []


def call(args, data=None, fail=False, log=None, timeout=240):
    result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    if log:
        (evidence / log).write_bytes(result.stdout + result.stderr)
    if fail:
        assert result.returncode != 0, 'Expected refusal did not occur'
    elif result.returncode:
        raise RuntimeError('Owned rollback verification command failed; private output retained')
    return result


def plan_args(target_api, name):
    return ['plan', '--package', state['package'], '--instance', state['instance'], '--api', target_api,
            '--worker', original['worker'], '--web', original['web'], '--output', str(evidence / name), '--isolated']


def build_fixture(suffix, instruction):
    tag = state['project'] + '-rollback-' + suffix
    context = run / ('image-fixture-' + suffix)
    recipe = 'FROM ' + state['project'] + '-api:latest\n' + instruction + '\n'
    existing = [item for item in extra_images if item['tag'] == tag]
    if existing:
        assert len(existing) == 1 and (context / 'Dockerfile').read_text() == recipe
        actual = json.loads(call([docker, 'image', 'inspect', tag]).stdout)[0]['Id']
        assert actual == existing[0]['id']
        return actual  # Reuse the recorded immutable fixture, never rebuild it on resume.
    if context.exists():
        assert (context / 'Dockerfile').read_text() == recipe
    else:
        context.mkdir()
        (context / 'Dockerfile').write_text(recipe)
    call([docker, 'build', '--pull=false', '--platform', 'linux/amd64', '-t', tag, str(context)], log='build-' + suffix + '.log')
    image = json.loads(call([docker, 'image', 'inspect', tag]).stdout)[0]['Id']
    extra_images.append({'tag': tag, 'id': image})
    (run / 'rollback-images.json').write_text(json.dumps(extra_images, indent=2))
    return image


def sql_proof(path):
    proof = json.loads(path.read_text())
    assert re.fullmatch('[a-f0-9-]{36}', proof['submissionId'])
    query = "SELECT processing_status,verdict,SHA2(source_code,256) FROM submission WHERE id='" + proof['submissionId'] + "';"
    assert instance.sql(query).strip() == 'FINISHED\tAC\t' + proof['sourceSha256']


def http(name, mode='submit', previous=None):
    args = [node, str(repo / 'tools/deployment/rollback-http.mjs'), str(run / 'private'), str(evidence / name), mode]
    if previous:
        args.append(str(evidence / previous))
    return call(args, log=name + '.log')


if '--reuse-ready' in sys.argv[4:]:
    # A new evidence generation on the same fixture; preserve all prior submissions/failed artifacts.
    sql_proof(run / 'rollback-before-http.json')
    for item in state['images']:
        info = json.loads(call([docker, 'inspect', state['project'] + '-' + item['component'] + '-1']).stdout)[0]
        assert info['Image'] == item['id'] and info['State'].get('Health', {}).get('Status') == 'healthy'
    http('rollback-before-http.json')
elif '--resume-owned' in sys.argv[4:]:
    # Only resume before the first plan, preserving the original real AC and history.
    assert (run / 'rollback-before-http.json').exists() and not (run / 'bad-release-plan.json').exists()
    for item in state['images']:
        info = json.loads(call([docker, 'inspect', state['project'] + '-' + item['component'] + '-1']).stdout)[0]
        assert info['Image'] == item['id'] and info['State'].get('Health', {}).get('Status') == 'healthy'
    # The earlier resume mode also creates a new honest HTTP proof for this generation.
    http('rollback-before-http.json')
else:
    assert instance.sql('SELECT COUNT(*) FROM user_account;').strip() == '0'
    instance.sql((repo / 'forgeoj-api/src/main/resources/db/devdata/R__seed_m0_development_data.sql').read_text(encoding='utf-8-sig'))
    http('rollback-before-http.json')
sql_proof(evidence / 'rollback-before-http.json')
bad = build_fixture('bad-entrypoint', 'ENTRYPOINT ["java", "-XX:ForgeOJDeliberateStartupFailure=1"]')
changed = build_fixture('changed-filesystem-user-owned', 'RUN printf owned-negative-fixture > /tmp/rollback-negative-fixture')
call(cli + plan_args(bad, 'active-refused.json'), fail=True, log='rollback-active-refused.log')
assert not (evidence / 'active-refused.json').exists()

# A real queued submission while this exact Worker is stopped; never write judge facts by hand.
call(compose + ['stop', '--timeout', '30', 'worker'])
http('rollback-queued-http.json', 'queue')
call(compose + ['stop', '--timeout', '30', 'web', 'api'])
call(cli + plan_args(bad, 'pending-refused.json'), fail=True, log='rollback-pending-refused.log')
assert not (evidence / 'pending-refused.json').exists()
call(compose + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'api', 'worker', 'web'])
queued = json.loads((evidence / 'rollback-queued-http.json').read_text())
for _ in range(80):
    if instance.sql("SELECT processing_status FROM submission WHERE id='" + queued['submissionId'] + "';").strip() == 'FINISHED':
        break
    time.sleep(1.5)
sql_proof(evidence / 'rollback-queued-http.json')
call(compose + ['stop', '--timeout', '30', 'web', 'api', 'worker'])

for target, name in [(original['api'], 'same-image'), (state['project'] + '-api:latest', 'mutable-tag'), (changed, 'changed-filesystem')]:
    call(cli + plan_args(target, name + '-refused.json'), fail=True, log='rollback-' + name + '-refused.log')
    assert not (evidence / (name + '-refused.json')).exists()

prepared = json.loads(call(cli + plan_args(bad, 'bad-release-plan.json'), log='rollback-plan.log').stdout)
plan = evidence / 'bad-release-plan.json'
apply = ['apply', '--plan', str(plan), '--sha256', prepared['sha256']]
baseline = instance.snapshot()
schema_baseline = backup.sha(instance.dump())
env = pathlib.Path(state['instance'])
original_env = env.read_bytes()
call(cli + apply[:-1] + ['0' * 64], fail=True, log='rollback-digest-refused.log')
assert env.read_bytes() == original_env
with backup.instance_lock(instance.instance):
    call(cli + apply, fail=True, log='rollback-lock-refused.log')
assert not pathlib.Path(str(plan) + '.applied.json').exists()
env.write_bytes(original_env + b'\n# owned drift probe\n')
try:
    call(cli + apply, fail=True, log='rollback-drift-refused.log')
    assert not pathlib.Path(str(plan) + '.applied.json').exists()
finally:
    env.write_bytes(original_env)
# Index-only DDL preserves data-only snapshots; the full schema fingerprint must reject it.
instance.sql('CREATE INDEX rollback_drift_probe ON user_account(username);')
try:
    assert instance.snapshot() == baseline and backup.sha(instance.dump()) != schema_baseline
    refused = call(cli + apply, fail=True, log='rollback-schema-drift-refused.log')
    assert b'changed after planning' in refused.stderr
    assert not pathlib.Path(str(plan) + '.applied.json').exists()
finally:
    instance.sql('DROP INDEX rollback_drift_probe ON user_account;')
assert backup.sha(instance.dump()) == schema_baseline
expired = json.loads(plan.read_text()); expired['createdAt'] = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(hours=1)).isoformat()
expiry = evidence / 'expired-plan.json'; expiry.write_text(json.dumps(expired))
call(cli + ['apply', '--plan', str(expiry), '--sha256', backup.sha(expiry.read_bytes())], fail=True, log='rollback-expired-refused.log')
assert env.read_bytes() == original_env and instance.snapshot() == baseline

print('Planning refusals passed; injecting owned startup configuration failure.', flush=True)
call(cli + apply, fail=True, log='rollback-target-failure.log', timeout=210)
failed = json.loads(pathlib.Path(str(plan) + '.applied.json').read_text())
assert failed['status'] == 'FAILED_STOPPED'
assert not call(compose + ['ps', '-q', 'api', 'worker', 'web']).stdout.strip()
assert instance.snapshot() == baseline
call(cli + apply, fail=True, log='rollback-replay-refused.log')
assert json.loads(pathlib.Path(str(plan) + '.applied.json').read_text()) == failed

# Source is now the stopped bad image, target the previously working immutable image.
prepared = json.loads(call(cli + plan_args(original['api'], 'recovery-plan.json'), log='rollback-recovery-plan.log').stdout)
recovery = evidence / 'recovery-plan.json'
recovery_apply = ['apply', '--plan', str(recovery), '--sha256', prepared['sha256']]
result = json.loads(call(cli + recovery_apply, log='rollback-recovery-apply.log').stdout)
assert result['status'] == 'READY_FOR_BUSINESS_CHECK'
assert instance.snapshot() == baseline
http('rollback-after-http.json', 'history-submit', 'rollback-before-http.json')
for name in ['rollback-before-http.json', 'rollback-queued-http.json', 'rollback-after-http.json']:
    sql_proof(evidence / name)
for role, table in [('api', 'problem_test_case'), ('worker', 'user_account')]:
    query = 'export MYSQL_PWD=$(cat /run/secrets/' + role + '-password); exec mysql -uforgeoj_' + role + ' forgeoj'
    refused = call(compose + ['exec', '-T', 'mysql', 'sh', '-c', query], ('SELECT COUNT(*) FROM ' + table + ';').encode(), fail=True)
    assert b'ERROR 1142' in refused.stderr

images = json.loads(call([docker, 'image', 'inspect', original['api'], bad]).stdout)
assert original['api'] != bad and images[0]['RootFS'] == images[1]['RootFS']
report = {
    'status': 'PASS', 'scope': 'same-filesystem ENTRYPOINT fault and rollback only; not cross-business-version/schema rollback',
    'project': state['project'], 'goodApiImage': original['api'], 'badApiImage': bad,
    'distinctImageIds': True, 'identicalFilesystemLayers': True,
    'sourceEntrypoint': images[0]['Config']['Entrypoint'], 'faultEntrypoint': images[1]['Config']['Entrypoint'],
    'startupFailureStoppedAllApplications': True, 'rollback': result,
    'maintenanceSnapshotMatched': True, 'schemaAndGrantsUnchanged': True, 'sqlPermissionDenials': 2,
    'httpsProofs': [json.loads((evidence / name).read_text()) for name in ['rollback-before-http.json', 'rollback-queued-http.json', 'rollback-after-http.json']],
    'queuedSubmissionNaturallyCompleted': True,
    'negativeCases': ['active writers', 'pending real submission', 'same image', 'mutable tag', 'changed filesystem',
                      'wrong plan digest', 'held instance lock', 'instance config drift', 'index-only schema drift', 'expired plan', 'replayed attempt'],
    'databaseRestored': False, 'migrationsRunDuringRollback': False, 'queuesPurged': False,
    'businessVersionsDiffer': False, 'notVerified': ['cross-business-version compatibility', 'schema upgrade/downgrade', 'production Linux host', 'production cutover'],
}
(evidence / 'rollback-verification.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report))
