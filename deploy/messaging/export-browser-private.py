"""Explicit private export of the existing read-only token; redirect output to ignored file."""
import json
from pathlib import Path
import subprocess

pid=int(subprocess.check_output(['systemctl','show','argus-control-center.service','--property=MainPID','--value'],text=True))
env=dict(row.split('=',1) for row in Path('/proc/'+str(pid)+'/environ').read_bytes().decode().split('\0') if '=' in row)
if env.get('ARGUS_API_READ_ONLY')!='true':raise SystemExit('expected read-only production mode')
print(json.dumps({'scope':'PRODUCTION_READ_ONLY','token':env['ARGUS_API_ACCESS_TOKEN']}))
