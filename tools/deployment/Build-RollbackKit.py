#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Package only the original rollback tools and documentation; never include instance artifacts."""
import ast
import datetime as dt
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import uuid
import zipfile

repo = pathlib.Path(__file__).resolve().parents[2]
stamp = dt.datetime.now(dt.timezone(dt.timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
name = 'forgeoj-rollback-tools-' + stamp + '-' + uuid.uuid4().hex[:8]
out = repo / 'target' / name
package = out / 'package'
package.mkdir(parents=True)
for filename in ['rollback.py', 'backup.py']:
    shutil.copyfile(repo / 'deploy/production/scripts' / filename, package / filename)
readme = (repo / 'docs/archive/M5-IMAGE-ROLLBACK.md').read_text(encoding='utf-8')
readme = readme.replace('[验收记录](M5-IMAGE-ROLLBACK-VALIDATION.md)', '仓库文档 docs/archive/M5-IMAGE-ROLLBACK-VALIDATION.md（不随工具包分发）')
readme = readme.replace('scripts/rollback.py', 'rollback.py')
(package / 'README.md').write_text(readme, encoding='utf-8')
shutil.copyfile(repo / 'LICENSE', package / 'LICENSE')
(package / 'NOTICE').write_text(
    'ForgeOJ rollback tools\nCopyright 2026 池也\n\n'
    'ForgeOJ-authored Python and documentation: Apache-2.0.\n'
    'No third-party runtime or binaries are distributed in this kit.\n'
    'Docker and MySQL remain upstream software. Keep the separate deployment notices.\n', encoding='utf-8')
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {'format': 'forgeoj-entrypoint-rollback-kit-v1',
            'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
            'gitHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
            'workingTree': 'uncommitted engineering work; not a Release',
            'scope': 'identical filesystem layers; ENTRYPOINT only; no business/schema version rollback',
            'files': [{'path': path.name, 'sha256': sha(path), 'bytes': path.stat().st_size} for path in sorted(package.iterdir())]}
(package / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
allowed = {'rollback.py', 'backup.py', 'README.md', 'LICENSE', 'NOTICE', 'manifest.json'}
assert {path.name for path in package.iterdir()} == allowed
archive = out / (name + '.zip')
with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as bundle:
    for path in sorted(package.iterdir()):
        bundle.write(path, path.name)
unpacked = out / 'unpacked-check'
with zipfile.ZipFile(archive) as bundle:
    assert set(bundle.namelist()) == allowed and bundle.testzip() is None
    bundle.extractall(unpacked)  # Exact fixed allowlist, created above; no arbitrary archive.
for item in manifest['files']:
    assert sha(unpacked / item['path']) == item['sha256']
assert sha(unpacked / 'manifest.json') == sha(package / 'manifest.json')
for script in ['backup.py', 'rollback.py']:
    ast.parse((unpacked / script).read_text(encoding='utf-8'))
for action in [[], ['plan'], ['apply']]:
    subprocess.run([sys.executable, '-B', str(unpacked / 'rollback.py'), *action, '--help'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True, timeout=15)
result = {'status': 'PASS', 'archive': str(archive), 'sha256': sha(archive), 'bytes': archive.stat().st_size,
          'files': sorted(allowed), 'manifest': manifest,
          'verification': ['allowlist', 'CRC', 'extracted hashes', 'two Python ASTs', 'three CLI help routes']}
(out / 'kit-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(out / (archive.name + '.sha256')).write_text(result['sha256'] + '  ' + archive.name + '\n', encoding='ascii')
print(json.dumps({key: result[key] for key in ['status', 'archive', 'sha256', 'bytes']}, ensure_ascii=False))
