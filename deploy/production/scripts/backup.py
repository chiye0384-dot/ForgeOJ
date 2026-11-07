#!/usr/bin/env python3
# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Maintenance-only encrypted MySQL backup/restore. No plaintext files or automatic cutover."""
import argparse
import contextlib
import datetime as dt
import hashlib
import io
import json
import os
import pathlib
import re
import subprocess
import sys
import tarfile
import threading
import uuid

CRYPTO_IMAGE = 'eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438'
MAX_SQL = 60 * 1024 * 1024
MAX_ARCHIVE = 64 * 1024 * 1024
MAX_CIPHER = 65 * 1024 * 1024

class BackupRefused(Exception):
    """Only fixed, non-private diagnostic messages may use this type."""

def require(condition, message):
    if not condition:
        raise BackupRefused(message)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def command(arguments, data=None, limit=MAX_SQL, timeout=180):
    # Binary pipes avoid PowerShell's text-redirection encodings. All output stays in RAM.
    child = subprocess.Popen(arguments, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    timer = threading.Timer(timeout, child.kill)  # Only the exact child handle created here.
    timer.start()
    writer_error = []
    def write_input():
        try:
            child.stdin.write(data)
            child.stdin.close()
        except (OSError, BrokenPipeError) as error:
            writer_error.append(error)
    writer = threading.Thread(target=write_input, daemon=True) if data is not None else None
    try:
        if writer:
            writer.start()
        output = bytearray()
        while True:
            block = child.stdout.read(65536)
            if not block:
                break
            output.extend(block)
            require(len(output) <= limit, 'Command output exceeded the supported backup size')
        code = child.wait()
        if writer:
            writer.join(timeout=5)
        require(code == 0 and not writer_error, 'Backup command failed; private diagnostic output withheld')
        return bytes(output)
    finally:
        timer.cancel()
        if child.poll() is None:
            child.kill()
        child.wait()
        child.stdout.close()
        if child.stdin and not child.stdin.closed:
            child.stdin.close()

def crypto(docker, arguments, mounts=(), data=None, limit=MAX_CIPHER):
    identity = 'crypto-' + uuid.uuid4().hex[:12]
    owner = str(os.getuid()) + ':' + str(os.getgid()) if os.name == 'posix' else '0:0'
    cmd = [docker, 'run', '--rm', '-i', '--network', 'none', '--read-only', '--user', owner,
           '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--pids-limit', '32', '--memory', '256m',
           '--label', 'com.forgeoj.backup-helper=' + identity]
    for source, target, read_only in mounts:
        cmd += ['--mount', 'type=bind,src=' + str(pathlib.Path(source).resolve()) + ',dst=' + target + (',readonly' if read_only else '')]
    return command(cmd + ['--entrypoint', 'openssl', CRYPTO_IMAGE] + arguments, data, limit)

@contextlib.contextmanager
def instance_lock(path):
    lock = pathlib.Path(str(path) + '.start.lock')
    require(not lock.is_symlink(), 'Instance lock cannot be a symlink')
    with open(lock, 'a+b') as stream:
        if os.name == 'posix':
            import fcntl
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        else:
            import msvcrt
            stream.seek(0)
            if not stream.read(1):
                stream.write(b'0'); stream.flush()
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        try:
            yield
        finally:
            if os.name == 'posix':
                fcntl.flock(stream, fcntl.LOCK_UN)
            else:
                stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)

