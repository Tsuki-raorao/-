"""Isolated server acceptance environment. Does not control game containers.

Run as root on the test server. Required private files and build artifacts are
uploaded separately into ROOT before 'prepare'. All published outputs omit secrets.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path('/opt/argus/acceptance-20261019')
FIXTURE = 'argus-acceptance-fixture'
DATABASES = ('argus_test_acceptance', 'argus_test_perf')
UNITS = ('argus-acceptance-agent', 'argus-acceptance-central', 'argus-acceptance-proxy')

def call(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kwargs).stdout.strip()

def docker(*args):
    return call(['docker', *args])

def baseline():
    ids = docker('ps', '-aq').splitlines()
    values = json.loads(docker('inspect', *ids)) if ids else []
    return [{ 'name': v['Name'].lstrip('/'), 'id': v['Id'], 'startedAt': v['State']['StartedAt'], 'running': v['State']['Running'] }
            for v in values if v['Name'].lstrip('/') in ('mc01', 'mc02', 'mc03', 'fight202609')]

def private(name):
    p = ROOT / name
    if not p.is_file():
        raise RuntimeError(f'missing private file: {name}')
    os.chmod(p, 0o600)
    return p

def sql(query, database=None):
    if database is not None and database not in DATABASES:
        raise RuntimeError('database outside isolated scope')
    # Password is expanded within the dedicated test container, never printed.
    return docker('exec', 'argus-stage1-mysql', 'sh', '-c',
                  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --skip-column-names -e "$1"',
                  'acceptance-sql', query)

def start(unit, args, env=None, memory='256M'):
    existing = subprocess.run(['systemctl', 'is-active', '--quiet', unit], capture_output=True)
    if existing.returncode == 0:
        raise RuntimeError(f'{unit} already running; inspect it before replacing')
    command = ['systemd-run', '--unit='+unit, '--collect', '--property=WorkingDirectory='+str(ROOT),
               '--property=MemoryMax='+memory, '--property=CPUQuota=100%', '--property=UMask=0077']
    if env:
        command.append('--property=EnvironmentFile='+str(private(env)))
    return call(command + args)

def status():
    services = {u: subprocess.run(['systemctl', 'is-active', u], capture_output=True, text=True).stdout.strip() for u in UNITS}
    previous = json.loads((ROOT/'baseline.json').read_text()) if (ROOT/'baseline.json').exists() else None
    return {'services': services, 'gameBaselineUnchanged': previous == baseline(), 'games': baseline()}

def main():
    action = sys.argv[1]
    if action == 'prepare':
        ROOT.mkdir(parents=True, exist_ok=True)
        if (ROOT/'baseline.json').exists():
            raise RuntimeError('already prepared; preserve baseline and inspect status')
        (ROOT/'baseline.json').write_text(json.dumps(baseline(), indent=2))
        for name in ('central.env', 'agent.properties', 'proxy.env'):
            private(name)
        for db in DATABASES:
            sql(f"CREATE DATABASE IF NOT EXISTS {db} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL ON {db}.* TO 'arguscheck'@'%';")
        # The existing image already contains sh/sleep; no Java process in the fixture.
        docker('run', '-d', '--name', FIXTURE, '--memory', '32m', '--cpus', '0.1',
               'eclipse-temurin:21-jre', 'sh', '-c', 'while true; do echo argus-acceptance-alive; sleep 5; done')
        docker('stop', FIXTURE)
        print(json.dumps({'prepared': True, 'fixture': FIXTURE, 'games': baseline()}))
    elif action == 'reset-empty-failed-database':
        if subprocess.run(['systemctl','is-active','--quiet',UNITS[1]],capture_output=True).returncode==0:
            raise RuntimeError('central still active')
        count=sql('SELECT COUNT(*) FROM argus_test_acceptance.tasks;', 'argus_test_acceptance')
        if count!='0': raise RuntimeError('refuse reset of a database containing task history')
        sql("DROP DATABASE argus_test_acceptance; CREATE DATABASE argus_test_acceptance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", 'argus_test_acceptance')
        print(json.dumps({'resetEmptyFailedTestDatabase': True}))
    elif action in ('start','start-central'):
        if action=='start': start(UNITS[0], ['/usr/bin/java', '-Xms32m', '-Xmx128m', '-cp', str(ROOT/'agent.jar'),
                         'com.argus.agent.AgentApplication', '--config='+str(ROOT/'agent.properties')])
        start(UNITS[1], ['/usr/bin/java', '-Xms128m', '-Xmx512m', '-jar', str(ROOT/'central.jar')], 'central.env', '768M')
        if action=='start': start(UNITS[2], ['/usr/bin/python3', str(ROOT/'fault-proxy.py')], 'proxy.env', '128M')
        print(json.dumps(status()))
    elif action == 'seed':
        project = '00000000-0000-0000-0000-000000000001'
        sql(f"""USE argus_test_acceptance;
