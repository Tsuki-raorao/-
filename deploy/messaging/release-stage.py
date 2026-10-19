"""Staged production rollout. Secrets never appear in the exported status.

start: backup gate + immutable artifact + MQ on/monitor off.
gray: temporary archived-project MOCK facts; no task_queue or Agent command.
finish: monitor on, publish static assets after API checks.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import time
import urllib.error
import urllib.request
import uuid

UNIT='argus-control-center.service'
ROOT=Path('/opt/argus/backend/releases/argus-first-four-20261019')
OVERRIDE=Path('/etc/systemd/system/argus-control-center.service.d/zz-first-four-20261019.conf')
CLIENT=Path('/etc/argus/messaging/client.env')
BACKUP=Path('/opt/argus/backups/first-four-20261019T121050Z')
ENVFILES=[Path('/etc/argus/argus-control-center.env'),Path('/etc/argus/central-release/central-release-20261009T004000.env'),Path('/etc/argus/central-release/argus-ai-v1-20261010.env')]

def call(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def digest(path):
    value=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(65536),b''):value.update(chunk)
    return value.hexdigest()
def live_env():
    pid=int(call(['systemctl','show',UNIT,'--property=MainPID','--value']))
    if pid<=0:raise RuntimeError('control service is not running')
    return dict(value.split('=',1) for value in Path('/proc/'+str(pid)+'/environ').read_bytes().decode().split('\0') if '=' in value)
def private_write(path,value):path.write_text(value);os.chmod(path,0o600)
def load():return json.loads((ROOT/'status.json').read_text())
def save(state):private_write(ROOT/'status.json',json.dumps(state,indent=2))
def auth_env():return json.loads((ROOT/'private-runtime.json').read_text())

class MySQL:
    def __init__(self,env):
        if env.get('MYSQL_DATABASE')!='argus':raise RuntimeError('expected production application database')
        self.config=Path('/etc/argus/messaging')/('.release-mysql-'+str(os.getpid())+'.cnf')
        def quote(value):return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\r','\\r')+'"'
        private_write(self.config,'[client]\n'+''.join(k+'='+quote(v)+'\n' for k,v in {'host':env['MYSQL_HOST'],'port':env.get('MYSQL_PORT','3306'),'user':env['MYSQL_USER'],'password':env['MYSQL_PASSWORD']}.items()))
    def query(self,sql):return call(['mysql','--defaults-extra-file='+str(self.config),'--default-character-set=utf8mb4','--batch','--skip-column-names','argus','-e',sql])
    def __enter__(self):return self
    def __exit__(self,*_):self.config.unlink(missing_ok=True)

def get(path,env=None,method='GET',body=None):
    env=env or auth_env();token=env.get('ARGUS_API_CONTROL_TOKEN') or env['ARGUS_API_ACCESS_TOKEN']
    request=urllib.request.Request('http://127.0.0.1:9091/api/'+path,method=method,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(request,timeout=5) as response:
        value=json.load(response)
        if value.get('code')!=0:raise RuntimeError('business response rejected')
        return value['data']
def wait(fn,reason,seconds=90):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            result=fn()
            if result:return result
        except (OSError,ValueError,subprocess.CalledProcessError):pass
        time.sleep(.5)
    raise RuntimeError(reason)
def restart():
    call(['systemctl','daemon-reload']);call(['systemctl','restart',UNIT]);wait(lambda:get('health')['status']=='UP','new application not healthy')
def monitor_flag(enabled):
    values=dict(row.split('=',1) for row in CLIENT.read_text().splitlines() if '=' in row)
    values['ARGUS_MONITORING_ENABLED']='true' if enabled else 'false'
    private_write(CLIENT,''.join(k+'='+v+'\n' for k,v in values.items()))
def env_preserved(state):return all(digest(path)==state['originalEnvironmentHashes'][str(path)] for path in ENVFILES)

def start():
    if (ROOT/'status.json').exists() or OVERRIDE.exists():raise RuntimeError('release already prepared; inspect its status rather than overwriting')
    old=live_env()
    if old.get('ARGUS_API_READ_ONLY')!='true' or old.get('ARGUS_TASK_CONTROL_ENABLED','false')=='true' or old.get('ARGUS_TASK_REVIEW_ENABLED','false')=='true':raise RuntimeError('expected read-only production baseline')
    call(['python3','/tmp/verify-backup.py',str(BACKUP)])
    if shutil.disk_usage('/opt/argus').free<400*1024*1024:raise RuntimeError('insufficient release disk reserve')
    candidate=Path('/tmp/argus-first-four-candidate.jar');metadata=json.loads(Path('/tmp/argus-first-four-candidate.json').read_text())
    if digest(candidate)!=metadata['artifactSha256'] or not re.fullmatch('[0-9a-f]{40}',metadata['sourceCommit']):raise RuntimeError('candidate manifest mismatch')
    with MySQL(old) as db:
        for table in ('task_queue','task_resolution_queue','instance_task_locks'):
            if db.query('SELECT COUNT(*) FROM '+table)!='0':raise RuntimeError('existing in-flight operations; do not interrupt')
        before=db.query('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1')
        if before!='9':raise RuntimeError('unexpected production schema baseline')
    pid=call(['systemctl','show',UNIT,'--property=MainPID','--value'])
    args=[value.decode() for value in Path('/proc/'+pid+'/cmdline').read_bytes().split(b'\0') if value]
    vm=args[1:args.index('-jar')]
    if any(not (arg.startswith(('-Xms','-Xmx')) or arg=='-Dfile.encoding=UTF-8') for arg in vm):raise RuntimeError('unreviewed live JVM flags')
    ROOT.mkdir(parents=True,exist_ok=False);os.chmod(ROOT,0o755)
    private_write(ROOT/'private-runtime.json',json.dumps(old));private_write(ROOT/'original-client.env',CLIENT.read_text())
    artifact=ROOT/'argus-control-center.jar';shutil.copy2(candidate,artifact);os.chmod(artifact,0o644)
    state={'stage':'PREPARED','sourceCommit':metadata['sourceCommit'],'artifactSha256':digest(artifact),'schemaBefore':before,
        'originalEnvironmentHashes':{str(path):digest(path) for path in ENVFILES},'backupVerified':True,'applicationReadOnly':True}
    save(state);monitor_flag(False)
    OVERRIDE.parent.mkdir(parents=True,exist_ok=True)
    OVERRIDE.write_text('[Service]\nEnvironmentFile='+str(CLIENT)+'\nExecStart=\nExecStart='+shlex.join([args[0],*vm,'-jar',str(artifact)])+'\n');os.chmod(OVERRIDE,0o644)
    try:
        restart()
        with MySQL(old) as db:state['schemaAfter']=db.query('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1')
        current=live_env()
        if state['schemaAfter']!='12' or current.get('ARGUS_MQ_ENABLED')!='true' or not env_preserved(state):raise RuntimeError('startup invariant failed')
        if current.get('ARGUS_AI_ENABLED')!=old.get('ARGUS_AI_ENABLED') or current.get('ARGUS_REDIS_ENABLED')!=old.get('ARGUS_REDIS_ENABLED'):raise RuntimeError('existing feature configuration changed')
        state['stage']='MQ_STARTED_MONITOR_PAUSED';save(state)
    except Exception:
        OVERRIDE.unlink(missing_ok=True);private_write(CLIENT,(ROOT/'original-client.env').read_text())
        call(['systemctl','daemon-reload']);call(['systemctl','restart',UNIT]);state['stage']='START_FAILED_ROLLED_BACK';save(state);raise
    print(json.dumps({'stage':state['stage'],'schemaBefore':before,'schemaAfter':state['schemaAfter'],'readOnlyPreserved':True,'originalEnvironmentFilesUnchanged':True}))

def gray():
    state=load()
    if state['stage']!='MQ_STARTED_MONITOR_PAUSED':raise RuntimeError('unexpected release stage')
    if live_env().get('ARGUS_MONITORING_ENABLED')!='false':raise RuntimeError('monitor must be paused for temporary test resources')
    ids={key:str(uuid.uuid4()) for key in ('project','node','instance','task','event','outbox')};state['grayIds']=ids;save(state)
    now='UTC_TIMESTAMP(6)';p,n,i,t,e,o=(ids[k] for k in ('project','node','instance','task','event','outbox'))
    # Separate archived project. No commandId, no task_queue, no Agent submission.
    with MySQL(auth_env()) as db:
        db.query(f"""START TRANSACTION;
