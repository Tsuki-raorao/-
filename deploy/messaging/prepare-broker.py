"""Provision the dedicated persistent Broker on the approved workload host.

All credentials stay under /etc/argus/messaging (0700/0600). No public AMQP port.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time

ROOT=Path('/etc/argus/messaging')
NAME='argus-rabbitmq-prod'
VOLUME='argus-rabbitmq-prod'
SCOPE='argus-production-messaging'

def call(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def inspect(kind,name):
    result=subprocess.run(['docker',kind,'inspect',name] if kind=='volume' else ['docker','inspect',name],capture_output=True,text=True)
    return json.loads(result.stdout)[0] if result.returncode==0 else None

ROOT.mkdir(parents=True,exist_ok=True);os.chmod(ROOT,0o700)
config=ROOT/'broker.env'
if sys.argv[1:] == ['export-client-private']:
    values=dict(row.split('=',1) for row in config.read_text().splitlines())
    # This explicit command must be redirected to a private file, never terminal logs.
    print(json.dumps({'user':values['RABBITMQ_DEFAULT_USER'],'password':values['RABBITMQ_DEFAULT_PASS'],'vhost':values['RABBITMQ_DEFAULT_VHOST']}))
    raise SystemExit(0)
current=inspect('container',NAME)
volume=inspect('volume',VOLUME)
if current and current['Config']['Labels'].get('argus.scope')!=SCOPE:raise SystemExit('foreign container name')
if volume and (volume.get('Labels') or {}).get('argus.scope')!=SCOPE:raise SystemExit('foreign volume name')
if not config.exists():
    if current or volume:raise SystemExit('existing Broker data without its private bootstrap configuration; refuse reset')
    config.write_text('RABBITMQ_DEFAULT_USER=argus-prod\nRABBITMQ_DEFAULT_PASS='+secrets.token_hex(32)+'\nRABBITMQ_DEFAULT_VHOST=argus-prod\nRABBITMQ_ERLANG_COOKIE='+secrets.token_hex(32)+'\n')
    os.chmod(config,0o600)
if not volume:call(['docker','volume','create','--label','argus.scope='+SCOPE,VOLUME])
if not current:
    # Reuse the already verified image; no image pull or disk use on the control host.
    call(['docker','run','-d','--name',NAME,'--hostname','argus-mq','--label','argus.scope='+SCOPE,
          '--restart','unless-stopped','--memory','384m','--cpus','0.5','-p','127.0.0.1:25674:5672',
          '--env-file',str(config),'--mount','type=volume,source='+VOLUME+',target=/var/lib/rabbitmq',
          '--health-cmd','rabbitmq-diagnostics -q ping','--health-interval','15s','--health-timeout','10s','--health-retries','3',
          'rabbitmq:4.1-management'])
elif not current['State']['Running']:call(['docker','start',NAME])
deadline=time.monotonic()+90
while time.monotonic()<deadline:
    result=subprocess.run(['docker','exec',NAME,'rabbitmq-diagnostics','-q','ping'],capture_output=True)
    if result.returncode==0:break
    time.sleep(1)
else:raise RuntimeError('Broker did not become ready; inspect dedicated container logs')
value=inspect('container',NAME)
print(json.dumps({'container':NAME,'running':value['State']['Running'],'volume':VOLUME,
                  'hostname':value['Config']['Hostname'],'restartPolicy':value['HostConfig']['RestartPolicy']['Name'],
                  'amqpBoundToLoopback':value['HostConfig']['PortBindings']['5672/tcp'][0]['HostIp']=='127.0.0.1',
                  'managementPortPublished':bool(value['HostConfig']['PortBindings'].get('15672/tcp'))}))
