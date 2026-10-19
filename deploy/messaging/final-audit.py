"""Read-only production audit. Export facts/hashes/counts, never server addresses or tokens."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import re
from datetime import datetime,timezone
import urllib.request

sys.path.insert(0,'/tmp')
import importlib.util
spec=importlib.util.spec_from_file_location('release_stage','/tmp/release-stage.py');release=importlib.util.module_from_spec(spec);spec.loader.exec_module(release)
state=release.load();env=release.live_env()
if state['stage']!='PUBLISHED':raise SystemExit('release publication incomplete')
operations=len(sys.argv)==3 and sys.argv[2]=='--operations'
if len(sys.argv) not in (2,3) or (len(sys.argv)==3 and not operations) or not re.fullmatch(r'[A-Za-z0-9.-]+',sys.argv[1]):raise SystemExit('provide the Argus HTTP virtual-host name/IP, not the default loopback host')
http_host=sys.argv[1]
report={'scope':'PRODUCTION_OPERATIONS_FINAL_AUDIT' if operations else 'PRODUCTION_FIRST_FOUR_FINAL_AUDIT','passed':False,'checks':[]}
def check(value,name):
    report['checks'].append({'name':name,'passed':bool(value)})
    if not value:raise RuntimeError(name)
try:
    check(release.env_preserved(state),'three original environment files unchanged')
    pid=release.call(['systemctl','show',release.UNIT,'--property=MainPID','--value'])
    args=[part.decode() for part in Path('/proc/'+pid+'/cmdline').read_bytes().split(b'\0') if part]
    live=Path(args[args.index('-jar')+1]).resolve()
    check(live==release.ROOT/'argus-control-center.jar' and release.digest(live)==state['artifactSha256'],'running artifact matches verified candidate')
    check(env.get('ARGUS_MQ_ENABLED')=='true' and env.get('ARGUS_MONITORING_ENABLED')=='true','MQ and monitoring enabled in running process')
    if operations:
        check(env.get('ARGUS_API_READ_ONLY')=='false' and env.get('ARGUS_TASK_CONTROL_ENABLED')=='true' and env.get('ARGUS_TASK_REVIEW_ENABLED')=='true' and bool(env.get('ARGUS_API_CONTROL_TOKEN')) and env['ARGUS_API_CONTROL_TOKEN']!=env['ARGUS_API_ACCESS_TOKEN'],'authenticated operations and independent operator enabled')
        caps=release.get('control/capabilities',env)
        check(caps['controlEnabled'] and caps['canControl'] and len([t for t in caps['targets'] if t['allowedActions']])==9,'nine explicit business/test targets enabled')
        reader=dict(env);reader.pop('ARGUS_API_CONTROL_TOKEN',None)
        rcaps=release.get('control/capabilities',reader)
        check(not rcaps['canControl'] and all(not t['allowedActions'] for t in rcaps['targets']),'existing reader has no operation capability')
    else:
        check(env.get('ARGUS_API_READ_ONLY')=='true' and env.get('ARGUS_TASK_CONTROL_ENABLED','false')!='true' and env.get('ARGUS_TASK_REVIEW_ENABLED','false')!='true','production real operations remain disabled')
    old=release.auth_env()
    check(all(env.get(key)==old.get(key) for key in ('ARGUS_AI_ENABLED','ARGUS_REDIS_ENABLED')),'existing AI and Redis toggles preserved')
    with release.MySQL(env) as db:
        version=db.query('SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1')
        check(version=='12' and db.query('SELECT COUNT(*) FROM flyway_schema_history WHERE success=0')=='0','production schema V12 with no failed migration')
        for table in ('task_queue','task_resolution_queue','instance_task_locks'):
            check(db.query('SELECT COUNT(*) FROM '+table)=='0',table+' has no unresolved work')
        ids=state['grayIds'];task=ids['task']
        remaining=sum(int(db.query(query)) for query in [f"SELECT COUNT(*) FROM tasks WHERE id='{task}'",f"SELECT COUNT(*) FROM task_events WHERE task_id='{task}'",f"SELECT COUNT(*) FROM task_event_outbox WHERE task_id='{task}'",f"SELECT COUNT(*) FROM task_event_receipts WHERE task_id='{task}'",f"SELECT COUNT(*) FROM projects WHERE id='{ids['project']}'",f"SELECT COUNT(*) FROM nodes WHERE id='{ids['node']}'",f"SELECT COUNT(*) FROM instances WHERE id='{ids['instance']}'"])
        check(remaining==0,'all temporary production MQ facts cleaned')
        report['database']={'version':version,'userTasks':int(db.query('SELECT COUNT(*) FROM tasks')),'pendingOutbox':int(db.query("SELECT COUNT(*) FROM task_event_outbox WHERE status<>'PUBLISHED'"))}
    nodes=release.get('nodes');instances=release.get('instances');real=[node for node in nodes if node['dataSource']=='HOST'];mock=[node for node in nodes if node['dataSource']=='MOCK']
    check(len(real)>=2,'real control and workload nodes present')
    now=datetime.now(timezone.utc);facts=[]
    for node in real:
        checked=datetime.fromisoformat(node['lastSuccessfulSyncAt'].replace('Z','+00:00'))
        age=(now-checked).total_seconds()
        check(node['status']=='ONLINE' and node['syncStatus']=='OK' and -5<=age<180,'real node snapshot fresh '+node['id'])
        health=release.get('agent-gateway/nodes/'+node['id']+'/health')
        if operations:
            check(health.get('controlEnabled') is True and health.get('reviewEnabled') is True,'Agent control and review enabled '+node['id'])
        else:
            check(health.get('controlEnabled') is False and health.get('readOnly') is True,'Agent remains read-only '+node['id'])
        facts.append({'nodeId':node['id'],'status':node['status'],'source':node['dataSource'],'snapshotAgeSeconds':round(age,3),'memoryPercent':node['memoryBytes']*100/node['memoryTotalBytes']})
    report['inventory']={'realHostNodes':facts,'legacyMockNodes':len(mock),'instances':len(instances)}
    summary=release.get('monitoring/summary');rules={a['rule_key'] for a in summary['alerts']}
    expected={'NODE_OFFLINE','CPU_HIGH','MEMORY_HIGH','TASK_FAILURES','OUTBOX_BACKLOG','MQ_QUEUE_BACKLOG','MQ_DEAD_LETTERS'}
    check(summary['enabled'] and expected<=rules,'all seven monitoring rules evaluated')
    report['monitoring']={'ruleKeys':sorted(rules),'activeRules':[a['rule_key'] for a in summary['alerts'] if a['state']=='ACTIVE'],
        'memoryAlerts':[{k:a[k] for k in ('resource_id','state','observation_state','observed_value','threshold_value','checked_at')} for a in summary['alerts'] if a['rule_key']=='MEMORY_HIGH' and a['resource_id'] in {n['id'] for n in real}]}
    for port in (80,9090):
        request=urllib.request.Request('http://127.0.0.1:'+str(port)+'/',headers={'Host':http_host})
        with urllib.request.urlopen(request,timeout=5) as response:
            check(response.status==200 and hashlib.sha256(response.read()).hexdigest()==state['staticIndexSha256'],'static entry '+str(port)+' serves candidate index')
    queue=json.loads(release.call(['java','-cp','/tmp:/tmp/amqp-client-5.21.0.jar:/tmp/slf4j-api-2.0.16.jar','BrokerSmoke','/etc/argus/messaging/client.env','queue-status']))
    check(queue['ready']==0 and queue['dead']==0 and queue['consumers']>=1,'production queues clean and consumer connected')
    check(release.call(['systemctl','is-active','argus-mq-tunnel.service'])=='active','persistent SSH tunnel active')
    report.update(passed=True,sourceCommit=state['sourceCommit'],artifactSha256=state['artifactSha256'],gray=state['gray'],queue=queue,staticIndexSha256=state['staticIndexSha256'],writeGuardStatus=state['writeGuardStatus'],checkedAt=now.isoformat())
finally:
    (release.ROOT/'final-audit.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report))
