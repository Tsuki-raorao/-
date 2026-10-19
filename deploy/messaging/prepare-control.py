"""Prepare, but do not activate, application messaging configuration on control host.

The bootstrap JSON is private and contains only broker credentials, peer host and
already trusted known_hosts lines. The long-lived cloud access key is never copied.
"""
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path('/etc/argus/messaging')
data=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8-sig'))
ipaddress.ip_address(data['host'])
for name in ('user','password','vhost'):
    if not data[name] or '\n' in data[name] or '\r' in data[name]:raise SystemExit('invalid bootstrap field')
if not data['knownHosts'].strip() or any(not row.startswith(('|1|',data['host']+' ')) for row in data['knownHosts'].splitlines() if row.strip()):
    raise SystemExit('trusted peer host key required')
ROOT.mkdir(parents=True,exist_ok=True);os.chmod(ROOT,0o700)
key=ROOT/'tunnel_ed25519'
if not key.exists():subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-C','argus-amqp-tunnel','-f',str(key)],check=True,capture_output=True)
os.chmod(key,0o600)
values={
    'tunnel.env':'ARGUS_MQ_TUNNEL_HOST='+data['host']+'\n',
    'known_hosts':data['knownHosts'].strip()+'\n',
    'client.env':'ARGUS_MQ_ENABLED=true\nARGUS_MONITORING_ENABLED=true\nSPRING_RABBITMQ_HOST=127.0.0.1\nSPRING_RABBITMQ_PORT=25674\nSPRING_RABBITMQ_USERNAME='+data['user']+'\nSPRING_RABBITMQ_PASSWORD='+data['password']+'\nSPRING_RABBITMQ_VIRTUAL_HOST='+data['vhost']+'\nSPRING_RABBITMQ_CONNECTION_TIMEOUT=2s\n',
}
for name,value in values.items():
    target=ROOT/name
    if target.exists() and target.read_text()!=value:raise SystemExit('existing messaging config differs; inspect before replacing')
    target.write_text(value);os.chmod(target,0o600)
print(json.dumps({'keyReady':True,'peerHostKeyPinned':True,'clientConfigPrepared':True,'applicationConfigurationChanged':False}))
