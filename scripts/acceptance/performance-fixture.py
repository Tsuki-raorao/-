"""Cloud performance fixture. Only the dedicated argus_test_perf database is writable.

Run with sudo on the existing acceptance host. Credentials remain in private files.
Does not create task queue rows or issue Docker control commands.
"""
from pathlib import Path
import json
import os
import subprocess
import sys

ROOT = Path('/opt/argus/acceptance-20261019')
DB = 'argus_test_perf'
PROJECT = '00000000-0000-0000-0000-000000000001'
USER = '00000000-0000-0000-0000-000000000002'

def sql(query):
    return subprocess.run(['docker','exec','argus-stage1-mysql','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names "$1" -e "$2"',
        'perf-sql',DB,query],check=True,capture_output=True,text=True).stdout.strip()

def start():
    values=dict(line.split('=',1) for line in (ROOT/'central.env').read_text().splitlines() if '=' in line)
    if values.get('MYSQL_HOST')!='127.0.0.1' or values.get('MYSQL_PORT')!='23306':
        raise RuntimeError('dedicated loopback MySQL required')
    values.update(MYSQL_DATABASE=DB,SERVER_PORT='19092',ARGUS_API_READ_ONLY='true',
        ARGUS_AGENT_GATEWAY_ENABLED='false',ARGUS_AGENT_GATEWAY_ALLOW_CONTROL='false',
        ARGUS_TASK_CONTROL_ENABLED='false',ARGUS_TASK_REVIEW_ENABLED='false',
        ARGUS_MQ_ENABLED='false',ARGUS_MONITORING_ENABLED='false',ARGUS_REDIS_ENABLED='false')
    target=ROOT/'performance.env'
    target.write_text(''.join(k+'='+v+'\n' for k,v in values.items()));os.chmod(target,0o600)
    if subprocess.run(['systemctl','is-active','--quiet','argus-acceptance-perf']).returncode==0:
        raise RuntimeError('performance service already active; inspect rather than replacing')
    subprocess.run(['systemd-run','--unit=argus-acceptance-perf','--collect',
        '--property=EnvironmentFile='+str(target),'--property=WorkingDirectory='+str(ROOT),
        '--property=MemoryMax=768M','--property=CPUQuota=100%','--property=UMask=0077',
        '/usr/bin/java','-Xms128m','-Xmx512m','-jar',str(ROOT/('performance.jar' if (ROOT/'performance.jar').exists() else 'central.jar'))],check=True,capture_output=True)
    print(json.dumps({'started':True,'port':19092,'database':DB,'controlEnabled':False}))

def seed():
    if sql('SELECT COUNT(*) FROM tasks;')!='0' or sql('SELECT COUNT(*) FROM logs;')!='0':
        raise RuntimeError('refuse to seed a nonempty dataset')
    # Five digits produce exactly 100000 rows without recursion configuration changes.
    digits='SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9'
    numbers=f'SELECT a.d+10*b.d+100*c.d+1000*d.d+10000*e.d n FROM ({digits}) a CROSS JOIN ({digits}) b CROSS JOIN ({digits}) c CROSS JOIN ({digits}) d CROSS JOIN ({digits}) e'
    statements=f"""
INSERT INTO nodes(id,name,address,status,project_id) VALUES('perf-node','Performance fixture','http://127.0.0.1:1','UNKNOWN','{PROJECT}');
INSERT INTO instances(id,name,node_id,container_name,version,status,updated_at,agent_instance_id,data_source,metrics_status,project_id)
SELECT CONCAT('perf-instance-',n),CONCAT('Performance ',n),'perf-node',CONCAT('perf-container-',n),'fixture','STOPPED',CURRENT_TIMESTAMP,CONCAT('perf-container-',n),'LEGACY','UNKNOWN','{PROJECT}' FROM ({numbers}) seq WHERE n<100;
INSERT INTO logs(id,instance_id,log_timestamp,level,message,project_id)
SELECT CONCAT('perf-log-',LPAD(n,6,'0')),CONCAT('perf-instance-',MOD(n,100)),TIMESTAMP('2026-10-01 00:00:00')+INTERVAL n SECOND,'INFO',CONCAT('Isolated synthetic log ',n),'{PROJECT}' FROM ({numbers}) seq;
INSERT INTO tasks(id,instance_id,action,status,message,created_at,finished_at,updated_at,project_id,actor_user_id,auth_mode)
SELECT CONCAT('perf-task-',LPAD(n,6,'0')),CONCAT('perf-instance-',MOD(n,100)),'START',IF(MOD(n,20)=0,'FAILED','SUCCEEDED'),'Synthetic completed history',TIMESTAMP('2026-10-01 00:00:00')+INTERVAL n SECOND,TIMESTAMP('2026-10-01 00:00:01')+INTERVAL n SECOND,TIMESTAMP('2026-10-01 00:00:01')+INTERVAL n SECOND,'{PROJECT}','{USER}','LEGACY_TOKEN' FROM ({numbers}) seq;
ANALYZE TABLE logs,tasks;
"""
    sql(statements)
    print(json.dumps({'database':DB,'nodes':1,'instances':100,'logs':int(sql('SELECT COUNT(*) FROM logs;')),'tasks':int(sql('SELECT COUNT(*) FROM tasks;')),'queueRows':int(sql('SELECT COUNT(*) FROM task_queue;'))}))

def explain():
    queries={
        'project_logs':f"SELECT id,instance_id,log_timestamp,level,message FROM logs WHERE project_id='{PROJECT}' ORDER BY log_timestamp DESC LIMIT 100",
        'recent_failures':f"SELECT COUNT(*) FROM tasks WHERE project_id='{PROJECT}' AND status IN ('FAILED','UNKNOWN') AND updated_at>='2026-10-02 03:40:00'",
        'recent_tasks':f"SELECT * FROM tasks WHERE project_id='{PROJECT}' ORDER BY created_at DESC,id LIMIT 100",
    }
    result={name:{'query':query,'plan':sql('EXPLAIN ANALYZE '+query)} for name,query in queries.items()}
    print(json.dumps({'database':DB,'queries':result}))

if __name__=='__main__':
    {'start':start,'seed':seed,'explain':explain}[sys.argv[1]]()
