# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Real disposable MySQL/Worker/crypto verification; never invoke against production."""
import datetime as dt
import hashlib
import importlib.util
import json
import pathlib
import secrets
import shutil
import subprocess
import sys
import uuid

repo=pathlib.Path(__file__).resolve().parents[2]
run=(repo/sys.argv[1]).resolve()
docker=sys.argv[2]
node=sys.argv[3]
assert run.is_relative_to(repo/'target') and run.name.startswith('forgeoj-deployment-')
state=json.loads((run/'validation-state.json').read_text(encoding='utf-8-sig'))
assert state['project'].startswith('forgeoj-m5check-')
spec=importlib.util.spec_from_file_location('owned_backup_tool',repo/'deploy/production/scripts/backup.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
def call(args, data=None, fail=False):
    result=subprocess.run(args,input=data,capture_output=True,timeout=240)
    if fail:
        assert result.returncode != 0
    elif result.returncode:
        raise RuntimeError('Owned backup verification command failed; output is private')
    return result
cli=[sys.executable,str(repo/'deploy/production/scripts/backup.py'),'--docker',docker]
source=tool.Instance(state['package'],state['instance'],docker)
compose=source.compose

# A verifier-only resume keeps the original real AC and does not reset or manufacture data.
if '--resume-source' in sys.argv[4:]:
    proof=json.loads((run/'backup-source-http.json').read_text())
    import re
    assert re.fullmatch('[a-f0-9-]{36}',proof['submissionId']) and proof['verdict']=='AC'
    assert source.sql("SELECT processing_status,verdict FROM submission WHERE id='"+proof['submissionId']+"';").strip()=='FINISHED\tAC'
    call(compose+['up','-d','--no-deps','--wait','--wait-timeout','120','api','worker','web'])
else:
    assert source.sql('SELECT COUNT(*) FROM user_account;').strip()=='0'
    seed=(repo/'forgeoj-api/src/main/resources/db/devdata/R__seed_m0_development_data.sql').read_text(encoding='utf-8-sig')
    source.sql(seed)
    print('Owned source fixture installed; running ordinary HTTPS submission.',flush=True)
    call([node,str(repo/'tools/deployment/backup-source-fixture.mjs'),str(run/'private'),str(run/'backup-source-http.json')])
fixture=json.loads((run/'backup-source-http.json').read_text())
assert fixture['verdict']=='AC'

offline=run/'offline-decryption-key'
wrong=run/'wrong-offline-key'
if not offline.exists():
    call(cli+['keygen','--directory',str(offline)])
if not wrong.exists():
    call(cli+['keygen','--directory',str(wrong)])
key_hash=tool.sha((offline/'private.pem').read_bytes())
call(cli+['keygen','--directory',str(offline)],fail=True)
assert tool.sha((offline/'private.pem').read_bytes())==key_hash
recipient=offline/'recipient.pem'
artifact=run/('encrypted-copy/source-'+uuid.uuid4().hex[:8]+'.cms')
source_args=['--package',state['package'],'--instance',state['instance'],'--recipient',str(recipient)]
call(cli+['backup']+source_args+['--output',str(artifact)],fail=True)
assert not artifact.exists()  # Active-writer refusal must precede publication.
call(compose+['stop','--timeout','30','web','api','worker'])
before=source.snapshot()
for table in ['user_account','submission','judge_task','judge_task_attempt','outbox_event','login_session']:
    assert before['counts'][table]>0
call(cli+['backup']+source_args+['--output',str(artifact)])
receipt=json.loads(artifact.with_suffix('.cms.receipt.json').read_text())
assert receipt['status']=='LOCAL_ENCRYPTED' and not receipt['offsiteConfirmed']
assert fixture['marker'].encode() not in artifact.read_bytes()
artifact_hash=tool.sha(artifact.read_bytes())
call(cli+['backup']+source_args+['--output',str(artifact)],fail=True)
assert tool.sha(artifact.read_bytes())==artifact_hash
assert source.snapshot()==before
print('Real source encrypted; active-writer/overwrite refusal passed.',flush=True)

# A separate project/private credential set and empty server; never migrate it before restore.
name='forgeoj-deployment-'+dt.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8]
target_run=repo/'target'/name
target_run.mkdir()
shutil.copytree(run/'package',target_run/'package')
private=target_run/'private'
for directory in ['secrets','tls','search']:
    (private/directory).mkdir(parents=True)
for file in (run/'private/secrets').iterdir():
    (private/'secrets'/file.name).write_text(secrets.token_hex(32) if file.stat().st_size else '')
project='forgeoj-m5check-'+uuid.uuid4().hex[:12]
config=dict(source.config)
config['COMPOSE_PROJECT_NAME']=project
config['FORGEOJ_PRIVATE_DIR']=private.as_posix()
(target_run/'instance.env').write_text('\n'.join(key+'='+value for key,value in config.items())+'\n')
images=[]
for image in state['images']:
    tag=project+'-'+image['component']
    call([docker,'tag',image['id'],tag])
    images.append({'component':image['component'],'id':image['id'],'tag':tag})
target_state={'project':project,'package':str(target_run/'package'),'instance':str(target_run/'instance.env'),
              'privateDirectory':str(private),'status':'EMPTY_RESTORE_TARGET','images':images}
