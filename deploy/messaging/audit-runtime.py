"""Report deployment toggles without exposing process credentials or addresses."""
import json
from pathlib import Path
import subprocess

unit='argus-control-center.service'
pid=int(subprocess.check_output(['systemctl','show',unit,'--property=MainPID','--value'],text=True))
if pid<=0:raise SystemExit('control service not running')
env=dict(value.split('=',1) for value in Path('/proc/'+str(pid)+'/environ').read_bytes().decode().split('\0') if '=' in value)
files=subprocess.check_output(['systemctl','show',unit,'--property=EnvironmentFiles','--value'],text=True)
report={'serviceRunning':True,'profile':env.get('SPRING_PROFILES_ACTIVE'),
    'processEnvironmentToggles':{name:env.get(name,'false').lower()=='true' for name in
        ('ARGUS_MQ_ENABLED','ARGUS_MONITORING_ENABLED','ARGUS_REDIS_ENABLED','ARGUS_AI_ENABLED','ARGUS_API_READ_ONLY','ARGUS_TASK_CONTROL_ENABLED','ARGUS_TASK_REVIEW_ENABLED')},
    'newMessagingEnvironmentAttached':'/etc/argus/messaging/client.env' in files}
print(json.dumps(report))