class Instance:
    def __init__(self, package, instance, docker):
        self.package = pathlib.Path(package).resolve()
        self.instance = pathlib.Path(instance).resolve()
        self.docker = docker
        self.manifest = json.loads((self.package / 'package-manifest.json').read_text(encoding='utf-8-sig'))
        for item in self.manifest['files']:
            file = (self.package / item['path']).resolve()
            require(file.is_relative_to(self.package) and sha(file.read_bytes()) == item['sha256'], 'Package integrity failed')
        self.config = {}
        for line in self.instance.read_text(encoding='utf-8-sig').splitlines():
            if line.strip() and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                require(key not in self.config, 'Duplicate instance setting')
                self.config[key] = value
        self.project = self.config['COMPOSE_PROJECT_NAME']
        require(re.fullmatch('[a-z0-9][a-z0-9-]{0,62}', self.project), 'Invalid instance identity')
        self.compose = [docker, 'compose', '--project-name', self.project, '--env-file', str(self.instance), '-f', str(self.package / 'compose.yml')]
        ids = command(self.compose + ['ps', '-q', 'mysql'], limit=4096).decode().split()
        require(len(ids) == 1, 'Exactly one running target MySQL is required')
        info = json.loads(command([docker, 'inspect', ids[0]], limit=1024*1024))[0]
        require(info['Config']['Labels'].get('com.docker.compose.project') == self.project, 'MySQL ownership mismatch')
        require(info['State'].get('Health', {}).get('Status') == 'healthy', 'MySQL is not healthy')
        self.mysql_id = ids[0]

    def quiet(self):
        ids = command(self.compose + ['ps', '-q', 'api', 'worker', 'web', 'migrate', 'admin', 'search-setup'], limit=8192)
        require(not ids.strip(), 'Maintenance window required: stop instance writers and maintenance jobs explicitly')

    def sql(self, sql, role='migrator', database=True):
        require(role in ['migrator', 'api', 'worker'], 'Invalid database role')
        # Only this read-only file enters the command; its value is never argv or Docker Config.Env.
        shell = 'export MYSQL_PWD=$(cat /run/secrets/' + role + '-password); exec mysql --connect-timeout=5 --binary-mode --local-infile=0 --default-character-set=utf8mb4 --batch --raw --skip-column-names -uforgeoj_' + role + (' forgeoj' if database else '')
        return command(self.compose + ['exec', '-T', 'mysql', 'sh', '-c', shell], sql.encode(), limit=4*1024*1024).decode('utf-8')

    def server_uuid(self):
        return self.sql('SELECT @@server_uuid;').strip()

    def tables(self):
        rows = self.sql("SELECT TABLE_NAME,TABLE_TYPE,COALESCE(ENGINE,'') FROM information_schema.tables WHERE table_schema='forgeoj' ORDER BY TABLE_NAME;")
        names = []
        for row in rows.splitlines():
            name, kind, engine = row.split('\t')
            require(re.fullmatch('[A-Za-z0-9_]+', name) and kind == 'BASE TABLE' and engine == 'InnoDB', 'Only InnoDB base tables are supported')
            names.append(name)
        unsupported = self.sql("SELECT (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='forgeoj')+(SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='forgeoj')+(SELECT COUNT(*) FROM information_schema.events WHERE event_schema='forgeoj');").strip()
        require(unsupported == '0', 'Routines, triggers and events require a separate reviewed backup strategy')
        return names

    def dump(self, data_only=False):
        options = '--default-character-set=utf8mb4 --no-tablespaces --set-gtid-purged=OFF --single-transaction --quick --hex-blob --skip-comments --skip-add-drop-table --skip-add-locks --skip-disable-keys --skip-triggers --skip-routines --skip-events --complete-insert --skip-extended-insert --order-by-primary --skip-lock-tables'
        if data_only:
            options += ' --no-create-info'
        shell = 'export MYSQL_PWD=$(cat /run/secrets/migrator-password); exec mysqldump -uforgeoj_migrator ' + options + ' forgeoj'
        return command(self.compose + ['exec', '-T', 'mysql', 'sh', '-c', shell])

    def grants(self):
        result = {}
        for role in ['api', 'worker']:
            statements = self.sql('SHOW GRANTS;', role, database=False).splitlines()
            result[role] = sorted(validate_grants(statements, role))
        return result

    def snapshot(self):
        names = self.tables()
        require(names and 'flyway_schema_history' in names, 'Versioned schema required')
        counts_sql = ' UNION ALL '.join("SELECT '" + name + "',COUNT(*) FROM `" + name + '`' for name in names)
        counts = {row.split('\t')[0]: int(row.split('\t')[1]) for row in self.sql(counts_sql + ';').splitlines()}
        return {'counts': counts, 'dataSha256': sha(self.dump(True)), 'grants': self.grants(),
                'migrations': self.sql('SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;').splitlines()}

def validate_grants(statements, role):
    target = r'`forgeoj_' + role + r'`@`%`'
    privilege = r'(?:SELECT|INSERT|UPDATE|DELETE)(?: \(`[A-Za-z0-9_]+`(?:, `[A-Za-z0-9_]+`)*\))?'
    accepted = []
    for statement in statements:
        if re.fullmatch(r'GRANT USAGE ON \*\.\* TO ' + target, statement):
            continue
        require(re.fullmatch(r'GRANT ' + privilege + r'(?:, ' + privilege + r')* ON `forgeoj`\.`[A-Za-z0-9_]+` TO ' + target, statement), 'Unsupported or excessive runtime grant')
        accepted.append(statement)
    return accepted

