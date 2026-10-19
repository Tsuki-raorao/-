"""Real Redis pause/stop recovery through the existing isolated task HTTP chain.

Only the dedicated Redis container and acceptance Docker fixture are touched.
The control center's original private environment is restored in finally.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.request
import uuid

ROOT=Path('/opt/argus/acceptance-20261019')
NAME='argus-redis-check-20261019'
original=(ROOT/'central.env').read_text()
env=dict(line.split('=',1) for line in original.splitlines() if '=' in line)
if env.get('MYSQL_DATABASE')!='argus_test_acceptance' or env.get('ARGUS_TASK_ALLOWED_INSTANCE_IDS')!='acceptance-instance':
    raise SystemExit('isolated acceptance task scope required')
report={'scope':'ISOLATED_REAL_REDIS_MYSQL_DOCKER','passed':False,'stages':[]}
existing=ROOT/'redis-fault-result.json'
if existing.exists():(ROOT/('redis-fault-result-'+str(time.time_ns())+'.json')).write_bytes(existing.read_bytes())

def call(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def docker(*args):return call(['docker',*args])
def sql(query):return docker('exec','argus-stage1-mysql','sh','-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names argus_test_acceptance -e "$1"','redis-fact-check',query)

def wait(fn,reason,seconds=75):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            value=fn()
            if value:return value
        except (OSError,ValueError,subprocess.CalledProcessError):pass
        time.sleep(.5)
    raise RuntimeError(reason)

def api(path,payload=None,key=None):
    headers={'Authorization':'Bearer '+env['ARGUS_API_CONTROL_TOKEN'],'Content-Type':'application/json'}
    if key:headers['Idempotency-Key']=key
    request=urllib.request.Request('http://127.0.0.1:19091/api/'+path,
        data=json.dumps(payload).encode() if payload is not None else None,headers=headers)
    with urllib.request.urlopen(request,timeout=10) as response:
        value=json.load(response)
        if value.get('code')!=0:raise RuntimeError('business rejection')
        if payload is not None and response.status!=202:raise RuntimeError('expected durable acceptance')
        return value['data']

def restart():
    call(['systemctl','restart','argus-acceptance-central'])
    wait(lambda:api('health')['status']=='UP','control center not ready')

def redis(*args):return docker('exec',NAME,'sh','-c',
    'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --no-auth-warning --raw "$@"','redis-check',*args)

def action(name,stage):
    began=time.monotonic();task=api('instances/acceptance-instance/actions',{'action':name,'expectedExecutionMode':'DOCKER'},str(uuid.uuid4()))
    accepted_ms=round((time.monotonic()-began)*1000,3)
    if task['instanceId']!='acceptance-instance' or task['executionMode']!='DOCKER':raise RuntimeError('task outside fixture scope')
    def finished():
        value=api('tasks/'+task['id'])
        if value['status'] in ('FAILED','UNKNOWN'):raise RuntimeError('task failed: '+value['status'])
        return value if value['status']=='SUCCEEDED' else None
    value=wait(finished,'task not completed')
    events=int(sql("SELECT COUNT(*) FROM task_events WHERE task_id='"+task['id']+"'"))
    outbox=int(sql("SELECT COUNT(*) FROM task_event_outbox WHERE task_id='"+task['id']+"'"))
    if events<2 or outbox!=events:raise RuntimeError('transactional event/outbox facts missing')
    return {'stage':stage,'taskId':task['id'],'commandId':task['commandId'],'action':name,'status':value['status'],
            'acceptanceMs':accepted_ms,'completionMs':round((time.monotonic()-began)*1000,3),'events':events,'outbox':outbox}

try:
    if sql('SELECT COUNT(*) FROM instance_task_locks')!='0':raise RuntimeError('unresolved task exists')
    # A prior failed test can leave its fixture running. Restore only this fixture via a new audited task.
    fixture=json.loads(docker('inspect','argus-acceptance-fixture'))[0]
    if fixture['State']['Running']:report['preparationCleanup']=action('STOP','preparation-cleanup')
    secret_file=ROOT/'redis-check.env'
    if not secret_file.exists():secret_file.write_text('REDIS_PASSWORD='+secrets.token_hex(32)+'\n');os.chmod(secret_file,0o600)
    password=dict(line.split('=',1) for line in secret_file.read_text().splitlines())['REDIS_PASSWORD']
    found=subprocess.run(['docker','inspect',NAME],capture_output=True,text=True)
    if found.returncode:
        docker('run','-d','--name',NAME,'--label','argus.scope=redis-check-20261019','--memory','64m','--cpus','0.2',
               '-p','127.0.0.1:26379:6379','--env-file',str(secret_file),'--user','redis','redis:7.4-alpine','sh','-c',
               'umask 077; printf "port 6379\nbind 0.0.0.0\nrequirepass %s\n" "$REDIS_PASSWORD" > /tmp/check-redis.conf; exec redis-server /tmp/check-redis.conf')
    else:
        state=json.loads(found.stdout)[0]
        if state['Config']['Labels'].get('argus.scope')!='redis-check-20261019':raise RuntimeError('foreign Redis container')
        if state['State']['Paused']:docker('unpause',NAME)
        if not state['State']['Running']:docker('start',NAME)
    wait(lambda:redis('PING')=='PONG','test Redis not ready')
    overrides={'ARGUS_REDIS_ENABLED':'true','ARGUS_REDIS_KEY_PREFIX':'argus:acceptance:',
        'SPRING_DATA_REDIS_HOST':'127.0.0.1','SPRING_DATA_REDIS_PORT':'26379','SPRING_DATA_REDIS_PASSWORD':password,
        'SPRING_DATA_REDIS_CLIENT_NAME':'argus-acceptance-fault-check',
        'SPRING_DATA_REDIS_TIMEOUT':'500ms','SPRING_DATA_REDIS_CONNECT_TIMEOUT':'500ms'}
    (ROOT/'central.env').write_text(original+'\n'+''.join(k+'='+v+'\n' for k,v in overrides.items()))
    restart()
    for stage in ('healthy','paused-timeout','stopped-unavailable','recovered'):
        if stage=='paused-timeout':docker('pause',NAME)
        if stage=='stopped-unavailable':docker('unpause',NAME);docker('stop',NAME)
        if stage=='recovered':
            docker('start',NAME);wait(lambda:redis('PING')=='PONG','Redis recovery failed')
            began=time.monotonic()
            wait(lambda:'name=argus-acceptance-fault-check ' in redis('CLIENT','LIST'),'application Redis connection did not recover',60)
            report['applicationReconnectWaitMs']=round((time.monotonic()-began)*1000,3)
        for operation in ('START','STOP'):
            record=action(operation,stage)
            report['stages'].append(record)
            if stage in ('healthy','recovered'):
                key='argus:acceptance:task:'+record['taskId']
                cached=wait(lambda:json.loads(redis('GET',key) or '{}').get('status')=='SUCCEEDED','successful hot-state missing',10)
                record['cacheMatchesTerminal']=bool(cached);record['ttlSeconds']=int(redis('TTL',key))
            (ROOT/'redis-fault-result.json').write_text(json.dumps(report,indent=2))
    if sql('SELECT COUNT(*) FROM instance_task_locks')!='0':raise RuntimeError('final instance lock remains')
    report['passed']=True
except Exception as failure:
    report['error']=str(failure)
    raise
finally:
    state=subprocess.run(['docker','inspect','--format','{{.State.Paused}}',NAME],capture_output=True,text=True)
    if state.stdout.strip()=='true':docker('unpause',NAME)
    if (ROOT/'central.env').read_text()!=original:
        (ROOT/'central.env').write_text(original);restart()
    # Never force-unlock an uncertain task. Only clean a proven free fixture via the task API.
    if sql('SELECT COUNT(*) FROM instance_task_locks')=='0':
        fixture=json.loads(docker('inspect','argus-acceptance-fixture'))[0]
        if fixture['State']['Running']:report['finalCleanup']=action('STOP','final-cleanup')
    if subprocess.run(['docker','inspect',NAME],capture_output=True).returncode==0:docker('stop',NAME)
    (ROOT/'redis-fault-result.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
