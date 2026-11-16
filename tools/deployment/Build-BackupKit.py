#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Build and verify an allowlisted, secret-free standalone backup tool kit."""
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
name = 'forgeoj-backup-tools-' + stamp + '-' + uuid.uuid4().hex[:8]
out = repo / 'target' / name
package = out / 'package'
package.mkdir(parents=True)
shutil.copyfile(repo / 'deploy/production/scripts/backup.py', package / 'backup.py')
readme = (repo / 'docs/archive/M5-BACKUP-RESTORE.md').read_text(encoding='utf-8')
readme = readme.replace('[M5备份验收](M5-BACKUP-VALIDATION.md)', '仓库文档 docs/archive/M5-BACKUP-VALIDATION.md（验收记录不随工具包分发）')
(package / 'README.md').write_text(readme, encoding='utf-8')
shutil.copyfile(repo / 'LICENSE', package / 'LICENSE')
(package / 'NOTICE').write_text(
    'ForgeOJ backup tools\nCopyright 2026 池也\n\n'
    'ForgeOJ-authored Python and documentation: Apache-2.0.\n'
    'No third-party runtime or binaries are distributed in this kit.\n'
    'Docker, MySQL and OpenSSL in the pinned Temurin image remain upstream software.\n'
    'Use the deployment package notices for those separately obtained runtimes.\n', encoding='utf-8')
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {
    'format': 'forgeoj-backup-tools-v1', 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
    'gitHead': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip(),
    'workingTree': 'uncommitted engineering work; not a Release',
    'scope': 'manual maintenance-window MySQL encryption and isolated empty-target restore; no scheduler/offsite/cutover',
    'files': [{'path': path.name, 'sha256': digest(path), 'bytes': path.stat().st_size}
              for path in sorted(package.iterdir())],
}
(package / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
allowed = {'backup.py', 'README.md', 'LICENSE', 'NOTICE', 'manifest.json'}
assert {path.name for path in package.iterdir()} == allowed
archive = out / (name + '.zip')
with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED) as bundle:
    for path in sorted(package.iterdir()):
        bundle.write(path, path.name)
unpacked = out / 'unpacked-check'
with zipfile.ZipFile(archive) as bundle:
    assert set(bundle.namelist()) == allowed
    assert bundle.testzip() is None
    bundle.extractall(unpacked)  # Exact fixed allowlist; no externally supplied archive.
for item in manifest['files']:
    assert digest(unpacked / item['path']) == item['sha256']
assert digest(unpacked / 'manifest.json') == digest(package / 'manifest.json')
ast.parse((unpacked / 'backup.py').read_text(encoding='utf-8'))
for action in [[], ['keygen'], ['backup'], ['restore'], ['retention-plan']]:
    subprocess.run([sys.executable, str(unpacked / 'backup.py'), *action, '--help'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True, timeout=15)
result = {'status': 'PASS', 'archive': str(archive), 'sha256': digest(archive),
          'bytes': archive.stat().st_size, 'files': sorted(allowed),
          'verification': ['exact allowlist', 'zip CRC', 'extracted SHA256', 'Python AST', 'five CLI help routes'],
          'manifest': manifest}
(out / 'kit-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(out / (archive.name + '.sha256')).write_text(result['sha256'] + '  ' + archive.name + '\n', encoding='ascii')
print(json.dumps({key: result[key] for key in ['status', 'archive', 'sha256', 'bytes']}, ensure_ascii=False))