(target_run/'validation-state.json').write_text(json.dumps(target_state,indent=2))
targets_path=run/'backup-targets.json'
targets=json.loads(targets_path.read_text()) if targets_path.exists() else []
targets.append({'run':str(target_run),'project':project})
targets_path.write_text(json.dumps(targets,indent=2))
target_compose=[docker,'compose','--project-name',project,'--env-file',str(target_run/'instance.env'),'-f',str(target_run/'package/compose.yml')]
call(target_compose+['up','-d','--no-deps','--wait','--wait-timeout','180','mysql'])
target=tool.Instance(target_run/'package',target_run/'instance.env',docker)
assert not target.tables()
restore=['restore','--package',str(target_run/'package'),'--instance',str(target_run/'instance.env'),
         '--recipient',str(recipient),'--private-key',str(offline/'private.pem'),'--backup',str(artifact),'--sha256',artifact_hash]
wrong_key=list(restore);wrong_key[wrong_key.index('--private-key')+1]=str(wrong/'private.pem')
call(cli+wrong_key,fail=True);assert not target.tables()
corrupt=run/('encrypted-copy/corrupt-'+uuid.uuid4().hex[:8]+'.cms')
data=bytearray(artifact.read_bytes());data[len(data)//2]^=1;corrupt.write_bytes(data)
tampered=list(restore);tampered[tampered.index('--backup')+1]=str(corrupt)
call(cli+tampered,fail=True);assert not target.tables()
# Supply the corrupted file's hash intentionally to test AEAD, beyond the trusted digest check.
tampered[tampered.index('--sha256')+1]=tool.sha(data)
call(cli+tampered,fail=True);assert not target.tables()
same_source=list(restore)
same_source[same_source.index('--package')+1]=state['package']
same_source[same_source.index('--instance')+1]=state['instance']
call(cli+same_source,fail=True);assert source.snapshot()==before
call(cli+restore)
assert target.snapshot()==before and source.snapshot()==before
call(cli+restore,fail=True);assert target.snapshot()==before
call(target_compose+['run','--rm','--no-deps','migrate'])
assert target.snapshot()==before

# Exact table/column grants survive with fresh database passwords; forbidden reads still fail 1142.
for role,table in [('api','problem_test_case'),('worker','user_account')]:
    shell='export MYSQL_PWD=$(cat /run/secrets/'+role+'-password); exec mysql -uforgeoj_'+role+' forgeoj'
    denied=call(target_compose+['exec','-T','mysql','sh','-c',shell],('SELECT COUNT(*) FROM '+table+';').encode(),fail=True)
    assert b'ERROR 1142' in denied.stderr
print('New server restored: all rows/grants match; wrong key/tamper/source/nonempty target refused.',flush=True)

# An adversarial calendar fixture, not a claim of having run backups over seven days/four weeks.
catalog=run/('retention-fixture-'+uuid.uuid4().hex[:8]);catalog.mkdir()
now=dt.datetime.now(dt.timezone.utc)
for offset in [0,0,1,2,3,4,5,6,7,8,9,14,21,28,35]:
    filename='copy-'+str(len(list(catalog.glob('*.cms'))))+'.cms'
    when=now-dt.timedelta(days=offset)
    shutil.copyfile(artifact,catalog/filename)
    item=dict(receipt,artifact=filename,createdAt=when.isoformat())
    (catalog/(filename+'.receipt.json')).write_text(json.dumps(item))
original_files={item.name:hashlib.sha256(item.read_bytes()).hexdigest() for item in catalog.iterdir()}
plan=json.loads(call(cli+['retention-plan','--directory',str(catalog)]).stdout)
assert plan['deleted']==0 and len(plan['deletionCandidates'])>0
assert len(plan['keep'])>=7
assert {item.name:hashlib.sha256(item.read_bytes()).hexdigest() for item in catalog.iterdir()}==original_files
kept=[json.loads((catalog/(name+'.receipt.json')).read_text()) for name in plan['keep']]
assert len({dt.datetime.fromisoformat(item['createdAt']).date() for item in kept})>=7
assert len({dt.datetime.fromisoformat(item['createdAt']).isocalendar()[:2] for item in kept})>=4
result={'status':'PASS','sourceProject':source.project,'targetProject':project,'targetRun':str(target_run),
        'realSubmission':{'id':fixture['submissionId'],'verdict':'AC'},'tablesMatched':len(before['counts']),
        'allRowsAndRuntimeGrantsMatched':True,'flywayValidateAfterRestore':True,'permissionDenials':2,
        'negativeCases':['existing offline key directory','active source writers','existing backup output','wrong private key','wrong ciphertext digest','authenticated ciphertext corruption','source instance restore','nonempty target restore'],
        'noPlaintextDatabaseDumpFileWritten':True,'sourceUnchanged':True,'retentionPlan':{'keep':len(plan['keep']),'deleteCandidates':len(plan['deletionCandidates']),'deleted':0},
        'encryptedArtifactSha256':artifact_hash,'scope':'maintenance-only disposable MySQL backup/restore; local file copy; no offsite/scheduler/production cutover/RabbitMQ recovery'}
(run/'backup-verification.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
