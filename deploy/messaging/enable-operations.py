"""Enable explicit production business targets without executing their actions.

Private input JSON is supplied outside Git; tokens are never printed. Separate
systemd drop-ins preserve existing config files and enable removal-only rollback.
"""
import hashlib,importlib.util,json,os,subprocess,sys,time,urllib.request
from pathlib import Path

FIXTURE='argus-acceptance-fixture'
BUSINESS={'workload':{'mc01','mc02','mc03','fight202609',FIXTURE},'control':{'code-server','open-terminal','open-webui','open-webui-old-v0.10.2'}}
ROOT=Path('/opt/argus/operations-20261019')
def run(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def private(path,text):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);os.chmod(path,0o600)
def environment(unit):
 pid=run(['systemctl','show',unit,'--property=MainPID','--value']);assert int(pid)>0
 return dict(v.split('=',1) for v in Path('/proc/'+pid+'/environ').read_bytes().decode().split('\0') if '=' in v)
def fetch(url,token):
 with urllib.request.urlopen(urllib.request.Request(url,headers={'Authorization':'Bearer '+token}),timeout=7) as r:return json.load(r)
def wait(fn):
 end=time.monotonic()+75
 while time.monotonic()<end:
  try:
   result=fn()
   if result:return result
  except (OSError,ValueError):pass
  time.sleep(.5)
 raise RuntimeError('service health timeout')
def install(unit,values,validate):
 cfg=Path('/etc/argus/operations')/(unit+'.env');drop=Path('/etc/systemd/system')/(unit+'.d')/'zzz-operations-20261019.conf'
 if cfg.exists() or drop.exists():raise RuntimeError('operations override already exists; inspect instead of overwrite')
 ROOT.mkdir(parents=True,exist_ok=True);os.chmod(ROOT,0o700)
 private(ROOT/(unit+'.before.txt'),run(['systemctl','cat',unit]))
 cfg.parent.mkdir(mode=0o700,parents=True,exist_ok=True);os.chmod(cfg.parent,0o700)
 private(cfg,''.join(k+'='+v+'\n' for k,v in values.items()))
 drop.parent.mkdir(parents=True,exist_ok=True);drop.write_text('[Service]\nEnvironmentFile='+str(cfg)+'\n')
 try:
  run(['systemctl','daemon-reload']);run(['systemctl','restart',unit]);return validate()
 except Exception:
  drop.unlink(missing_ok=True);cfg.unlink(missing_ok=True);run(['systemctl','daemon-reload']);run(['systemctl','restart',unit]);raise

action=sys.argv[1];payload=json.loads(Path(sys.argv[2]).read_text(encoding='utf-8-sig'))
if action=='agent':
 role=sys.argv[3];allowed=BUSINESS[role];unit='argus-agent.service';env=environment(unit)
 pid=run(['systemctl','show',unit,'--property=MainPID','--value'])
 args=Path('/proc/'+pid+'/cmdline').read_bytes().decode().split('\0')
 cfg=Path(next(a.split('=',1)[1] for a in args if a.startswith('--config=')))
 props=dict(line.split('=',1) for line in cfg.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))
 inbox=Path(env.get('ARGUS_AGENT_TASK_DIR',props.get('task.directory','agent/data/task-inbox')))
 if not inbox.is_absolute():inbox=Path(run(['systemctl','show',unit,'--property=WorkingDirectory','--value']))/inbox
 def facts():
  result={}
  for p in inbox.iterdir():
   if p.suffix not in ('.task','.resolution') and p.name!='store.id':continue
   raw=p.read_bytes()
   if p.suffix=='.task':
    assert hashlib.sha256(raw[8:-32]).digest()==raw[-32:]
    assert json.loads(raw[8:-32]).get('status') not in ('PENDING','RUNNING')
   result[p.name]=hashlib.sha256(raw).hexdigest()
  return result
 before_files=facts();before_cfg=hashlib.sha256(cfg.read_bytes()).hexdigest()
 assert 'store.id' in before_files
 token=env.get('ARGUS_AGENT_AUTH_TOKEN',props.get('auth.token',''))
 url='http://127.0.0.1:'+env.get('ARGUS_AGENT_PORT',props.get('server.port','8090'))+'/api/agent/health'
 before=fetch(url,token);assert before['executionMode']=='DOCKER'
 actual={r['Names'] for r in [json.loads(line) for line in run(['docker','ps','-a','--format','{{json .}}']).splitlines()]}
 assert allowed<=actual and payload['agentControlToken']!=token
 def validate():
  after=wait(lambda:fetch(url,token))
  assert after['controlEnabled'] and after['reviewEnabled']
  assert all(before[k]==after[k] for k in ('nodeId','storeId','executionMode'))
  assert facts()==before_files and hashlib.sha256(cfg.read_bytes()).hexdigest()==before_cfg
  return {'scope':'PRODUCTION_OPERATIONS_AGENT','passed':True,'role':role,'allowedTargets':sorted(allowed),'identityAndInboxPreserved':True,'controlEnabled':True,'reviewEnabled':True}
 result=install(unit,{'ARGUS_AGENT_CONTROL_ENABLED':'true','ARGUS_AGENT_CONTROL_TOKEN':payload['agentControlToken'],'ARGUS_AGENT_CONTROL_INSTANCES':','.join(sorted(allowed)),'ARGUS_AGENT_REVIEW_ENABLED':'true','ARGUS_AGENT_REVIEW_INSTANCES':','.join(sorted(allowed))},validate)
