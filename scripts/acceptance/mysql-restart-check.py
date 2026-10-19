"""Restart ONLY the isolated MySQL with a durable, not-yet-executed task/outbox.

Production MySQL and game containers are outside this script's scope.
"""
import json
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

ROOT=Path('/opt/argus/acceptance-20261019')
config=ROOT/'central.env';original=config.read_text()
env=dict(row.split('=',1) for row in original.splitlines() if '=' in row)
if env.get('MYSQL_DATABASE')!='argus_test_acceptance' or env.get('MYSQL_PORT')!='23306' or env.get('MYSQL_HOST')!='127.0.0.1':raise SystemExit('dedicated test database required')
report={'scope':'ISOLATED_MYSQL_PROCESS_RESTART_REAL_TASK_OUTBOX','passed':False}
def call(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def sql(query):return call(['docker','exec','argus-stage1-mysql','sh','-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names argus_test_acceptance -e "$1"','mysql-restart-check',query])
def api(path,payload=None):
    headers={'Authorization':'Bearer '+env['ARGUS_API_CONTROL_TOKEN'],'Content-Type':'application/json'}
    if payload is not None:headers['Idempotency-Key']=str(uuid.uuid4())
    req=urllib.request.Request('http://127.0.0.1:19091/api/'+path,data=json.dumps(payload).encode() if payload is not None else None,headers=headers)
    with urllib.request.urlopen(req,timeout=10) as response:
        body=json.load(response)
        if body.get('code')!=0 or (payload is not None and response.status!=202):raise RuntimeError('API rejection')
        return body['data']
def wait(fn,reason,seconds=90):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            value=fn()
            if value:return value
        except (OSError,ValueError,subprocess.CalledProcessError):pass
        time.sleep(.5)
    raise RuntimeError(reason)
def restart_app():
    call(['systemctl','restart','argus-acceptance-central']);wait(lambda:api('health')['status']=='UP','app not ready')
def done(task):
    value=api('tasks/'+task['id'])
    if value['status'] in ('FAILED','UNKNOWN'):raise RuntimeError('unexpected task terminal '+value['status'])
    return value['status']=='SUCCEEDED'
def submit(action):return api('instances/acceptance-instance/actions',{'action':action,'expectedExecutionMode':'DOCKER'})
try:
    if sql('SELECT COUNT(*) FROM instance_task_locks')!='0':raise RuntimeError('unresolved task exists')
    fixture=json.loads(call(['docker','inspect','argus-acceptance-fixture']))[0]
    if fixture['State']['Running']:raise RuntimeError('fixture must start stopped')
    config.write_text(original+'\nARGUS_MQ_ENABLED=false\nARGUS_TASK_INITIAL_DELAY_MS=3600000\nARGUS_TASK_COMMAND_TTL=120s\n')
    restart_app();task=submit('START');report['taskId']=task['id'];report['commandId']=task['commandId']
    queue=lambda:int(sql("SELECT COUNT(*) FROM task_queue WHERE task_id='"+task['id']+"'"))
    outbox=lambda:int(sql("SELECT COUNT(*) FROM task_event_outbox WHERE task_id='"+task['id']+"' AND status='PENDING'"))
    if queue()!=1 or outbox()!=1:raise RuntimeError('durable queue/outbox not created')
    if (ROOT/'inbox'/(task['commandId']+'.task')).exists():raise RuntimeError('command already executed before fault')
    report['beforeRestart']={'taskStatus':api('tasks/'+task['id'])['status'],'queueRows':queue(),'pendingOutbox':outbox(),'agentRecordExists':False}
    began=time.monotonic();call(['docker','restart','argus-stage1-mysql'])
    wait(lambda:sql('SELECT 1')=='1','isolated MySQL not recovered')
    report['mysqlRestartMs']=round((time.monotonic()-began)*1000,3)
    report['afterRestart']={'queueRows':queue(),'pendingOutbox':outbox(),'taskStatus':sql("SELECT status FROM tasks WHERE id='"+task['id']+"'")}
    if report['afterRestart']!={'queueRows':1,'pendingOutbox':1,'taskStatus':'PENDING'}:raise RuntimeError('durable facts did not survive')
    config.write_text(original);restart_app();wait(lambda:done(task),'original task did not recover')
    count=lambda table,condition:int(sql('SELECT COUNT(*) FROM '+table+" WHERE task_id='"+task['id']+"'"+condition))
    wait(lambda:count('task_event_outbox'," AND status<>'PUBLISHED'")==0,'outbox not published after recovery')
    events=count('task_events','');wait(lambda:count('task_event_receipts','')==events,'consumer receipts missing')
    report.update(finalTaskStatus='SUCCEEDED',events=events,publishedOutbox=count('task_event_outbox'," AND status='PUBLISHED'"),consumerReceipts=count('task_event_receipts',''),inboxRecordCount=len(list((ROOT/'inbox').glob(task['commandId']+'.task'))))
    cleanup=submit('STOP');wait(lambda:done(cleanup),'cleanup STOP failed');report['cleanupTaskId']=cleanup['id']
    report['passed']=report['inboxRecordCount']==1 and queue()==0 and sql('SELECT COUNT(*) FROM instance_task_locks')=='0'
    if not report['passed']:raise RuntimeError('final recovery invariants failed')
except Exception as failure:
    report['error']=str(failure);raise
finally:
    state=subprocess.run(['docker','inspect','--format','{{.State.Running}}','argus-stage1-mysql'],capture_output=True,text=True)
    if state.stdout.strip()=='false':call(['docker','start','argus-stage1-mysql']);wait(lambda:sql('SELECT 1')=='1','test MySQL not restored')
    if config.read_text()!=original:config.write_text(original);restart_app()
    (ROOT/'mysql-restart-result.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