INSERT INTO nodes(id,name,address,status,sync_status,data_source,metrics_status,project_id)
VALUES('acceptance-node','独立验收节点','http://127.0.0.1:18091','UNKNOWN','UNKNOWN','LEGACY','UNKNOWN','{project}');
INSERT INTO instances(id,name,node_id,container_name,version,status,updated_at,agent_instance_id,data_source,metrics_status,project_id)
VALUES('acceptance-instance','独立验收容器','acceptance-node','{FIXTURE}','acceptance','STOPPED',CURRENT_TIMESTAMP,'{FIXTURE}','DOCKER','UNKNOWN','{project}');""", 'argus_test_acceptance')
        print(json.dumps({'seeded': True}))
    elif action == 'status':
        print(json.dumps(status()))
    elif action == 'repair-fixture-labels':
        sql("UPDATE argus_test_acceptance.nodes SET name='独立验收节点' WHERE id='acceptance-node'; UPDATE argus_test_acceptance.instances SET name='独立验收容器' WHERE id='acceptance-instance';", 'argus_test_acceptance')
        print(json.dumps({'fixtureLabelsRepaired':True}))
    elif action == 'evidence':
        rows=sql("SELECT JSON_OBJECT('id',id,'action',action,'status',status,'commandId',command_id,'resultCode',result_code) FROM argus_test_acceptance.tasks WHERE instance_id='acceptance-instance' ORDER BY created_at,id;", 'argus_test_acceptance')
        inbox=[]
        for path in sorted((ROOT/'inbox').glob('*.task')):
            import struct
            raw=path.read_bytes();magic,length=struct.unpack('>II',raw[:8]);payload=raw[8:8+length]
            if magic!=0x41524731 or len(raw)!=length+40 or hashlib.sha256(payload).digest()!=raw[-32:]:
                raise RuntimeError('Inbox integrity check failed')
            value=json.loads(payload);command=json.loads(value['command'])
            inbox.append({'commandId':command['commandId'],'action':command['action'],'status':value['status'],'target':value['target']})
        fixture=json.loads(docker('inspect',FIXTURE))[0]
        result=status()
        result.update(tasks=[json.loads(row) for row in rows.splitlines()],inbox=inbox,
                      fixture={'running':fixture['State']['Running'],'startedAt':fixture['State']['StartedAt'],'finishedAt':fixture['State']['FinishedAt']},
                      unresolvedLocks=int(sql('SELECT COUNT(*) FROM argus_test_acceptance.instance_task_locks;', 'argus_test_acceptance')))
        print(json.dumps(result))
    elif action == 'stop':
        for unit in reversed(UNITS):
            subprocess.run(['systemctl', 'stop', unit], capture_output=True)
        docker('stop', FIXTURE)
        print(json.dumps(status()))
    else:
        raise RuntimeError('unknown acceptance action')

if __name__ == '__main__':
    main()
