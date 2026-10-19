"""Prepare an independent database and private environment for collection load."""
from pathlib import Path
import os
import subprocess

ROOT=Path('/opt/argus/acceptance-20261019')
env=dict(line.split('=',1) for line in (ROOT/'performance.env').read_text().splitlines() if '=' in line)
if env.get('MYSQL_DATABASE')!='argus_test_perf':raise SystemExit('isolated base environment required')
subprocess.run(['docker','exec','argus-stage1-mysql','sh','-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -e "$1"','collection-setup',
    "CREATE DATABASE IF NOT EXISTS argus_test_collection CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL ON argus_test_collection.* TO 'arguscheck'@'%';"],check=True,capture_output=True)
env.update(MYSQL_DATABASE='argus_test_collection',SERVER_PORT='0',ARGUS_AGENT_GATEWAY_ENABLED='true',
    ARGUS_AGENT_SYNC_INITIAL_DELAY_MS='3600000',ARGUS_AGENT_SYNC_INTERVAL_MS='3600000',
    ARGUS_TASK_INITIAL_DELAY_MS='3600000',ARGUS_TASK_REVIEW_INITIAL_DELAY_MS='3600000',
    ARGUS_AGENT_GATEWAY_READ_TIMEOUT='3s',ARGUS_AGENT_SYNC_CONCURRENCY='4')
target=ROOT/'collection.env';target.write_text(''.join(k+'='+v+'\n' for k,v in env.items()));os.chmod(target,0o600)
print('Isolated collection environment prepared; no values printed')