def create_key(args):
    directory = pathlib.Path(args.directory).resolve()
    require(not directory.exists(), 'Refusing to replace an existing key directory')
    directory.mkdir(parents=True, mode=0o700)
    try:
        crypto(args.docker, ['req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-days', '3650', '-subj', '/CN=ForgeOJ offline backup recipient',
                             '-keyout', '/offline/private.pem', '-out', '/offline/recipient.pem'], [(directory, '/offline', False)])
        os.chmod(directory / 'private.pem', 0o600)
        os.chmod(directory / 'recipient.pem', 0o444)
    except Exception:
        # Never delete an existing/offline key on failure; caller inspects this new directory.
        raise ValueError('Key creation failed; inspect the newly created offline directory') from None
    print(json.dumps({'status': 'KEY_CREATED', 'privateKey': 'Keep offline; do not copy into the application or backup server', 'publicCertificate': str(directory / 'recipient.pem')}))

def make_backup(args):
    source = Instance(args.package, args.instance, args.docker)
    output = pathlib.Path(args.output).resolve()
    require(not output.exists() and not output.with_suffix(output.suffix + '.receipt.json').exists(), 'Refusing to replace a backup or receipt')
    require(not output.is_relative_to(source.package), 'Backup must be outside the deployment package')
    recipient = pathlib.Path(args.recipient).resolve()
    crypto(args.docker, ['x509', '-in', '/recipient.pem', '-checkend', '0', '-noout'], [(recipient, '/recipient.pem', True)], limit=4096)
    with instance_lock(source.instance):
        source.quiet()
        before = source.snapshot()
        sql = source.dump()
        require(source.snapshot() == before, 'Database changed during backup; no artifact committed')
        metadata = {'format': 'forgeoj-mysql-backup-v1', 'createdAt': dt.datetime.now(dt.timezone.utc).isoformat(),
                    'project': source.project, 'serverUuid': source.server_uuid(), 'apiInputSha256': source.manifest['apiInputSha256'],
                    'sqlSha256': sha(sql), 'snapshot': before, 'crypto': 'CMS AES-256-GCM / RSA-OAEP-SHA256',
                    'consistency': 'maintenance; InnoDB single-transaction; no DDL or external writers', 'scope': 'MySQL facts and runtime grants only; no RabbitMQ queue or instance secrets'}
        payload = io.BytesIO()
        with tarfile.open(fileobj=payload, mode='w') as tar:
            for name, data in [('metadata.json', json.dumps(metadata, sort_keys=True).encode()), ('database.sql', sql)]:
                info = tarfile.TarInfo(name); info.size = len(data); info.mode = 0o600
                tar.addfile(info, io.BytesIO(data))
        require(payload.tell() <= MAX_ARCHIVE, 'Backup archive exceeded supported size')
        ciphertext = crypto(args.docker, ['cms', '-encrypt', '-binary', '-stream', '-aes-256-gcm', '-outform', 'DER',
                                          '-recip', '/recipient.pem', '-keyopt', 'rsa_padding_mode:oaep', '-keyopt', 'rsa_oaep_md:sha256'],
                            [(recipient, '/recipient.pem', True)], payload.getvalue())
        output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        # Exclusive publication: no plaintext temporary file and no overwrite of prior copies.
        with output.open('xb') as stream:
            stream.write(ciphertext)
        os.chmod(output, 0o600)
        receipt = {'status': 'LOCAL_ENCRYPTED', 'createdAt': metadata['createdAt'], 'sha256': sha(ciphertext), 'bytes': len(ciphertext),
                   'artifact': output.name, 'offsiteConfirmed': False, 'note': 'A checksum is not a digital signature; preserve this receipt through a trusted independent channel'}
        with output.with_suffix(output.suffix + '.receipt.json').open('x', encoding='utf-8') as stream:
            json.dump(receipt, stream, indent=2)
        os.chmod(output.with_suffix(output.suffix + '.receipt.json'), 0o600)
    print(json.dumps(receipt))

