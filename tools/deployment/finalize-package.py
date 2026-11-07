# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import hashlib
import json
import pathlib
import sys
import zipfile

repo=pathlib.Path(__file__).resolve().parents[2]
run=(repo/sys.argv[1]).resolve()
assert run.is_relative_to(repo/'target') and run.name.startswith('forgeoj-deployment-')
package=run/'package'
state=json.loads((run/'validation-state.json').read_text(encoding='utf-8-sig'))
audit=json.loads((run/'package-audit.json').read_text())
smoke=json.loads((run/'https-smoke.json').read_text())
assert audit['status']==smoke['status']=='PASS'
manifest=json.loads((package/'package-manifest.json').read_text(encoding='utf-8-sig'))
revision=manifest.get('deploymentRevision', 1)
lifecycle=None
if revision >= 2:
    lifecycle=json.loads((run/'package-lifecycle.json').read_text())
    assert lifecycle['status']=='PASS'
cleanup=json.loads((run/'cleanup-result.json').read_text(encoding='utf-8-sig'))
assert cleanup['project']==state['project'] and cleanup['containers']==cleanup['volumes']==cleanup['networks']==0
expected={item['path'] for item in manifest['files']}|{'package-manifest.json'}
actual={file.relative_to(package).as_posix() for file in package.rglob('*') if file.is_file()}
assert actual==expected
for item in manifest['files']:
    file=(package/item['path']).resolve()
    assert file.is_relative_to(package)
    assert hashlib.sha256(file.read_bytes()).hexdigest()==item['sha256']
archive=run/(run.name+'.zip')
assert not archive.exists()
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as output:
    for name in sorted(expected):
        output.write(package/name,name)
digest=hashlib.sha256(archive.read_bytes()).hexdigest()
(run/(archive.name+'.sha256')).write_text(digest+'  '+archive.name+'\n')
evidence={'status':'IMPLEMENTED','isolatedPackageChecks':'VERIFIED','deploymentRevision':revision,'m5':'IN_PROGRESS','release':False,
          'sourceCommit':manifest['sourceCommit'],'compiledInputs':manifest['compiledInputs'],
          'apiInputSha256':manifest['apiInputSha256'],'workerInputSha256':manifest['workerInputSha256'],
          'project':state['project'],'images':state['images'],'httpsSmoke':smoke,'readOnlyAudit':audit,
          'archive':str(archive),'archiveSha256':digest,'files':len(expected),'bytes':archive.stat().st_size,
          'cleanup':cleanup,'lifecycle':lifecycle,
          'notVerified':['fresh Linux host','real domain/DNS/CA/SMTP','old-version schema upgrade','external encrypted backup/restore','compatible version rollback','fixed-host performance','controlled real-user trial','Linux host file permissions','full release dependency and image audit']}
folder=repo/'docs/evidence/m5-deployment'/run.name
folder.mkdir(parents=True,exist_ok=True)
assert not (folder/'verification.json').exists()
(folder/'verification.json').write_text(json.dumps(evidence,indent=2,ensure_ascii=False),encoding='utf-8')
print(json.dumps({'archive':str(archive),'sha256':digest,'bytes':archive.stat().st_size,'files':len(expected)}))
