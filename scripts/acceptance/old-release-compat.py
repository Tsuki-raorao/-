"""Start the backed-up V9 application against isolated V12 DB; no remote agents."""
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT=Path('/opt/argus/acceptance-20261019')
env=dict(row.split('=',1) for row in (ROOT/'performance.env').read_text().splitlines() if '=' in row)
env.update(MYSQL_DATABASE='argus_test_release',SERVER_PORT='19093',SERVER_ADDRESS='127.0.0.1',
           ARGUS_AGENT_GATEWAY_ENABLED='false',ARGUS_TASK_INITIAL_DELAY_MS='3600000',
           ARGUS_TASK_REVIEW_INITIAL_DELAY_MS='3600000',ARGUS_MQ_ENABLED='false',ARGUS_MONITORING_ENABLED='false')
target=ROOT/'old-release.env';target.write_text(''.join(k+'='+v+'\n' for k,v in env.items()));os.chmod(target,0o600)
unit='argus-old-release-compat'
if subprocess.run(['systemctl','is-active','--quiet',unit],capture_output=True).returncode==0:raise SystemExit('existing compatibility test still running')
subprocess.run(['systemd-run','--unit='+unit,'--collect','--property=WorkingDirectory='+str(ROOT),
    '--property=EnvironmentFile='+str(target),'--property=MemoryMax=768M','--property=CPUQuota=100%',
    '/usr/bin/java','-Xms128m','-Xmx512m','-jar',str(ROOT/'previous-control.jar')],check=True,capture_output=True)
report={'scope':'ISOLATED_OLD_V9_APPLICATION_ON_V12_SCHEMA','passed':False}
def get(path):
    request=urllib.request.Request('http://127.0.0.1:19093/api/'+path,headers={'Authorization':'Bearer '+env['ARGUS_API_ACCESS_TOKEN']})
    with urllib.request.urlopen(request,timeout=5) as response:
        body=json.load(response)
        if response.status!=200 or body.get('code')!=0:raise RuntimeError('invalid business response')
        return body['data']
try:
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        try:
            if get('health')['status']=='UP':break
        except (OSError,ValueError):pass
        time.sleep(.5)
    else:raise RuntimeError('old application not healthy on V12')
    report['nodes']=len(get('nodes'));report['instances']=len(get('instances'))
    report['tasks']=len(get('tasks'));report['logs']=len(get('logs'))
    report['readOnly']=get('health')['readOnly']
    report['passed']=report['readOnly'] and report['nodes']>0 and report['instances']>0
    if not report['passed']:raise RuntimeError('old application read checks failed')
finally:
    subprocess.run(['systemctl','stop',unit],check=True,capture_output=True)
    (ROOT/'old-release-compat.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