def restore_backup(args):
    artifact = pathlib.Path(args.backup).resolve()
    require(artifact.stat().st_size <= MAX_CIPHER and re.fullmatch('[a-f0-9]{64}', args.sha256), 'Backup size or expected digest invalid')
    ciphertext = artifact.read_bytes()
    require(sha(ciphertext) == args.sha256, 'Backup checksum does not match the trusted receipt')
    plaintext = crypto(args.docker, ['cms', '-decrypt', '-binary', '-inform', 'DER', '-recip', '/recipient.pem', '-inkey', '/private.pem'],
                       [(args.recipient, '/recipient.pem', True), (args.private_key, '/private.pem', True)], ciphertext, MAX_ARCHIVE)
    # OpenSSL must authenticate the entire message before a byte can reach MySQL.
    with tarfile.open(fileobj=io.BytesIO(plaintext), mode='r:') as tar:
        entries = tar.getmembers()
        require(len(entries) == 2 and {entry.name for entry in entries} == {'metadata.json', 'database.sql'} and all(entry.isfile() for entry in entries), 'Unsupported archive contents')
        require(tar.getmember('metadata.json').size <= 2*1024*1024 and tar.getmember('database.sql').size <= MAX_SQL, 'Invalid archive sizes')
        metadata = json.load(tar.extractfile('metadata.json'))
        sql = tar.extractfile('database.sql').read()
    require(metadata['format'] == 'forgeoj-mysql-backup-v1' and sha(sql) == metadata['sqlSha256'], 'Backup payload mismatch')
    grants = metadata['snapshot']['grants']
    require(set(grants) == {'api', 'worker'}, 'Unexpected runtime roles')
    for role, statements in grants.items():
        validate_grants(statements, role)
    target = Instance(args.package, args.instance, args.docker)
    with instance_lock(target.instance):
        target.quiet()
        require(target.project != metadata['project'] and target.server_uuid() != metadata['serverUuid'], 'Restoring onto the source instance is forbidden')
        require(target.manifest['apiInputSha256'] == metadata['apiInputSha256'], 'Restore requires the exact compatible schema artifact')
        require(not target.tables() and target.grants() == {'api': [], 'worker': []}, 'Restore target must be a new empty database with unprivileged runtime roles')
        try:
            target.sql(sql.decode('utf-8'))
        except BackupRefused:
            raise BackupRefused('SQL restore failed; the isolated target is incomplete and must remain stopped') from None
        statements = [statement + ';' for role in ['api', 'worker'] for statement in grants[role]]
        target.sql('\n'.join(statements))
        require(target.snapshot() == metadata['snapshot'], 'Restored contents or permissions do not match; leave this isolated target stopped for inspection')
    print(json.dumps({'status': 'RESTORED_AND_MATCHED', 'tables': len(metadata['snapshot']['counts']),
                      'sourceOverwritten': False, 'servicesStarted': False,
                      'scope': 'isolated MySQL data/schema/runtime grants; production cutover, sessions and RabbitMQ reconciliation require separate approval'}))

def retention_plan(args):
    directory = pathlib.Path(args.directory).resolve()
    receipts = []
    for path in directory.glob('*.receipt.json'):
        require(not path.is_symlink(), 'Receipt symlinks are not supported')
        value = json.loads(path.read_text())
        artifact = directory / value['artifact']
        require(artifact.parent == directory and artifact.is_file() and not artifact.is_symlink(), 'Invalid receipt artifact')
        require(artifact.stat().st_size <= MAX_CIPHER and sha(artifact.read_bytes()) == value['sha256'], 'Receipt checksum mismatch')
        when = dt.datetime.fromisoformat(value['createdAt'])
        require(when.tzinfo is not None, 'Receipt timestamp must include its timezone')
        receipts.append((when.astimezone(dt.timezone.utc), artifact.name))
    receipts.sort(reverse=True)
    days, weeks, keep = set(), set(), set()
    for when, name in receipts:
        day, week = when.date(), when.isocalendar()[:2]
        if day not in days and len(days) < 7:
            days.add(day); keep.add(name)
        if week not in weeks and len(weeks) < 4:
            weeks.add(week); keep.add(name)
    print(json.dumps({'status': 'PLAN_ONLY', 'keep': sorted(keep), 'deletionCandidates': sorted({name for _, name in receipts} - keep),
                      'deleted': 0, 'offsiteConfirmed': False, 'rule': 'latest copy in 7 distinct UTC days and 4 distinct ISO weeks; never auto-delete without a confirmed offsite policy'}))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--docker', default='docker')
    sub = parser.add_subparsers(dest='action', required=True)
    key = sub.add_parser('keygen'); key.add_argument('--directory', required=True)
    for name in ['backup', 'restore']:
        part = sub.add_parser(name)
        part.add_argument('--package', required=True); part.add_argument('--instance', required=True); part.add_argument('--recipient', required=True)
        if name == 'backup':
            part.add_argument('--output', required=True)
        else:
            part.add_argument('--backup', required=True); part.add_argument('--private-key', required=True); part.add_argument('--sha256', required=True)
    plan = sub.add_parser('retention-plan'); plan.add_argument('--directory', required=True)
    args = parser.parse_args()
    try:
        {'keygen': create_key, 'backup': make_backup, 'restore': restore_backup, 'retention-plan': retention_plan}[args.action](args)
    except BackupRefused as failure:
        print('Backup operation refused: ' + str(failure), file=sys.stderr)
        return 1
    except Exception:
        print('Backup operation refused or failed. No private command output is printed. Do not start or reuse a partially restored target.', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
