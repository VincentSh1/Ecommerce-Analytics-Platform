#!/usr/bin/env python3
"""Export existing local consumer outcome logs and cross-check benchmark replay evidence."""
import collections
import csv
import datetime as dt
import json
import math
import re
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent / 'benchmarks' / 'results'
records = []
errors = collections.Counter()
warning_times = {}
for service in ('analytics-service', 'ingestion-service', 'localstack'):
    output = subprocess.run(['docker', 'logs', 'ecommerce-benchmark-'+service+'-1'],
                            text=True, capture_output=True, check=True)
    for line in (output.stdout+'\n'+output.stderr).splitlines():
        try:
            entry = json.loads(line)
        except json.JSONDecodeError:
            continue
        if entry.get('message') == 'record_outcome':
            records.append({k:entry.get(k,'') for k in ('@timestamp','outcome','eventId')})
        if entry.get('level') in ('WARN','ERROR'):
            key = (service,entry.get('message',''),entry.get('failureClass',''))
            errors[key] += 1
            first, last = warning_times.get(key, (entry.get('@timestamp'), entry.get('@timestamp')))
            warning_times[key] = (first, entry.get('@timestamp'))
with (root/'consumer-outcomes.csv').open('w') as file:
    writer = csv.DictWriter(file,fieldnames=('@timestamp','outcome','eventId'))
    writer.writeheader()
    writer.writerows(records)
committed = collections.Counter(r['eventId'] for r in records if r['outcome']=='COMMITTED')
duplicated = collections.Counter(r['eventId'] for r in records if r['outcome']=='DUPLICATE')
report = {'applicationWarnings':[{'service':s,'message':m,'failureClass':f,'count':n,'firstAt':warning_times[(s,m,f)][0],'lastAt':warning_times[(s,m,f)][1]} for (s,m,f),n in errors.items()], 'runs':{}}
for path in sorted(root.glob('*/result.json')):
    result = json.loads(path.read_text())
    with (path.parent/'measured-requests.csv').open() as file:
        requests = list(csv.DictReader(file))
    ids = {r['eventId'] for r in requests if r['status']=='202'}
    attempted_ids = {r['eventId'] for r in requests}
    committed_ids = attempted_ids & committed.keys()
    replay = sorted(committed_ids)[:10]
    def epoch(value):
        normalized = re.sub(r'\.(\d+)', lambda m: '.'+m.group(1)[:6].ljust(6,'0'), value)
        return dt.datetime.fromisoformat(normalized.replace('Z','+00:00')).timestamp()

    if result.get('startedAt'):
        start = epoch(result['startedAt'])
        intervals = collections.defaultdict(lambda: dict(attempted=0, accepted=0, errors=0, committed=0))
        for request in requests:
            intervals[math.floor(float(request['start']))]['attempted'] += 1
            intervals[math.floor(float(request['end']))]['accepted' if request['status']=='202' else 'errors'] += 1
        for record in records:
            if record['outcome']=='COMMITTED' and record['eventId'] in attempted_ids:
                intervals[math.floor(epoch(record['@timestamp'])-start)]['committed'] += 1
        with (path.parent/'intervals.csv').open('w') as file:
            writer = csv.DictWriter(file,fieldnames=('second','attempted','accepted','errors','committed'))
            writer.writeheader()
            writer.writerows(dict(second=i,**intervals[i]) for i in range(min(0,min(intervals)),max(intervals)+1))
    times = sorted(dt.datetime.fromisoformat(re.sub(r'\.(\d+)', lambda m: '.'+m.group(1)[:6].ljust(6,'0'), r['@timestamp']).replace('Z','+00:00')).timestamp()
                   for r in records if r['outcome']=='COMMITTED' and r['eventId'] in ids)
    gaps = [b-a for a,b in zip(times,times[1:])]
    report['runs'][path.parent.name] = dict(
        acceptedIds=len(ids), durableMarkerCountMatches=len(committed_ids)==result['reconciliation']['markers'], committedExactlyOnce=all(committed[i]==1 for i in ids),
        duplicateOutcomesObserved=all(duplicated[i]>=1 for i in replay),
        firstCommit=times[0] if times else None,lastCommit=times[-1] if times else None,
        meanInterCommitMs=1000*sum(gaps)/len(gaps) if gaps else None)
(root/'log-verification.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report['runs'],indent=2))
if not all(r['committedExactlyOnce'] and r['duplicateOutcomesObserved'] and r['durableMarkerCountMatches'] for r in report['runs'].values()):
    raise SystemExit('Consumer log cross-check failed')
