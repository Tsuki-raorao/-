"""Restore a private logical backup ONLY into the named empty acceptance database."""
import gzip
import os
from pathlib import Path
import re
import subprocess

ROOT=Path('/opt/argus/acceptance-20261019')
source=Path('/tmp/argus-first-four-20261019.sql.gz');os.chmod(source,0o600)
data=gzip.decompress(source.read_bytes())
if re.search(rb'^\s*(USE\s|CREATE\s+DATABASE\s|DROP\s+DATABASE\s)',data,re.I|re.M):raise SystemExit('cross-database statements refused')
def sql(query):
    return subprocess.check_output(['docker','exec','argus-stage1-mysql','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names -e "$1"','restore-release-copy',query],text=True).strip()
sql("CREATE DATABASE IF NOT EXISTS argus_test_release CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; GRANT ALL ON argus_test_release.* TO 'arguscheck'@'%';")
if sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='argus_test_release'")!='0':raise SystemExit('refuse to replace nonempty restore database')
subprocess.run(['docker','exec','-i','argus-stage1-mysql','sh','-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 argus_test_release'],input=data,check=True,capture_output=True)
env=dict(row.split('=',1) for row in (ROOT/'performance.env').read_text().splitlines() if '=' in row)
env.update(MYSQL_DATABASE='argus_test_release')
target=ROOT/'release-copy.env';target.write_text(''.join(k+'='+v+'\n' for k,v in env.items()));os.chmod(target,0o600)
print('Private production backup restored into isolated argus_test_release; no production credentials copied')
