#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Bind source, full local suites, homogeneous judge measurements and exact cleanup facts."""
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

repo=pathlib.Path(__file__).resolve().parents[2]
build=(repo/sys.argv[1]).resolve()
run=(repo/sys.argv[2]).resolve()
assert build.is_relative_to(repo/'target') and run.is_relative_to(repo/'target')
out=repo/'docs/archive/evidence/portfolio-readiness/20261114'
assert not out.exists(), 'Preserve existing evidence; do not overwrite a prior collection'
out.mkdir(parents=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs={}
for name,pattern in [('backend',r'\./(?:pom\.xml|forgeoj-(?:api|judge-worker)/(?:pom\.xml|src/.*)|contracts/.*)'),('frontend',r'\./frontend/(?:src/.*|package(?:-lock)?\.json|[^/]*config[^/]*)')]:
    for line in (build/f'source-files.{name}.sha256').read_text().splitlines():
        digest,path=line.split('  ',1)
        if re.fullmatch(pattern,path):
            path=path.removeprefix('./')
            assert sha(repo/path)==digest, 'Compiled source changed: '+path
            inputs[path]=digest
summary={}
for module in ['forgeoj-api','forgeoj-judge-worker']:
    reports=list((build/module/'surefire-reports').glob('TEST-*.xml'))
    assert reports
    sums={k:0 for k in ['tests','failures','errors','skipped']}
    for path in reports:
        xml=ET.parse(path).getroot()
        for key in sums:sums[key]+=int(xml.attrib[key])
    assert sums['failures']==sums['errors']==sums['skipped']==0
    summary[module]={**sums,'suites':len(reports),'jarSha256':sha(build/module/(module+'-0.0.1-SNAPSHOT.jar'))}
frontend=(build/'frontend.log').read_text()
frontend=re.sub(r'\x1b\[[0-9;]*m','',frontend)
assert re.search(r'Tests\s+159 passed \(159\)',frontend) and 'built in' in frontend and 'exited with 1' not in frontend
judge=run/'judge-performance'
result=json.loads((judge/'summary.json').read_text())
assert result['status']=='VERIFIED' and result['measured']==50
cleanup=json.loads((run/'cleanup-result.json').read_text(encoding='utf-8-sig'))
assert cleanup['containers']==cleanup['volumes']==cleanup['networks']==0
assert json.loads((judge/'judge-cleanup.json').read_text(encoding='utf-8-sig'))['status']=='VERIFIED'
protected=[('docs/M2-NEXT-CHAT-HANDOFF.md','e08acd3d26a8cd27aca394bdac91828e13b9455a86f62133c5bafc038b18c054'),('.smtp.qq.local','6df348a6404786791eb57c0dce9c9fb8968f7f0737de304f17d52c3900a03907')]
for path,expected in protected:
    literal="'"+str(repo/path).replace("'","''")+"'"
    digest=subprocess.check_output(['pwsh','-NoProfile','-Command','(Get-FileHash -LiteralPath '+literal+' -Algorithm SHA256).Hash'],text=True).strip().lower()
    assert digest==expected
for name in ['summary.json','environment.json','dataset.json','persisted-facts.json','probe-image.json','run-result.json','client-result.json','server-jobs.json','joined-jobs.json','jobs.ndjson','stages.ndjson','resources.ndjson','judge-cleanup.json']:
    shutil.copyfile(judge/name,out/name)
shutil.copyfile(judge/'checksums.json',out/'original-run-checksums.json')
for name in ['docker-commands.ndjson','command-records.json','command-records.zip']:
    shutil.copyfile(judge/name,out/name)
tool_inputs=json.loads((judge/'tool-inputs.json').read_text())
for item in tool_inputs:
    name=item['path']
    assert name in ['judge-load.mjs','judge-lifecycle-docker.sh','Dockerfile.judge-probe','run-judge-baseline.py','summarize-judge-baseline.py']
    assert sha(judge/'frozen-tools'/name)==item['sha256']
    assert sha(repo/'tools/performance'/name)==item['sha256'], 'Measurement tool changed; preserve and explain the exact revision'
    shutil.copyfile(judge/'frozen-tools'/name,out/name)
shutil.copyfile(judge/'tool-inputs.json',out/'tool-inputs.json')
# Preserve the rejected cohort separately; it is not pooled into the accepted statistics.
failed=repo/'target/forgeoj-deployment-20261114-192105-6f9b4b35'
rejected=json.loads((failed/'judge-performance/rejected-measurement.json').read_text())
assert rejected['status']=='REJECTED_LOG_INTEGRITY' and not rejected['acceptedAsPerformanceEvidence']
failure_cleanup=json.loads((failed/'cleanup-result.json').read_text(encoding='utf-8-sig'))
assert failure_cleanup['containers']==failure_cleanup['volumes']==failure_cleanup['networks']==0
shutil.copyfile(failed/'judge-performance/rejected-measurement.json',out/'rejected-measurement.json')
diagnostic=repo/'target/portfolio-readiness-20261113/append-diagnostic'
with zipfile.ZipFile(out/'rejected-measurement.zip','x',zipfile.ZIP_DEFLATED) as archive:
    for name in ['jobs.ndjson','commands/docker-commands.ndjson','server-jobs.json','stages.ndjson','resources.ndjson','client-result.json','run-result.json']:
        archive.write(failed/'judge-performance'/name,'rejected/'+name)
    for name in ['result.json','probe.mjs','probe-files.sh','concurrent.ndjson','serialized.ndjson']:
        archive.write(diagnostic/name,'diagnostic/'+name)
shutil.copyfile(diagnostic/'result.json',out/'append-diagnostic.json')
def git_oids(paths):
    result=subprocess.check_output(['git','-C',str(repo),'hash-object','--stdin-paths'],input=''.join(p+'\n' for p in paths),text=True).splitlines()
    assert len(result)==len(paths) and all(re.fullmatch('[a-f0-9]{40}',value) for value in result)
    return dict(zip(paths,result))
input_oids=git_oids(sorted(inputs))
(out/'compiled-inputs.json').write_text(json.dumps({'hashMethod':'sha256 is raw build snapshot; gitBlobOid applies repository attributes and line-ending filters','inputs':[{'path':p,'sha256':h,'gitBlobOid':input_oids[p]} for p,h in sorted(inputs.items())]},indent=2)+'\n')
source={p.relative_to(repo).as_posix():sha(p) for p in [repo/'pom.xml',repo/'README.md',repo/'DESIGN.md',repo/'CONTRIBUTING.md',repo/'PERFORMANCE.md',repo/'.gitattributes',repo/'.github/workflows/ci.yml']}
facts={'status':'VERIFIED','scope':'local portfolio readability and homogeneous judge benchmark, not production release','baseCommit':subprocess.check_output(['git','-C',str(repo),'rev-parse','HEAD'],text=True).strip(),'build':str(build.relative_to(repo)),'run':str(run.relative_to(repo)),'compiledInputs':len(inputs),'backend':summary,'frontend':{'tests':159,'suites':30,'allGates':True,'platform':'fixed Linux'},'judge':{'measured':50,'warmup':5,'actualAc':55,'fullRawRecalculation':True,'sameJarInstrumentationOnly':True},'cleanup':cleanup,'source':source,'protectedShaOnly':True,'originalDemoRestarted':False,'publicDeployment':False,'fullM5':'IN_PROGRESS','formalGitHubRelease':False}
facts['sourceGitBlobOids']=git_oids(list(source))
facts['sourceMapping']='Raw SHA256 binds build snapshot bytes; gitBlobOid binds the Git-filtered source. Check both rather than treating Windows EOL conversion as a source change.'
# Scan only the allowlisted export, using this disposable run's random secrets in memory.
# Protected local files are not read. No secret bytes or matching content are printed.
secrets=[p.read_bytes().strip() for directory in [run/'private/secrets',failed/'private/secrets'] for p in directory.iterdir() if p.is_file() and p.stat().st_size]
assert len(secrets)>=12
for path in out.rglob('*'):
    if path.is_file():
        contents=[path.read_bytes()]
        if zipfile.is_zipfile(path):
            with zipfile.ZipFile(path) as archive:
                assert archive.testzip() is None
                contents.extend(archive.read(name) for name in archive.namelist())
        for raw in contents:
            assert not any(secret in raw for secret in secrets), 'Random test secret found in export: '+path.name
            assert not re.search(rb'-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----',raw), 'Private key in export: '+path.name
facts['secretScan']='PASS: exact disposable random secret bytes and private PEM headers; no protected local reads'
(out/'verification.json').write_text(json.dumps(facts,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(out/'manifest.json').write_text(json.dumps([{'path':p.relative_to(out).as_posix(),'sha256':sha(p)} for p in sorted(out.rglob('*')) if p.is_file() and p.name!='manifest.json'],indent=2)+'\n',encoding='utf-8')
print(json.dumps({'status':facts['status'],'backend':summary,'frontend':facts['frontend'],'compiledInputs':len(inputs),'judge':facts['judge']},indent=2))
