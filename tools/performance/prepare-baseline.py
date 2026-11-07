#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Apply explicit benchmark limits only to a NEW ignored deployment package."""
import hashlib
import json
import pathlib
import re
import sys

repo = pathlib.Path(__file__).resolve().parents[2]
package = (repo / sys.argv[1]).resolve()
run = package.parent
assert package.name == 'package' and run.is_relative_to(repo / 'target')
assert re.fullmatch(r'forgeoj-deployment-\d{8}-\d{6}-[a-f0-9]{8}', run.name)
assert not (run / 'private').exists() and not (run / 'performance').exists()
limits = {'api': 2, 'worker': 2, 'mysql': 2, 'search': 2, 'rabbitmq': 1, 'redis': 1, 'web': 1, 'prometheus': 0.5, 'grafana': 0.5}
file = package / 'compose.yml'
body = file.read_text(encoding='utf-8')
for service, cpus in limits.items():
    key = '  ' + service + ':\n'
    assert body.count(key) == 1
    body = body.replace(key, key + '    cpus: ' + str(cpus) + '\n')
file.write_text(body, encoding='utf-8')
manifest_path = package / 'package-manifest.json'
manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
item = [row for row in manifest['files'] if row['path'] == 'compose.yml']
assert len(item) == 1
item[0]['sha256'] = hashlib.sha256(file.read_bytes()).hexdigest()
manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
performance = run / 'performance'; performance.mkdir()
(performance / 'resource-plan.json').write_text(json.dumps({'cpus': limits, 'loadGenerator': {'cpus': 1, 'memoryMiB': 512, 'pids': 128}, 'scope': 'owned benchmark package only; no host configuration changed'}, indent=2))
print(str(run))