INSERT INTO projects(id,name,status,created_at,updated_at) VALUES('{p}','Temporary MQ release verification','ARCHIVED',{now},{now});
INSERT INTO nodes(id,name,address,status,data_source,project_id) VALUES('{n}','Temporary MQ verification','http://127.0.0.1:1','UNKNOWN','MOCK','{p}');
INSERT INTO instances(id,name,node_id,container_name,agent_instance_id,version,status,updated_at,data_source,project_id) VALUES('{i}','MQ verification only','{n}','mq-verification','mq-verification','release','STOPPED',{now},'MOCK','{p}');
INSERT INTO tasks(id,instance_id,action,status,message,created_at,finished_at,updated_at,execution_mode,requested_by,result_code,project_id,actor_user_id,auth_mode) VALUES('{t}','{i}','START','SUCCEEDED','Synthetic MQ fact only; no Agent command',{now},{now},{now},'MOCK','deployment','RELEASE_VERIFICATION','{p}','00000000-0000-0000-0000-000000000002','LEGACY_TOKEN');
INSERT INTO task_events(id,task_id,event_sequence,from_status,to_status,actor,reason,occurred_at) VALUES('{e}','{t}',1,NULL,'SUCCEEDED','deployment','RELEASE_VERIFICATION',{now});
INSERT INTO task_event_outbox(id,task_id,event_sequence,from_status,to_status,actor,reason,occurred_at,status,attempts,next_attempt_at,created_at) VALUES('{o}','{t}',1,NULL,'SUCCEEDED','deployment','RELEASE_VERIFICATION',{now},'PENDING',0,{now},{now});
COMMIT;""")
        wait(lambda:db.query(f"SELECT status FROM task_event_outbox WHERE id='{o}'")=='PUBLISHED','production outbox not published',45)
        wait(lambda:db.query(f"SELECT COUNT(*) FROM task_event_receipts WHERE task_id='{t}' AND event_sequence=1 AND event_status='SUCCEEDED' AND observed_status='SUCCEEDED'")=='1','production consumer receipt missing',45)
        if db.query(f"SELECT COUNT(*) FROM task_queue WHERE task_id='{t}'")!='0':raise RuntimeError('synthetic fact unexpectedly scheduled')
        state['gray']={'scope':'PRODUCTION_CONTROLLED_SYNTHETIC_MQ_FACT','outboxStatus':'PUBLISHED','receiptCount':1,'taskQueueRows':0,'agentCommandCreated':False}
        db.query(f"""START TRANSACTION;
