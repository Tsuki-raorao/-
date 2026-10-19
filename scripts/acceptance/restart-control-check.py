"""Restart only the isolated control center while its fixture Agent executes a new RESTART.

Start this watcher before browser-check --restart-only. It never issues a Docker action.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT=Path('/opt/argus/acceptance-20261019')
def sql(query):
    return subprocess.check_output(['docker','exec','argus-stage1-mysql','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names argus_test_acceptance -e "$1"',
        'restart-check',query],text=True).strip()
def pid(unit):
    return subprocess.check_output(['systemctl','show',unit,'--property=MainPID','--value'],text=True).strip()
def commands():
    return [row.split('\t') for row in sql("SELECT id,command_id FROM tasks WHERE instance_id='acceptance-instance' AND action='RESTART'").splitlines()]
previous={row[0] for row in commands()}
if sql('SELECT COUNT(*) FROM instance_task_locks')!='0':raise SystemExit('unresolved task exists')
agent_before=pid('argus-acceptance-agent');central_before=pid('argus-acceptance-central')
if agent_before=='0' or central_before=='0':raise SystemExit('services must be active')
end=time.monotonic()+120
result=None
while time.monotonic()<end:
    for task,command in commands():
        if task in previous:continue
        if any(c not in '0123456789abcdef-' for c in command) or len(command)!=36:raise RuntimeError('invalid fixture command')
        path=ROOT/'inbox'/(command+'.task')
        if not path.exists():continue
        raw=path.read_bytes();payload=raw[8:-32]
        if hashlib.sha256(payload).digest()!=raw[-32:]:raise RuntimeError('invalid inbox checksum')
        record=json.loads(payload)
        if record['status']!='RUNNING':continue
        if record['target']!='argus-acceptance-fixture':raise RuntimeError('target outside fixture')
        subprocess.run(['systemctl','restart','argus-acceptance-central'],check=True,capture_output=True)
        result={'taskId':task,'commandId':command,'agentStateAtRestart':'RUNNING'}
        break
    if result:break
    time.sleep(.25)
if not result:raise RuntimeError('no new running fixture RESTART observed')
end=time.monotonic()+75
while time.monotonic()<end:
    state=sql("SELECT status FROM tasks WHERE id='"+result['taskId']+"'")
    if state=='SUCCEEDED':break
    time.sleep(1)
else:raise RuntimeError('task did not recover after central restart')
result.update(status=state,agentProcessUnchanged=agent_before==pid('argus-acceptance-agent'),
              controlProcessChanged=central_before!=pid('argus-acceptance-central'),
              inboxRecordCount=len(list((ROOT/'inbox').glob(result['commandId']+'.task'))))
result['passed']=result['agentProcessUnchanged'] and result['controlProcessChanged'] and result['inboxRecordCount']==1
(ROOT/'restart-control-result.json').write_text(json.dumps(result,indent=2))
if not result['passed']:raise RuntimeError('restart invariants failed')
print(json.dumps(result))
