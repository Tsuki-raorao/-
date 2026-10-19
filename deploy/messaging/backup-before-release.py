"""Create private application DB/jar/static/env backup before production gray rollout.

Reads credentials only from the running service environment. All outputs are 0600.
Does not stop or reconfigure the application, database, or game servers.
"""
from datetime import datetime,timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

pid=int(subprocess.check_output(['systemctl','show','argus-control-center.service','--property=MainPID','--value'],text=True))
if pid<=0:raise SystemExit('service not running')
env=dict(value.split('=',1) for value in Path('/proc/'+str(pid)+'/environ').read_bytes().decode().split('\0') if '=' in value)
required=('MYSQL_HOST','MYSQL_USER','MYSQL_PASSWORD','MYSQL_DATABASE')
if any(not env.get(key) for key in required) or env['MYSQL_DATABASE']!='argus':raise SystemExit('expected application MySQL environment required')
if shutil.disk_usage('/opt/argus').free<512*1024*1024:raise SystemExit('insufficient backup/release disk reserve')
args=[value.decode() for value in Path('/proc/'+str(pid)+'/cmdline').read_bytes().split(b'\0') if value]
jar=Path(args[args.index('-jar')+1]).resolve()
if not jar.is_file() or not jar.is_relative_to(Path('/opt/argus')):raise SystemExit('unexpected live artifact')
root=Path('/opt/argus/backups')/('first-four-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
root.mkdir(parents=True,exist_ok=False);os.chmod(root,0o700)
def quoted(value):return '"'+value.replace('\\','\\\\').replace('"','\\"').replace('\n','\\n').replace('\r','\\r')+'"'
client=root/'mysql-client.cnf'
client.write_text('[client]\n'+''.join(key+'='+quoted(value)+'\n' for key,value in
    {'host':env['MYSQL_HOST'],'port':env.get('MYSQL_PORT','3306'),'user':env['MYSQL_USER'],'password':env['MYSQL_PASSWORD']}.items()))
os.chmod(client,0o600)
manifest={'scope':'PRIVATE_PRE_RELEASE_BACKUP','database':'argus','createdAt':datetime.now(timezone.utc).isoformat(),'files':{}}
try:
    dump=root/'database.sql.gz'
    with (root/'mysqldump.log').open('wb') as errors:
        proc=subprocess.Popen(['mysqldump','--defaults-extra-file='+str(client),'--single-transaction','--skip-lock-tables',
            '--no-tablespaces','--set-gtid-purged=OFF','--hex-blob','--routines','--events','--default-character-set=utf8mb4','argus'],stdout=subprocess.PIPE,stderr=errors)
        with gzip.open(dump,'wb') as output:shutil.copyfileobj(proc.stdout,output)
        proc.stdout.close()
        if proc.wait()!=0:raise RuntimeError('mysqldump failed; private error log retained')
    shutil.copy2(jar,root/'previous-control.jar')
    envdir=root/'environment';envdir.mkdir(mode=0o700)
    envpaths=['/etc/argus/argus-control-center.env','/etc/argus/central-release/central-release-20261009T004000.env','/etc/argus/central-release/argus-ai-v1-20261010.env']
    for index,path in enumerate(envpaths):
        source=Path(path)
        if source.is_file():shutil.copy2(source,envdir/(str(index)+'-'+source.name))
    (root/'systemd-unit.txt').write_bytes(subprocess.check_output(['systemctl','cat','argus-control-center.service']))
    subprocess.run(['tar','-czf',str(root/'previous-frontend.tgz'),'-C','/var/www/argus','.'],check=True,capture_output=True)
    version=subprocess.check_output(['mysql','--defaults-extra-file='+str(client),'--batch','--skip-column-names','argus','-e',
        'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1'],text=True).strip()
    manifest['flywayVersionBefore']=version
    for path in root.rglob('*'):
        if path.is_file():
            os.chmod(path,0o600)
            if path==client:continue  # Ephemeral credential file is deliberately removed below.
            manifest['files'][str(path.relative_to(root))]={'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2));os.chmod(root/'manifest.json',0o600)
finally:
    client.unlink(missing_ok=True)
print(json.dumps({'backupDirectory':str(root),'flywayVersionBefore':manifest.get('flywayVersionBefore'),'databaseBackupBytes':(root/'database.sql.gz').stat().st_size,'liveServiceUnchanged':True}))
