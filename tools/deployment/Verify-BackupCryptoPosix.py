# Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
"""Non-root Linux crypto helper probe; does not claim a full Linux host CLI test."""
import json
import subprocess
import sys
import uuid

docker=sys.argv[1]
image='eclipse-temurin:21.0.12_8-jdk-jammy@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438'
name='forgeoj-backup-posix-'+uuid.uuid4().hex[:12]
def call(args,data=None):
    result=subprocess.run(args,input=data,capture_output=True,timeout=120)
    if result.returncode:
        raise RuntimeError('Owned POSIX crypto probe failed; private output withheld')
    return result.stdout
call([docker,'volume','create','--label','com.forgeoj.backup-posix='+name,name])
common=[docker,'run','--rm','-i','--network','none','--read-only','--cap-drop','ALL','--label','com.forgeoj.backup-posix='+name,'--mount','type=volume,src='+name+',dst=/probe']
try:
    call(common+['--user','0:0','--cap-add','CHOWN','--entrypoint','sh',image,'-c','mkdir /probe/offline; chmod 0700 /probe/offline; chown 1000:1000 /probe/offline'])
    call(common+['--user','1000:1000','--entrypoint','openssl',image,'req','-x509','-newkey','rsa:3072','-nodes','-days','2','-subj','/CN=Owned backup POSIX probe','-keyout','/probe/offline/private.pem','-out','/probe/offline/recipient.pem'])
    modes=call(common+['--user','1000:1000','--entrypoint','sh',image,'-c','stat -c "%u:%a" /probe/offline /probe/offline/private.pem']).decode().splitlines()
    assert modes==['1000:700','1000:600']
    ciphertext=call(common+['--user','1000:1000','--entrypoint','openssl',image,'cms','-encrypt','-binary','-stream','-aes-256-gcm','-outform','DER','-recip','/probe/offline/recipient.pem','-keyopt','rsa_padding_mode:oaep','-keyopt','rsa_oaep_md:sha256'],b'owned non-root backup probe')
    plaintext=call(common+['--user','1000:1000','--entrypoint','openssl',image,'cms','-decrypt','-binary','-inform','DER','-recip','/probe/offline/recipient.pem','-inkey','/probe/offline/private.pem'],ciphertext)
    assert plaintext==b'owned non-root backup probe'
    print(json.dumps({'status':'PASS','uid':1000,'directoryMode':'0700','privateKeyMode':'0600','cryptoRoundtrip':True,'scope':'Linux engine helper filesystem; not a fresh Linux host CLI verification'}))
finally:
    call([docker,'volume','rm',name])
