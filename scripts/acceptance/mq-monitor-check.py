"""Verify actual ready/dead queue alerts on the isolated acceptance service.

Temporarily pauses its listener by configuration and restarts ONLY that service.
Restores the original configuration in finally. Never purges or auto-replays a DLQ.
"""
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT=Path('/opt/argus/acceptance-20261019')
config=ROOT/'central.env'
original=config.read_text()
env=dict(line.split('=',1) for line in original.splitlines() if '=' in line)
if env.get('MYSQL_DATABASE')!='argus_test_acceptance' or env.get('SERVER_PORT')!='19091':
    raise SystemExit('isolated acceptance environment required')
result={'passed':False,'scope':'ISOLATED_REAL_BROKER_MONITORING','observations':[]}

def save():
    (ROOT/'mq-monitor-result.json').write_text(json.dumps(result,indent=2))

def get(path):
    request=urllib.request.Request('http://127.0.0.1:19091/api/'+path,headers={'Authorization':'Bearer '+env['ARGUS_API_CONTROL_TOKEN']})
    with urllib.request.urlopen(request,timeout=5) as response:
        body=json.load(response)
        if body.get('code')!=0:raise RuntimeError('invalid business response')
        return body['data']

def wait(fn,reason,seconds=75):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        try:
            value=fn()
            if value:return value
        except (OSError,ValueError):pass
        time.sleep(1)
    raise RuntimeError(reason)

def restart():
    subprocess.run(['systemctl','restart','argus-acceptance-central'],check=True,capture_output=True)
    wait(lambda:get('health')['status']=='UP','acceptance central did not become ready')

def sql(query):
    return subprocess.run(['docker','exec','argus-stage1-mysql','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names argus_test_acceptance -e "$1"',
        'mq-monitor-sql',query],check=True,capture_output=True,text=True).stdout.strip()

def broker(action,*args):
    output=subprocess.run(['java','-cp','/tmp:/tmp/amqp-client-5.21.0.jar:/tmp/slf4j-api-2.0.16.jar','MqFaultCheck',str(config),action,*args],
        check=True,capture_output=True,text=True).stdout.strip()
    return json.loads(output)

def alert(rule,state):
    value=next((row for row in get('monitoring/summary')['alerts'] if row['rule_key']==rule and row['state']==state and row['observation_state']=='KNOWN'),None)
    if value:
        result['observations'].append({key:value[key] for key in ('rule_key','state','observed_value','checked_at','episodes')});save()
    return value

try:
    task=sql("SELECT task_id FROM task_event_receipts WHERE event_sequence=1 ORDER BY consumed_at DESC LIMIT 1")
    if len(task)!=36 or any(c not in 'abcdef0123456789-' for c in task):raise RuntimeError('fixture task missing')
    tasks_before=int(sql('SELECT COUNT(*) FROM tasks'))
    if broker('status')!={'ready':0,'dead':0}:raise RuntimeError('preserve existing nonempty queues; inspect first')
    # No in-flight container tasks are allowed while pausing/restarting this control center.
    if sql('SELECT COUNT(*) FROM instance_task_locks')!='0':raise RuntimeError('unresolved task exists')
    config.write_text(original+'\nSPRING_RABBITMQ_LISTENER_SIMPLE_AUTO_STARTUP=false\n')
    restart()
    result['publish']=broker('backlog',task)
    wait(lambda:alert('MQ_QUEUE_BACKLOG','ACTIVE'),'ready queue alert did not trigger')
    config.write_text(original)
    restart()
    wait(lambda:alert('MQ_QUEUE_BACKLOG','RESOLVED'),'ready queue alert did not recover')
    result['duplicateReceiptCount']=int(sql("SELECT COUNT(*) FROM task_event_receipts WHERE task_id='"+task+"' AND event_sequence=1"))
    if result['duplicateReceiptCount']!=1:raise RuntimeError('duplicate receipt invariant failed')
    result['poison']=broker('poison')
    wait(lambda:alert('MQ_DEAD_LETTERS','ACTIVE'),'dead letter alert did not trigger')
    result['deadLetterCleanup']=broker('remove-own-poison')
    wait(lambda:alert('MQ_DEAD_LETTERS','RESOLVED'),'dead letter alert did not recover')
    result['finalQueueCounts']=broker('status')
    result['taskCountUnchanged']=tasks_before==int(sql('SELECT COUNT(*) FROM tasks'))
    if not result['taskCountUnchanged']:raise RuntimeError('unexpected new task')
    result['passed']=True
except Exception as failure:
    result['error']=str(failure)
    raise
finally:
    changed=config.read_text()!=original
    config.write_text(original)
    if changed:restart()
    save()
print(json.dumps(result))
