"""Append one constrained forwarding key, preserving all existing SSH access."""
import base64
import hashlib
import json
import os
from pathlib import Path
import pwd
import sys

line=Path(sys.argv[1]).read_text().strip()
parts=line.split()
if len(parts)!=3 or parts[0]!='ssh-ed25519' or parts[2]!='argus-amqp-tunnel':raise SystemExit('dedicated public key required')
blob=base64.b64decode(parts[1],validate=True)
if len(blob)!=51 or blob[:15]!=b'\x00\x00\x00\x0bssh-ed25519':raise SystemExit('invalid public key encoding')
target=Path('/home/ubuntu/.ssh/authorized_keys')
if target.is_symlink() or not target.is_file():raise SystemExit('existing regular authorized_keys required')
existing=target.read_text()
if parts[1] not in existing:
    options='restrict,port-forwarding,permitopen="127.0.0.1:25674",command="/bin/false"'
    with target.open('a') as stream:stream.write(('\n' if existing and not existing.endswith('\n') else '')+options+' '+line+'\n')
    account=pwd.getpwnam('ubuntu');os.chown(target,account.pw_uid,account.pw_gid);os.chmod(target,0o600)
print(json.dumps({'dedicatedKeyAuthorized':True,'keySha256':base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip('='),'shellDisabled':True,'localForwardTargetRestricted':True}))
