# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Owned, empty disposable project only. Fault and persistence checks are explicit mutations."""
import base64
import json
import pathlib
import subprocess
import sys
import time
import urllib.parse
import urllib.request

repo = pathlib.Path(__file__).resolve().parents[2]
run = (repo / sys.argv[1]).resolve()
assert run.is_relative_to(repo / 'target') and run.name.startswith('forgeoj-deployment-')
docker = sys.argv[2]
state = json.loads((run / 'validation-state.json').read_text(encoding='utf-8-sig'))
project = state['project']
assert project.startswith('forgeoj-m5check-') and pathlib.Path(state['package']).resolve() == run / 'package'
compose = [docker, 'compose', '--env-file', state['instance'], '-f', str(run / 'package/compose.yml')]
def call(args, check=True, stdin=None):
    result = subprocess.run(args, input=stdin, text=True, capture_output=True, timeout=240)
    if check and result.returncode:
        raise RuntimeError('Owned lifecycle command failed; private output withheld')
    return result
def inspect(service):
    info = json.loads(call([docker, 'inspect', project + '-' + service + '-1']).stdout)[0]
    assert info['Config']['Labels']['com.docker.compose.project'] == project
    return info
def healthy(service):
    return inspect(service)['State'].get('Health', {}).get('Status') == 'healthy'
def wait_for(predicate, limit=90):
    deadline = time.monotonic() + limit
    while not predicate():
        if time.monotonic() > deadline:
            raise RuntimeError('Owned lifecycle readiness deadline exceeded')
        time.sleep(3)
def ready(service):
    return call(compose + ['exec', '-T', service, 'sh', '/app/health.sh', service], check=False).returncode == 0
for service in ['api', 'worker', 'web', 'prometheus', 'grafana']:
    assert healthy(service)
    assert inspect(service)['Config']['Healthcheck']['Test']

# A real Linux filesystem, not Windows bind-mode emulation. It is always removed.
volume = project + '-permission-probe'
call([docker, 'volume', 'create', '--label', 'com.forgeoj.m5check=' + project, volume])
probe = r'''
set -euo pipefail
bash /package/scripts/init-private.sh /probe/private >/dev/null
cp /fixture/tls/fullchain.pem /fixture/tls/privkey.pem /probe/private/tls/
cp /fixture/search/search.crt /fixture/search/search.key /fixture/search/trust.p12 /probe/private/search/
chmod 0444 /probe/private/tls/fullchain.pem /probe/private/search/*
chmod 0600 /probe/private/tls/privkey.pem
bash /package/scripts/private-permissions.sh /probe/private
before=$(sha256sum /probe/private/secrets/api-password)
if bash /package/scripts/init-private.sh /probe/private >/dev/null 2>&1; then exit 1; fi
test "$before" = "$(sha256sum /probe/private/secrets/api-password)"
chmod 0755 /probe/private
if bash /package/scripts/private-permissions.sh /probe/private; then exit 1; fi
chmod 0700 /probe/private
chmod 0644 /probe/private/secrets/api-password
if bash /package/scripts/private-permissions.sh /probe/private; then exit 1; fi
chmod 0444 /probe/private/secrets/api-password
chmod 0644 /probe/private/tls/privkey.pem
if bash /package/scripts/private-permissions.sh /probe/private; then exit 1; fi
chmod 0600 /probe/private/tls/privkey.pem
mv /probe/private/secrets/api-password /probe/private/saved-api
ln -s worker-password /probe/private/secrets/api-password
if bash /package/scripts/private-permissions.sh /probe/private; then exit 1; fi
rm /probe/private/secrets/api-password
mv /probe/private/saved-api /probe/private/secrets/api-password
bash /package/scripts/private-permissions.sh /probe/private
mkdir /probe/fakebin
printf '#!/bin/sh
exit 0
' > /probe/fakebin/python3
cat > /probe/fakebin/docker <<'SH'
#!/bin/sh
case "$*" in *' ps -q api worker web') echo running-owned-writer;; *) touch /probe/unexpected-mutation; exit 1;; esac
SH
chmod 0755 /probe/fakebin/*
touch /probe/instance.env
if PATH="/probe/fakebin:$PATH" bash /package/scripts/start.sh /probe/instance.env --isolated >/probe/start-refusal 2>&1; then exit 1; fi
grep -q 'Stop this instance writers' /probe/start-refusal
test ! -e /probe/unexpected-mutation
if PATH="/probe/fakebin:$PATH" flock /probe/instance.env.start.lock bash /package/scripts/start.sh /probe/instance.env --isolated >/probe/lock-refusal 2>&1; then exit 1; fi
grep -q 'already being started' /probe/lock-refusal
'''
try:
    common = [docker, 'run', '--rm', '--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--label', 'com.forgeoj.m5check=' + project]
    call(common + ['--user', '0:0', '--mount', 'type=volume,src=' + volume + ',dst=/probe',
                  '--mount', 'type=bind,src=' + str(run / 'package') + ',dst=/package,readonly',
                  '--mount', 'type=bind,src=' + str(run / 'private') + ',dst=/fixture,readonly',
                  '--entrypoint', 'bash', state['images'][0]['id'], '-c', probe])
    mountpoint = json.loads(call([docker, 'volume', 'inspect', volume]).stdout)[0]['Mountpoint']
    for uid, relative in [('10001:10001', 'secrets/api-password'), ('10002:10002', 'secrets/worker-password'), ('1000:1000', 'search/search.key')]:
        call(common + ['--user', uid, '--mount', 'type=bind,src=' + mountpoint + '/private/' + relative + ',dst=/authorized,readonly',
                      '--entrypoint', 'sh', state['images'][0]['id'], '-c', 'test -r /authorized && test -s /authorized'])
    call(common + ['--user', '10001:10001', '--mount', 'type=volume,src=' + volume + ',dst=/probe,readonly',
                  '--entrypoint', 'sh', state['images'][0]['id'], '-c', 'test ! -r /probe/private/secrets/worker-password'])
