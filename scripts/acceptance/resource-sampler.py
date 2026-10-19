"""Sample cgroup-v2 CPU/memory for the isolated control center and MySQL only."""
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT=Path('/opt/argus/acceptance-20261019')
seconds=int(sys.argv[1]);label=sys.argv[2]
if not 1<=seconds<=600 or label not in ('before','after'):raise SystemExit('bounded measurement required')
mysql_id=subprocess.check_output(['docker','inspect','--format','{{.Id}}','argus-stage1-mysql'],text=True).strip()
groups={'control':Path('/sys/fs/cgroup/system.slice/argus-acceptance-perf.service'),
        'mysql':Path('/sys/fs/cgroup/system.slice/docker-'+mysql_id+'.scope')}
samples=[];began=time.monotonic()
while time.monotonic()-began<seconds:
    row={'elapsedSeconds':round(time.monotonic()-began,3)}
    for name,group in groups.items():
        cpu=dict(line.split() for line in (group/'cpu.stat').read_text().splitlines())
        row[name]={'cpuUsageUsec':int(cpu['usage_usec']),'throttledUsec':int(cpu.get('throttled_usec',0)),
                   'memoryBytes':int((group/'memory.current').read_text()),'cpuMax':(group/'cpu.max').read_text().strip()}
    mem=dict((line.split(':')[0],line.split(':')[1].strip()) for line in Path('/proc/meminfo').read_text().splitlines())
    row['hostMemoryAvailable']=mem['MemAvailable'];samples.append(row)
    (ROOT/('resources-'+label+'.json')).write_text(json.dumps(samples,indent=2))
    time.sleep(min(5,max(0,seconds-(time.monotonic()-began))))
print(json.dumps({'samples':len(samples),'label':label}))
