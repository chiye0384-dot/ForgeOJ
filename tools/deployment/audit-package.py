# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Read-only audit of an owned disposable package run; never print secret values."""
import json
import pathlib
import subprocess
import sys
import time

run = pathlib.Path(sys.argv[1]).resolve()
docker = sys.argv[2]
state = json.loads((run / 'validation-state.json').read_text(encoding='utf-8-sig'))
assert state['project'].startswith('forgeoj-m5check-')
compose = [docker, 'compose', '--env-file', state['instance'], '-f', str(run / 'package/compose.yml')]
def call(args, stdin=None, check=True):
    result = subprocess.run(args, input=stdin, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError('Owned audit command failed; no private output displayed')
    return result
def sql(role, query, success=True):
    cmd = ['exec', '-T', 'mysql', 'sh', '-c',
           'export MYSQL_PWD=$(cat /run/secrets/' + role + '-password); exec mysql -u' +
           ('root' if role == 'root' else 'forgeoj_' + role) + ' --batch --skip-column-names forgeoj']
    result = call(compose + cmd, query, check=success)
    if success:
        return result.stdout.strip()
    assert result.returncode != 0 and 'ERROR 1142' in result.stderr
    return 'DENIED_1142'
counts = sql('root', 'SELECT (SELECT COUNT(*) FROM user_account),(SELECT COUNT(*) FROM admin_account),(SELECT COUNT(*) FROM problem),(SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND type=\'SQL\'),(SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history);')
assert counts.split() == ['0', '0', '0', '24', '24']
denials = [sql('api','SELECT COUNT(*) FROM problem_test_case;',False),sql('worker','SELECT COUNT(*) FROM user_account;',False)]
for component in ['api', 'worker']:
    info=json.loads(call([docker,'inspect',state['project']+'-'+component+'-1']).stdout)[0]
    assert info['Config']['User'] in ['10001:10001','10002:10002']
    assert info['HostConfig']['ReadonlyRootfs']
    targets=[item['Destination'] for item in info['Mounts']]
    assert ('/var/run/docker.sock' in targets) == (component=='worker')
    assert '/run/secrets/migrator-password' not in targets and '/run/secrets/root-password' not in targets
    assert all(not item.startswith('SPRING_PROFILES_ACTIVE=') for item in info['Config']['Env'])
    for value in (run/'private/secrets').iterdir():
        secret=value.read_text()
        if secret:
            assert not any(secret in item for item in info['Config']['Env'])
image_audit=call([docker,'run','--rm','--network','none','--label','com.forgeoj.m5check='+state['project'],'--entrypoint','bash',state['images'][0]['id'],'-c',
                  'test ! -e /app/classes/db/devdata && test ! -e /app/classes/application-dev.properties && for f in /app/*.sh; do bash -n "$f"; done'])
prom_config=call(compose+['exec','-T','prometheus','promtool','check','config','/etc/prometheus/prometheus.yml'])
targets=json.loads(call(compose+['exec','-T','api','curl','--fail','--silent','http://prometheus:9090/api/v1/targets']).stdout)
assert {item['scrapePool'] for item in targets['data']['activeTargets']} == {'forgeoj-api','forgeoj-worker','rabbitmq'}
for _ in range(4):
    if all(item['health']=='up' for item in targets['data']['activeTargets']):
        break
    time.sleep(5)
    targets=json.loads(call(compose+['exec','-T','api','curl','--fail','--silent','http://prometheus:9090/api/v1/targets']).stdout)
assert all(item['health']=='up' for item in targets['data']['activeTargets'])
for name, port in [('api',9091),('worker',9092)]:
    status=call(compose+['exec','-T','api','curl','-s','-o','/dev/null','-w','%{http_code}',f'http://{name}:{port}/metrics']).stdout
    assert status=='401'
grafana=json.loads(call(compose+['exec','-T','api','curl','--fail','--silent','http://grafana:3000/api/health']).stdout)
assert grafana['database']=='ok'
resolved=json.loads(call(compose+['config','--format','json']).stdout)
for name, service in resolved['services'].items():
    if name not in ['web','grafana']:
        assert not service.get('ports')
    if name in ['prometheus','grafana']:
        assert any(mount['type']=='volume' for mount in service['volumes'])
    if name=='grafana':
        assert len(service['ports'])==1 and service['ports'][0]['host_ip']=='127.0.0.1'
        assert str(service['ports'][0]['published'])=='3000'
        actual_ports=json.loads(call([docker,'inspect',state['project']+'-grafana-1']).stdout)[0]['NetworkSettings']['Ports']['3000/tcp']
        assert actual_ports and all(item['HostIp']=='127.0.0.1' for item in actual_ports)
result={'status':'PASS','counts':{'users':0,'admins':0,'problems':0,'versionedMigrations':24,'schemaVersion':24},
        'permissionDenials':denials,'apiWithoutDocker':True,'workerNonRoot':True,'noDevdataInImage':True,
        'noSecretsInRuntimeConfigEnv':True,'scrapeTargetsUp':3,'unauthenticatedMetricsRefused':2,'grafanaDatabase':'ok',
        'monitoringNamedVolumes':True,'scope':'isolated read-only package audit; not fresh-host/backup/rollback/performance/user-trial verification'}
(run/'package-audit.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
