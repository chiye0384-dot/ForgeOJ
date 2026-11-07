#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
import argparse
import hashlib
import json
import os
import pathlib
import re
import sys
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('instance')
parser.add_argument('--isolated', action='store_true')
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
try:
    manifest = json.loads((root / 'package-manifest.json').read_text(encoding='utf-8-sig'))
    for item in manifest['files']:
        path = (root / item['path']).resolve()
        if not path.is_relative_to(root) or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('Package file mismatch')
    config = {}
    for line in pathlib.Path(args.instance).read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            if key in config or not re.fullmatch('[A-Z][A-Z0-9_]*', key):
                raise ValueError('Invalid config key')
            config[key] = value
    required = ['COMPOSE_PROJECT_NAME', 'FORGEOJ_DOMAIN', 'FORGEOJ_PRIVATE_DIR', 'FORGEOJ_DOCKER_SOCKET_GID', 'FORGEOJ_BIND_IP', 'FORGEOJ_API_IMAGE', 'FORGEOJ_WORKER_IMAGE', 'FORGEOJ_WEB_IMAGE']
    if any(not config.get(key) for key in required):
        raise ValueError('Missing required config')
    if not re.fullmatch('[a-z0-9][a-z0-9-]{0,62}', config['COMPOSE_PROJECT_NAME']):
        raise ValueError('Invalid project')
    domain = config['FORGEOJ_DOMAIN']
    if not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?', domain):
        raise ValueError('Invalid domain')
    for key in ['FORGEOJ_API_IMAGE', 'FORGEOJ_WORKER_IMAGE', 'FORGEOJ_WEB_IMAGE']:
        if not re.fullmatch(r'(?:sha256:|[a-z0-9./:_-]+@sha256:)[a-f0-9]{64}', config[key]):
            raise ValueError('Application images must be immutable')
    port = int(config.get('FORGEOJ_HTTPS_PORT', '443'))
    if not 1 <= port <= 65535 or config.get('FORGEOJ_HTTPS_SUFFIX', '') != ('' if port == 443 else ':' + str(port)):
        raise ValueError('HTTPS port and suffix mismatch')
    if not config['FORGEOJ_DOCKER_SOCKET_GID'].isdigit():
        raise ValueError('Invalid Docker socket group')
    private = pathlib.Path(config['FORGEOJ_PRIVATE_DIR']).resolve()
    if private.is_relative_to(root):
        raise ValueError('Private directory must be outside package')
    names = ['root-password', 'migrator-password', 'api-password', 'worker-password', 'rabbit-password', 'redis-password', 'jwt-secret', 'admin-jwt-secret', 'search-admin-password', 'search-password', 'monitoring-token', 'grafana-password', 'grafana-key']
    values = [(private / 'secrets' / name).read_text() for name in names]
    if any(not re.fullmatch('[a-f0-9]{64}', value) for value in values) or len(set(values)) != len(values):
        raise ValueError('Secrets must be distinct random 256-bit values')
    for relative in ['tls/fullchain.pem', 'tls/privkey.pem', 'search/search.crt', 'search/search.key', 'search/trust.p12']:
        if not (private / relative).is_file() or not (private / relative).stat().st_size:
            raise ValueError('TLS material missing')
    if os.name == 'posix':
        result = subprocess.run(['bash', str(root / 'scripts/private-permissions.sh'), str(private)], capture_output=True)
        if result.returncode:
            raise ValueError('Private POSIX permissions invalid')
    if args.isolated:
        if config['FORGEOJ_BIND_IP'] != '127.0.0.1' or not domain.endswith('.test') or config.get('FORGEOJ_MAIL_MODE') != 'disabled':
            raise ValueError('Isolated mode requires loopback, .test domain and disabled mail')
    else:
        if domain.endswith(('.invalid', '.test')) or config.get('FORGEOJ_MAIL_MODE') != 'smtp':
            raise ValueError('Production requires actual domain and SMTP')
        if any(not config.get(key) for key in ['FORGEOJ_SMTP_HOST', 'FORGEOJ_SMTP_FROM', 'FORGEOJ_SMTP_USERNAME']):
            raise ValueError('SMTP configuration missing')
        if config.get('FORGEOJ_SMTP_TLS') not in ['starttls', 'implicit'] or not (private / 'secrets/smtp-password').read_text().strip():
            raise ValueError('SMTP TLS or private password missing')
    print(json.dumps({'status': 'PASS', 'isolated': args.isolated, 'files': len(manifest['files']), 'compiledInputs': manifest['compiledInputs']}))
except Exception as failure:
    # Never print values or paths from untrusted/private configuration.
    print('Preflight refused: package integrity, configuration or private files invalid.', file=sys.stderr)
    sys.exit(1)