DELETE FROM task_event_receipts WHERE task_id='{t}';DELETE FROM task_event_outbox WHERE task_id='{t}';DELETE FROM task_events WHERE task_id='{t}';DELETE FROM tasks WHERE id='{t}';
DELETE FROM instances WHERE id='{i}';DELETE FROM nodes WHERE id='{n}';DELETE FROM project_members WHERE project_id='{p}';DELETE FROM projects WHERE id='{p}';COMMIT;""")
        if db.query(f"SELECT COUNT(*) FROM projects WHERE id='{p}'")!='0' or db.query(f"SELECT COUNT(*) FROM tasks WHERE id='{t}'")!='0':raise RuntimeError('gray cleanup incomplete')
    state['gray']['temporaryFactsCleaned']=True;state['stage']='MQ_GRAY_PASSED';save(state)
    print(json.dumps({'stage':state['stage'],'gray':state['gray']}))

def finish():
    state=load()
    if state['stage']!='MQ_GRAY_PASSED':raise RuntimeError('MQ gray gate not passed')
    if live_env().get('ARGUS_MONITORING_ENABLED')!='true':monitor_flag(True);restart()
    summary=wait(lambda:(v if (v:=get('monitoring/summary')).get('enabled') and len(v.get('alerts',[]))>0 else None),'monitoring not evaluating')
    counts={name:len(get(name)) for name in ('nodes','instances','tasks','logs')}
    if not counts['nodes'] or not counts['instances']:raise RuntimeError('real inventory missing')
    configured=auth_env();operator=bool(configured.get('ARGUS_API_CONTROL_TOKEN')) and configured.get('ARGUS_API_CONTROL_TOKEN')!=configured.get('ARGUS_API_ACCESS_TOKEN')
    expected=405 if operator else 403
    try:get('instances/__release_check__/actions',method='POST',body={'action':'START','expectedExecutionMode':'DOCKER'})
    except urllib.error.HTTPError as denied:
        rejected=json.load(denied)
        if denied.code!=expected or (expected==403 and rejected.get('message')!='OPERATOR_REQUIRED'):raise RuntimeError('unexpected write guard result')
        guard_status=denied.code
    else:raise RuntimeError('read-only write guard missing')
    current=live_env()
    if current.get('ARGUS_API_READ_ONLY')!='true' or current.get('ARGUS_TASK_CONTROL_ENABLED','false')=='true' or current.get('ARGUS_TASK_REVIEW_ENABLED','false')=='true' or not env_preserved(state):raise RuntimeError('production safety/config invariant changed')
    metadata=json.loads(Path('/tmp/argus-first-four-candidate.json').read_text())
    archive_path=Path('/tmp/argus-first-four-frontend.tgz')
    if metadata['artifactSha256']!=state['artifactSha256'] or digest(archive_path)!=metadata['frontendSha256']:raise RuntimeError('frontend candidate checksum mismatch')
    staging=ROOT/'frontend';staging.mkdir(exist_ok=True)
    with tarfile.open('/tmp/argus-first-four-frontend.tgz','r:gz') as archive:
        for member in archive.getmembers():
            target=(staging/member.name).resolve()
            if not target.is_relative_to(staging.resolve()) or member.issym() or member.islnk():raise RuntimeError('unsafe static archive entry')
            if member.isdir():target.mkdir(parents=True,exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True,exist_ok=True)
                with archive.extractfile(member) as source,target.open('wb') as output:shutil.copyfileobj(source,output)
                os.chmod(target,0o644)
            else:raise RuntimeError('unsupported static archive entry')
    site=Path('/var/www/argus')
    files=[path for path in staging.rglob('*') if path.is_file()];files.sort(key=lambda path:path.name=='index.html')
    for path in files:
        target=site/path.relative_to(staging);target.parent.mkdir(parents=True,exist_ok=True)
        temp=target.with_name(target.name+'.first-four-tmp');shutil.copy2(path,temp);os.replace(temp,target)
    state['stage']='PUBLISHED';state['inventoryCounts']=counts;state['monitorAlertRecords']=len(summary['alerts']);state['writeGuardStatus']=guard_status;state['staticIndexSha256']=digest(site/'index.html');state['frontendArchiveSha256']=metadata['frontendSha256'];save(state)
    print(json.dumps({key:state[key] for key in ('stage','sourceCommit','artifactSha256','schemaAfter','inventoryCounts','monitorAlertRecords','writeGuardStatus','staticIndexSha256')}))

if __name__=='__main__':{'start':start,'gray':gray,'finish':finish}[sys.argv[1]]()