elif action=='central':
 spec=importlib.util.spec_from_file_location('release','/tmp/release-stage.py');r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
 env=r.live_env();instances=r.get('instances');nodes={n['id']:n for n in r.get('nodes')};targets=[]
 for role,allowed in BUSINESS.items():
  for name in allowed:
   matches=[i for i in instances if i.get('agentInstanceId')==name and i.get('dataSource')=='DOCKER' and nodes[i['nodeId']].get('dataSource')=='HOST']
   assert len(matches)==1,(role,name,len(matches));targets.append(matches[0])
 assert len({i['nodeId'] for i in targets})==2
 with r.MySQL(env) as db:
  for table in ('task_queue','task_resolution_queue','instance_task_locks'):assert db.query('SELECT COUNT(*) FROM '+table)=='0'
 for node in {i['nodeId'] for i in targets}:
  h=r.get('agent-gateway/nodes/'+node+'/health');assert h['controlEnabled'] and h['reviewEnabled']
 assert payload['operatorToken']!=env['ARGUS_API_ACCESS_TOKEN'] and payload['agentControlToken']!=env['ARGUS_AGENT_AUTH_TOKEN']
 ids=','.join(sorted(i['id'] for i in targets))
 values={'ARGUS_API_READ_ONLY':'false','ARGUS_API_AUTH_REQUIRED':'true','ARGUS_API_CONTROL_TOKEN':payload['operatorToken'],'ARGUS_TASK_CONTROL_ENABLED':'true','ARGUS_TASK_ALLOW_DOCKER':'true','ARGUS_TASK_ALLOWED_INSTANCE_IDS':ids,'ARGUS_AGENT_CONTROL_TOKEN':payload['agentControlToken'],'ARGUS_AGENT_GATEWAY_ALLOW_CONTROL':'true','ARGUS_TASK_REVIEW_ENABLED':'true','ARGUS_TASK_REVIEW_ALLOWED_INSTANCE_IDS':ids}
 def validate():
  wait(lambda:r.get('health')['status']=='UP')
  current=r.live_env();assert all(current.get(k)==v for k,v in values.items())
  assert current.get('ARGUS_API_ACCESS_TOKEN')==env['ARGUS_API_ACCESS_TOKEN']
  for key in ('ARGUS_REDIS_ENABLED','ARGUS_AI_ENABLED','ARGUS_MQ_ENABLED','ARGUS_MONITORING_ENABLED'):assert current.get(key)==env.get(key)
  caps=r.get('control/capabilities',current);assert caps['controlEnabled'] and caps['canControl']
  actual={t['instanceId'] for t in caps['targets'] if t['allowedActions']}
  assert actual=={i['id'] for i in targets},actual
  return {'scope':'PRODUCTION_OPERATIONS_CENTRAL','passed':True,'controlEnabled':True,'reviewEnabled':True,'readOnly':False,'allowedTargets':[{'id':i['id'],'name':i['name']} for i in targets],'existingReadTokenPreserved':True,'MQMonitoringRedisAiTogglesPreserved':True}
 result=install('argus-control-center.service',values,validate)
else:raise SystemExit('expected agent or central')
private(ROOT/(action+'-result.json'),json.dumps(result,indent=2));print(json.dumps(result))
