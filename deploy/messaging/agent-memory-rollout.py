"""Metrics-only Agent upgrade. Keep credentials, node/store identity and task records.

Refuses restart with PENDING/RUNNING Inbox records. Does not execute Docker actions.
"""
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import urllib.request

UNIT='argus-agent.service'
ROOT=Path('/opt/argus/agent/releases/host-memory-20261019')
OVERRIDE=Path('/etc/systemd/system/argus-agent.service.d/zz-host-memory-20261019.conf')
def call(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
pid=call(['systemctl','show',UNIT,'--property=MainPID','--value'])
if pid=='0':raise SystemExit('Agent must be running before rollout')
args=[part.decode() for part in Path('/proc/'+pid+'/cmdline').read_bytes().split(b'\0') if part]
env=dict(value.split('=',1) for value in Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in value)
working=Path(call(['systemctl','show',UNIT,'--property=WorkingDirectory','--value']))
cfg_arg=next((arg for arg in args if arg.startswith('--config=')),None)
if cfg_arg is None:raise SystemExit('explicit Agent configuration required')
cfg=Path(cfg_arg.split('=',1)[1]);props={}
for line in cfg.read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        key,value=line.split('=',1);props[key.strip()]=value.strip()
directory=Path(env.get('ARGUS_AGENT_TASK_DIR',props.get('task.directory','agent/data/task-inbox')))
if not directory.is_absolute():directory=working/directory
def inbox_facts():
    facts={}
    if directory.exists():
        for path in directory.iterdir():
            if path.suffix not in ('.task','.resolution') and path.name!='store.id':continue
            if path.suffix=='.task':
                raw=path.read_bytes();payload=raw[8:-32]
                if hashlib.sha256(payload).digest()!=raw[-32:]:raise RuntimeError('Inbox checksum mismatch')
                if json.loads(payload).get('status') in ('PENDING','RUNNING'):raise RuntimeError('in-flight command exists; metrics rollout must wait')
            facts[path.name]=sha(path)
    return facts
before_files=inbox_facts();before_cfg=sha(cfg)
token=env.get('ARGUS_AGENT_AUTH_TOKEN',props.get('auth.token',''))
port=int(env.get('ARGUS_AGENT_PORT',props.get('server.port','8090')))
def health():
    req=urllib.request.Request('http://127.0.0.1:'+str(port)+'/api/agent/health',headers={'Authorization':'Bearer '+token})
    with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
before=health()
if sys.argv[1]=='inspect':
    print(json.dumps({'inFlightRecords':0,'retainedInboxFiles':len(before_files),'configured':True,'controlEnabled':before.get('controlEnabled')}));raise SystemExit(0)
if sys.argv[1]!='deploy':raise SystemExit('unknown action')
if ROOT.exists() or OVERRIDE.exists():raise SystemExit('existing metrics release; inspect rather than overwrite')
meta=json.loads(Path('/tmp/argus-agent-memory.json').read_text());source=Path('/tmp/argus-agent-memory.jar')
if sha(source)!=meta['sha256']:raise SystemExit('Agent candidate checksum mismatch')
ROOT.mkdir(parents=True,exist_ok=False);os.chmod(ROOT,0o755)
destination=ROOT/'argus-agent.jar';destination.write_bytes(source.read_bytes());os.chmod(destination,0o644)
(ROOT/'previous-unit.txt').write_bytes(subprocess.check_output(['systemctl','cat',UNIT]));os.chmod(ROOT/'previous-unit.txt',0o600)
vm=[]
for arg in args[1:]:
    if arg in ('-cp','-classpath','-jar'):break
    if arg.startswith(('-Xms','-Xmx')) or arg=='-Dfile.encoding=UTF-8':vm.append(arg)
    else:raise RuntimeError('unreviewed Agent JVM flag')
OVERRIDE.parent.mkdir(parents=True,exist_ok=True)
OVERRIDE.write_text('[Service]\nExecStart=\nExecStart='+shlex.join([args[0],*vm,'-cp',str(destination),'com.argus.agent.AgentApplication',cfg_arg])+'\n');os.chmod(OVERRIDE,0o644)
report={'scope':'PRODUCTION_AGENT_MEMORY_METRIC_CORRECTION','passed':False,'sourceCommit':meta['sourceCommit'],'artifactSha256':meta['sha256']}
try:
    call(['systemctl','daemon-reload']);call(['systemctl','restart',UNIT]);end=time.monotonic()+40;after=None
    while time.monotonic()<end:
        try:
            after=health()
            if after.get('status') in ('UP','ONLINE'):break
        except (OSError,ValueError):pass
        time.sleep(.5)
    else:raise RuntimeError('new Agent not healthy')
    for key in ('nodeId','storeId','executionMode','controlEnabled','reviewEnabled'):
        if before.get(key)!=after.get(key):raise RuntimeError('Agent identity/capability changed: '+key)
    if before_cfg!=sha(cfg) or before_files!=inbox_facts():raise RuntimeError('configuration or Inbox changed')
    proc={row.split(':',1)[0]:int(row.split()[1])*1024 for row in Path('/proc/meminfo').read_text().splitlines() if row.startswith(('MemTotal:','MemAvailable:'))}
    if after.get('hostMemoryBytes') is None:raise RuntimeError('Linux memory metric unavailable after correction')
    report.update(passed=True,configurationUnchanged=True,inboxUnchanged=True,identityAndCapabilitiesUnchanged=True,
        retainedInboxFiles=len(before_files),beforeMemoryPercent=before['hostMemoryBytes']*100/before['hostMemoryTotalBytes'],
        afterMemoryPercent=after['hostMemoryBytes']*100/after['hostMemoryTotalBytes'],kernelAvailablePercent=proc['MemAvailable']*100/proc['MemTotal'])
except Exception:
    OVERRIDE.unlink(missing_ok=True);call(['systemctl','daemon-reload']);call(['systemctl','restart',UNIT]);raise
finally:
    (ROOT/'result.json').write_text(json.dumps(report,indent=2));os.chmod(ROOT/'result.json',0o600)
print(json.dumps(report))
