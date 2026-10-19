"""One labeled poison event verifies production rejection/DLQ/monitor/recovery.

Never purges a queue or replays an event; any foreign dead letter is preserved.
"""
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT=Path('/opt/argus/backend/releases/argus-first-four-20261019')
env=json.loads((ROOT/'private-runtime.json').read_text())
def broker(action):
    value=subprocess.run(['java','-cp','/tmp:/tmp/amqp-client-5.21.0.jar:/tmp/slf4j-api-2.0.16.jar','BrokerSmoke','/etc/argus/messaging/client.env',action],check=True,capture_output=True,text=True)
    return json.loads(value.stdout)
def api(path):
    request=urllib.request.Request('http://127.0.0.1:9091/api/'+path,headers={'Authorization':'Bearer '+env['ARGUS_API_ACCESS_TOKEN']})
    with urllib.request.urlopen(request,timeout=5) as response:return json.load(response)['data']
def alert(state):
    return next((row for row in api('monitoring/summary')['alerts'] if row['rule_key']=='MQ_DEAD_LETTERS' and row['state']==state and row['observation_state']=='KNOWN'),None)
def wait(fn,reason):
    end=time.monotonic()+90
    while time.monotonic()<end:
        value=fn()
        if value:return value
        time.sleep(1)
    raise RuntimeError(reason)
report={'scope':'PRODUCTION_CONTROLLED_POISON_MONITOR','passed':False}
before=broker('queue-status')
if before['ready']!=0 or before['dead']!=0 or before['consumers']<1:raise SystemExit('queue not clean/consuming; preserve existing messages')
tasks=len(api('tasks'))
pending=False
try:
    report['before']=before;report['publish']=broker('poison');pending=True
    triggered=wait(lambda:alert('ACTIVE'),'production dead-letter alert did not trigger')
    report['trigger']={k:triggered[k] for k in ('rule_key','state','observed_value','checked_at','episodes')}
    report['cleanup']=broker('remove-poison');pending=False
    recovered=wait(lambda:alert('RESOLVED'),'production dead-letter alert did not recover')
    report['recovery']={k:recovered[k] for k in ('rule_key','state','observed_value','checked_at','episodes')}
    report['after']=broker('queue-status');report['taskCountUnchanged']=tasks==len(api('tasks'))
    report['passed']=report['after']['dead']==0 and report['after']['ready']==0 and report['taskCountUnchanged']
    if not report['passed']:raise RuntimeError('production gray final invariant failed')
finally:
    if pending:
        try:report['failureCleanup']=broker('remove-poison')
        except Exception:report['failureCleanup']='manual inspection required; foreign messages never purged'
    (ROOT/'mq-monitor-result.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