finally:
    call([docker, 'volume', 'rm', volume])

# Stop only this empty fixture's MySQL. Restore it even if the assertions fail.
stopped = False
try:
    call(compose + ['stop', '--timeout', '20', 'mysql'])
    stopped = True
    wait_for(lambda: not ready('api') and not ready('worker'), 75)
    wait_for(lambda: not healthy('api') and not healthy('worker'), 40)
    outage = {'apiReady': False, 'workerReady': False, 'apiHealth': inspect('api')['State']['Health']['Status'], 'workerHealth': inspect('worker')['State']['Health']['Status']}
finally:
    if stopped:
        call(compose + ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '120', 'mysql'])
wait_for(lambda: healthy('api') and healthy('worker') and healthy('web'), 120)

def prometheus_query(at=None):
    params = {'query': 'up{job="forgeoj-api"}'}
    if at is not None:
        params['time'] = str(at)
    raw = call(compose + ['exec', '-T', 'api', 'curl', '--fail', '--silent', 'http://prometheus:9090/api/v1/query?' + urllib.parse.urlencode(params)]).stdout
    return json.loads(raw)['data']['result']
wait_for(lambda: bool(prometheus_query()) and prometheus_query()[0]['value'][1] == '1', 60)
before = prometheus_query()[0]
evaluation_time = before['value'][0]
password = (run / 'private/secrets/grafana-password').read_text()
authorization = 'Basic ' + base64.b64encode(('forgeoj_monitor_admin:' + password).encode()).decode()
def grafana(path, body=None, method=None):
    req = urllib.request.Request('http://127.0.0.1:3000' + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Authorization': authorization, 'Content-Type': 'application/json'}, method=method)
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=15) as response:
        return json.load(response)
uid = 'persistence-' + project.rsplit('-', 1)[1]
saved = grafana('/api/dashboards/db', {'dashboard': {'uid': uid, 'title': 'Owned persistence probe', 'schemaVersion': 41, 'panels': []}, 'overwrite': False})
assert saved['uid'] == uid
prior_ids = {service: inspect(service)['Id'] for service in ['prometheus', 'grafana']}
# Recreation is stronger than restart: it excludes data held only in a container layer.
call(compose + ['up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '180', 'prometheus', 'grafana'])
for service in ['prometheus', 'grafana']:
    assert healthy(service) and inspect(service)['Id'] != prior_ids[service]
after = prometheus_query(evaluation_time)[0]
assert after == before
loaded = grafana('/api/dashboards/uid/' + uid)
assert loaded['dashboard']['id'] == saved['id'] and loaded['dashboard']['title'] == 'Owned persistence probe'
grafana('/api/dashboards/uid/' + uid, method='DELETE')

secret_values = [file.read_text() for file in (run / 'private/secrets').iterdir() if file.stat().st_size]
for service in ['api', 'worker', 'web', 'prometheus', 'grafana']:
    info = inspect(service)
    serialized = json.dumps({'test': info['Config']['Healthcheck']['Test'], 'history': info['State']['Health']['Log']})
    assert all(value not in serialized for value in secret_values)
result = {'status': 'PASS', 'readinessServices': 5, 'linuxEnginePermissionProbe': {'status': 'PASS', 'negativeCases': 4,
          'authorizedLeafReads': 3, 'privateParentDenied': True, 'existingSecretOverwriteRefused': True},
          'startupGuards': {'activeWriterRefused': True, 'concurrentStartRefused': True, 'scope': 'real bash script with controlled preflight/docker dependencies'},
          'dependencyOutage': outage, 'recovery': 'api/worker/web healthy',
          'monitoringRecreated': ['prometheus', 'grafana'], 'historicalPrometheusSampleRetained': True,
          'grafanaDashboardRetained': True, 'noSecretInHealthCommandOrHistory': True,
          'scope': 'isolated Docker Linux engine; not a fresh Linux host or real user trial'}
(run / 'package-lifecycle.json').write_text(json.dumps(result, indent=2))
print(json.dumps(result))
