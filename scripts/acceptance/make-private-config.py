"""Prepare local private acceptance config. Never put generated files in Git."""
import json
from pathlib import Path
import secrets
import sys

def envfile(path):
    return dict(line.strip().split('=',1) for line in Path(path).read_text(encoding='utf-8-sig').splitlines() if line.strip() and not line.startswith('#'))

mysql,mq=envfile(sys.argv[1]),envfile(sys.argv[2])
directory=Path(sys.argv[3]).resolve()
if directory.name != 'stage2-acceptance-20261019' or directory.parent.name != 'local-private':
    raise SystemExit('private output directory required')
directory.mkdir(parents=True,exist_ok=True)
if (directory/'central.env').exists():
    raise SystemExit('config exists; reuse credentials rather than silently replacing')
tokens={name:secrets.token_hex(32) for name in ('reader','operator','agentReader','agentOperator','proxy')}
central={
    'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':'19091','SPRING_PROFILES_ACTIVE':'mysql',
    'MYSQL_HOST':'127.0.0.1','MYSQL_PORT':'23306','MYSQL_DATABASE':'argus_test_acceptance','MYSQL_USER':mysql['MYSQL_USER'],'MYSQL_PASSWORD':mysql['MYSQL_PASSWORD'],
    'ARGUS_DEMO_DATA_ENABLED':'false','ARGUS_IDENTITY_TEST_DATA_ENABLED':'false','ARGUS_API_READ_ONLY':'false',
    'ARGUS_API_AUTH_REQUIRED':'true','ARGUS_API_ACCESS_TOKEN':tokens['reader'],'ARGUS_API_CONTROL_TOKEN':tokens['operator'],
    'ARGUS_AGENT_GATEWAY_ENABLED':'true','ARGUS_AGENT_GATEWAY_READ_ONLY':'true','ARGUS_AGENT_GATEWAY_ALLOW_CONTROL':'true',
    'ARGUS_AGENT_GATEWAY_ALLOWED_HOSTS':'127.0.0.1','ARGUS_AGENT_AUTH_TOKEN':tokens['agentReader'],
    'ARGUS_AGENT_CONTROL_TOKEN':tokens['agentOperator'],'ARGUS_AGENT_GATEWAY_CONNECT_TIMEOUT':'500ms','ARGUS_AGENT_GATEWAY_READ_TIMEOUT':'5s',
    'ARGUS_AGENT_SYNC_INITIAL_DELAY_MS':'2000','ARGUS_AGENT_SYNC_INTERVAL_MS':'2000',
    'ARGUS_TASK_CONTROL_ENABLED':'true','ARGUS_TASK_ALLOW_DOCKER':'true','ARGUS_TASK_ALLOWED_INSTANCE_IDS':'acceptance-instance',
    'ARGUS_TASK_LEASE':'20s','ARGUS_TASK_RETRY_DELAY':'1s','ARGUS_TASK_COMMAND_TTL':'30s','ARGUS_TASK_CONFIRMATION_GRACE':'10s',
    'ARGUS_TASK_REVIEW_ENABLED':'true','ARGUS_TASK_REVIEW_ALLOWED_INSTANCE_IDS':'acceptance-instance',
    'ARGUS_MONITORING_ENABLED':'true','ARGUS_MONITORING_INTERVAL_MS':'5000','ARGUS_MONITORING_STALE_SECONDS':'10',
    'ARGUS_MQ_ENABLED':'true','SPRING_RABBITMQ_HOST':'127.0.0.1','SPRING_RABBITMQ_PORT':'25672',
    'SPRING_RABBITMQ_USERNAME':mq['RABBITMQ_DEFAULT_USER'],'SPRING_RABBITMQ_PASSWORD':mq['RABBITMQ_DEFAULT_PASS'],
    'ARGUS_AI_ENABLED':'false','ARGUS_REDIS_ENABLED':'false',
}
agent={
    'server.port':'18090','server.bind-address':'127.0.0.1','server.max-http-threads':'8',
    'node.id':'acceptance-agent','node.name':'acceptance-agent','auth.token':tokens['agentReader'],
    'executor.mock':'false','docker.executable':'docker','executor.timeout-seconds':'20',
    'control.enabled':'true','control.token':tokens['agentOperator'],'control.instances':'argus-acceptance-fixture',
    'review.enabled':'true','review.instances':'argus-acceptance-fixture',
    'task.directory':'/opt/argus/acceptance-20261019/inbox','instance.discovery':'false',
    'instance.id':'argus-acceptance-fixture','instance.container':'argus-acceptance-fixture',
}
for file,values in [('central.env',central),('agent.properties',agent),('proxy.env',{'ACCEPTANCE_PROXY_TOKEN':tokens['proxy']})]:
    (directory/file).write_text(''.join(f'{k}={v}\n' for k,v in values.items()),encoding='utf-8')
(directory/'browser.json').write_text(json.dumps({'baseUrl':'http://127.0.0.1:29090','proxyUrl':'http://127.0.0.1:28091','tokens':tokens},indent=2),encoding='utf-8')
print('Private config created; values not printed')
