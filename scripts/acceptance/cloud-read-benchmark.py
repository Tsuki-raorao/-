"""Bounded, read-only HTTP workload against the isolated loopback performance service.

Includes JSON parsing/validation. Raw samples are private; summaries contain no tokens.
This is cloud-hosted synthetic load, not real user production traffic.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import csv
import json
from pathlib import Path
import threading
import time
import urllib.request

ROOT=Path('/opt/argus/acceptance-20261019')
parser=argparse.ArgumentParser()
parser.add_argument('--seconds',type=int,default=300)
parser.add_argument('--concurrency',type=int,default=20)
parser.add_argument('--label',choices=('before','after','step5','step10','step40'),required=True)
args=parser.parse_args()
if not 1<=args.seconds<=600 or not 1<=args.concurrency<=50:raise SystemExit('bounded load required')
env=dict(line.split('=',1) for line in (ROOT/'performance.env').read_text().splitlines() if '=' in line)
if env.get('MYSQL_DATABASE')!='argus_test_perf' or env.get('SERVER_PORT')!='19092' or env.get('ARGUS_API_READ_ONLY')!='true':
    raise SystemExit('dedicated read-only performance environment required')
token=env['ARGUS_API_ACCESS_TOKEN']
paths=('/api/nodes','/api/instances','/api/tasks','/api/logs?limit=100','/api/logs?instanceId=perf-instance-0&limit=100')
output=ROOT/('performance-'+args.label)
output.mkdir(exist_ok=True)
samples=[]
start=time.perf_counter()
deadline=start+args.seconds
barrier=threading.Barrier(args.concurrency)

def request(path):
    request=urllib.request.Request('http://127.0.0.1:19092'+path,headers={'Authorization':'Bearer '+token})
    with urllib.request.urlopen(request,timeout=10) as response:
        body=json.load(response)
        if response.status!=200 or body.get('code')!=0 or not isinstance(body.get('data'),list):
            raise ValueError('invalid business response')
        # A fast empty/error response must not masquerade as successful throughput.
        if not body['data']:raise ValueError('fixture dataset unexpectedly empty')

def worker(worker_id):
    values=[]
    barrier.wait()
    index=worker_id
    while time.perf_counter()<deadline:
        path=paths[index%len(paths)];index+=1
        began=time.perf_counter();error=''
        try:request(path)
        except Exception as failure:error=type(failure).__name__
        values.append((round(time.perf_counter()-start,6),path,round((time.perf_counter()-began)*1000,3),error))
    return values

# A short warmup validates all five paths before the timed run.
for path in paths:
    for _ in range(3):request(path)
start=time.perf_counter();deadline=start+args.seconds
with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
    for values in executor.map(worker,range(args.concurrency)):samples.extend(values)
elapsed=time.perf_counter()-start
samples.sort(key=lambda value:value[0])
with (output/'requests.csv').open('w',newline='') as stream:
    writer=csv.writer(stream);writer.writerow(('elapsed_seconds','path','latency_ms','error_type'));writer.writerows(samples)

def stats(values):
    latencies=sorted(v[2] for v in values)
    def percentile(p):return latencies[min(len(latencies)-1,max(0,int(len(latencies)*p+0.999999)-1))] if latencies else None
    errors=sum(bool(v[3]) for v in values)
    return {'requests':len(values),'errors':errors,'errorRate':errors/len(values) if values else None,
            'p50ms':percentile(.50),'p95ms':percentile(.95),'p99ms':percentile(.99),'maxMs':max(latencies) if latencies else None}

summary={'scope':'ISOLATED_CLOUD_SYNTHETIC_READ_LOAD','database':'argus_test_perf',
         'label':args.label,'concurrency':args.concurrency,'configuredSeconds':args.seconds,'elapsedSeconds':round(elapsed,3),
         'requestsPerSecond':round(len(samples)/elapsed,3),'overall':stats(samples),
         'endpoints':{path:stats([v for v in samples if v[1]==path]) for path in paths}}
summary['targetPassed']=bool(samples) and summary['overall']['p95ms']<=500 and summary['overall']['errorRate']<.001
(output/'summary.json').write_text(json.dumps(summary,indent=2))
print(json.dumps(summary),flush=True)
