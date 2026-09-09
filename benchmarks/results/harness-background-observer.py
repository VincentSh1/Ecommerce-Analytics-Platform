#!/usr/bin/env python3
"""LocalStack-only benchmark. Run inside the benchmark Compose LocalStack container."""
import argparse
import collections
import concurrent.futures
import csv
import datetime as dt
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import threading
import time
import uuid

COUNTERS = ('grossMinor', 'refundMinor', 'completedCount', 'refundCount', 'paidUnits', 'refundedUnits', 'eventCount')
CATEGORIES = ('BOOKS', 'ELECTRONICS', 'HOME', 'CLOTHING', 'OTHER')
REGIONS = ('NA', 'EU', 'APAC', 'OTHER')


def timestamp(value):
    return value.isoformat(timespec='milliseconds').replace('+00:00', 'Z')


def identifier(seed, index, kind):
    return str(uuid.UUID(bytes=hashlib.sha256(f'{seed}:{index}:{kind}'.encode()).digest()[:16], version=4))


def event(seed, index, occurred):
    # Every tenth fact refunds the preceding payment in full; IDs are deterministic UUID v4.
    order = index - 1 if index % 10 == 9 else index
    return dict(schemaVersion=1, eventId=identifier(seed, index, 'event'),
                eventType='REFUND_ISSUED' if index % 10 == 9 else 'PAYMENT_COMPLETED',
                orderId=identifier(seed, order, 'order'), occurredAt=occurred,
                amountMinor=100 + (order * 7919 % 100000), currency='USD',
                productCategory=CATEGORIES[order % 5], region=REGIONS[order % 4], quantity=1 + order % 3)


def expected_items(events, dataset):
    totals = collections.defaultdict(lambda: dict.fromkeys(COUNTERS, 0))
    for e in events:
        stripe = hashlib.sha256(e['eventId'].encode()).digest()[0] % 16
        minute = e['occurredAt'][:16] + ':00.000Z'
        payment = e['eventType'] == 'PAYMENT_COMPLETED'
        for dimension in ('TOTAL', 'CAT#' + e['productCategory'], 'REG#' + e['region']):
            key = (f'D#{dataset}#{dimension}#USD#{minute[:10]}#{stripe:02}', minute)
            item = totals[key]
            item['eventCount'] += 1
            item['grossMinor' if payment else 'refundMinor'] += e['amountMinor']
            item['completedCount' if payment else 'refundCount'] += 1
            item['paidUnits' if payment else 'refundedUnits'] += e['quantity']
    return dict(totals)


def percentile(values, p):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * p) - 1)] if ordered else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--rate', type=float, required=True)
    parser.add_argument('--duration', type=float, default=60)
    parser.add_argument('--count', type=int)
    parser.add_argument('--concurrency', type=int, default=16)
    parser.add_argument('--warmup', type=float, default=20)
    parser.add_argument('--drain', type=float, default=600)
    parser.add_argument('--seed', default=None)
    args = parser.parse_args()
    if not all(x > 0 for x in (args.rate, args.duration, args.concurrency, args.drain)) or args.warmup < 0 or (args.count is not None and args.count < 1):
        parser.error('rate, duration, concurrency, drain and count must be positive; warmup must be nonnegative')
    if not args.name.replace('-', '').replace('_', '').isalnum():
        parser.error('name must contain letters, digits, hyphens or underscores')
    # No configurable AWS destination or credential provider: this tool cannot target AWS.
    import boto3
    db = boto3.client('dynamodb', endpoint_url='http://localhost:4566', region_name='us-east-1',
                      aws_access_key_id='localstack', aws_secret_access_key='localstack')
    dataset = os.environ['DATASET_ID']
    tables = {k: os.environ[v] for k, v in [('aggregates', 'ANALYTICS_TABLE'), ('processed', 'PROCESSED_TABLE'), ('quarantine', 'QUARANTINE_TABLE')]}
    output = Path('/results') / args.name
    output.mkdir(exist_ok=False)
    seed = args.seed or args.name
    occurred = timestamp(dt.datetime.now(dt.timezone.utc).replace(second=0, microsecond=0) - dt.timedelta(minutes=1))
    manifest = dict(vars(args), seed=seed, occurredAt=occurred, startedAt=timestamp(dt.datetime.now(dt.timezone.utc)),
                    generator='sha256(seed:index:kind), first 16 bytes with UUID v4 bits',
                    harnessSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), dataset=dataset,
                    tables=tables, observer='background-thread', samplingSeconds=5, httpTimeoutSeconds=8, httpRetries=0)
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')

    def scan(table):
        result = {}
        cursor = {}
        while True:
            page = db.scan(TableName=table, ConsistentRead=True, **cursor)
            for item in page['Items']:
                if item['PK']['S'].startswith(f'D#{dataset}#'):
                    result[(item['PK']['S'], item['SK']['S'])] = item
            if 'LastEvaluatedKey' not in page:
                return result
            cursor = {'ExclusiveStartKey': page['LastEvaluatedKey']}

    def count(minute):
        total = 0
        for stripe in range(16):
            key = f'D#{dataset}#TOTAL#USD#{minute[:10]}#{stripe:02}'
            item = db.get_item(TableName=tables['aggregates'], ConsistentRead=True,
                               Key={'PK': {'S': key}, 'SK': {'S': minute}}).get('Item', {})
            total += int(item.get('eventCount', {'N': '0'})['N'])
        return total

    thread_state = threading.local()

    def send(index, payload, origin):
        started = time.monotonic()
        try:
            if not getattr(thread_state, 'connection', None):
                thread_state.connection = http.client.HTTPConnection('ingestion-service', 8081, timeout=8)
            connection = thread_state.connection
            connection.request('POST', '/api/v1/events', json.dumps(payload, separators=(',', ':')),
                               {'Content-Type': 'application/json'})
            response = connection.getresponse()
            body = json.loads(response.read())
            status = response.status
            code = body.get('error', {}).get('code', '')
            if status == 202 and (body.get('eventId') != payload['eventId'] or body.get('status') != 'ACCEPTED'):
                code, status = 'INVALID_ACK', 0
        except Exception as error:
            code, status = type(error).__name__, 0
            if getattr(thread_state, 'connection', None):
                thread_state.connection.close()
            thread_state.connection = None
        ended = time.monotonic()
        return dict(index=index, eventId=payload['eventId'], status=status, code=code,
                    start=started-origin, end=ended-origin, latencyMs=(ended-started)*1000)

    def phase(name, seconds, phase_seed, minute, limit=None):
        planned = min(math.ceil(args.rate * seconds), limit or math.ceil(args.rate * seconds))
        baseline = count(minute)
        quarantine_before = len(scan(tables['quarantine']))
        requests, samples, skipped = [], [], 0
        pending = set()
        phase_started = timestamp(dt.datetime.now(dt.timezone.utc))
        origin = time.monotonic()
        stop_observer = threading.Event()
        observer_errors = []

        def observe():
            while not stop_observer.wait(5):
                try:
                    sampled = count(minute) - baseline
                    samples.append(dict(seconds=time.monotonic()-origin, processed=sampled))
                except Exception as error:
                    observer_errors.append(type(error).__name__)
                    return

        observer = threading.Thread(target=observe, name='aggregate-observer', daemon=True)
        observer.start()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
            for index in range(planned):
                target = origin + index / args.rate
                if target > time.monotonic():
                    time.sleep(target-time.monotonic())
                done = {f for f in pending if f.done()}
                requests.extend(f.result() for f in done)
                pending -= done
                if len(pending) >= args.concurrency or time.monotonic() - target > 1:
                    skipped += 1
                else:
                    pending.add(pool.submit(send, index, event(phase_seed, index, minute), origin))
            requests.extend(f.result() for f in pending)
            remaining = min(seconds, planned / args.rate) - (time.monotonic()-origin)
            if remaining > 0:
                time.sleep(remaining)
        load_end = time.monotonic()-origin
        wall_seconds = (dt.datetime.now(dt.timezone.utc)-dt.datetime.fromisoformat(phase_started.replace('Z','+00:00'))).total_seconds()
        stop_observer.set()
        observer.join(timeout=30)
        if observer.is_alive() or observer_errors:
            raise RuntimeError('Aggregate observer failed: '+str(observer_errors))
        for sample in samples:
            sample['accepted'] = sum(r['status']==202 and r['end']<=sample['seconds'] for r in requests)
        at_end = count(minute)-baseline
        observation_seconds = time.monotonic()-origin
        samples.append(dict(seconds=time.monotonic()-origin, processed=at_end,
                            accepted=sum(r['status'] == 202 for r in requests)))
        accepted = sum(r['status'] == 202 for r in requests)
        drain_end = time.monotonic()+args.drain
        while count(minute)-baseline < accepted and time.monotonic() < drain_end:
            time.sleep(5)
            if len(scan(tables['quarantine'])) > quarantine_before:
                break
            samples.append(dict(seconds=time.monotonic()-origin, processed=count(minute)-baseline, accepted=accepted))
        # A quiet period catches late ambiguous publications and repeated effects before reconciliation.
        time.sleep(5)
        total_time = time.monotonic()-origin
        processed = count(minute)-baseline
        with (output / f'{name}-requests.csv').open('w') as f:
            writer = csv.DictWriter(f, fieldnames=('index', 'eventId', 'status', 'code', 'start', 'end', 'latencyMs'))
            writer.writeheader()
            writer.writerows(sorted(requests, key=lambda r: r['index']))
        (output / f'{name}-samples.json').write_text(json.dumps(samples, indent=2)+'\n')
        result = dict(startedAt=phase_started, clockContinuous=abs(wall_seconds-load_end)<2, wallSeconds=wall_seconds, planned=planned, attempted=len(requests), skipped=skipped, accepted=accepted,
                      statuses=dict(collections.Counter(str(r['status']) for r in requests)),
                      errors=dict(collections.Counter(r['code'] for r in requests if r['status'] != 202)),
                      processedAtLoadEnd=at_end, processedAfterDrain=processed, backlogAtLoadEnd=max(0, accepted-at_end),
                      loadSeconds=load_end, observationSeconds=observation_seconds, totalSeconds=total_time, acceptedPerSecond=accepted/load_end,
                      processedPerSecondDuringLoad=at_end/observation_seconds, processedPerSecondIncludingDrain=processed/total_time,
                      errorRate=(len(requests)-accepted)/len(requests) if requests else 0,
                      latencyMs={key: percentile([r['latencyMs'] for r in requests], p) for key,p in [('p50',.5),('p95',.95),('p99',.99)]})
        print(name, json.dumps(result), flush=True)
        return result, requests

    if args.warmup:
        warm_minute = timestamp(dt.datetime.fromisoformat(occurred.replace('Z','+00:00'))-dt.timedelta(minutes=1))
        warm, _ = phase('warmup', args.warmup, seed+'-warmup', warm_minute)
        (output / 'warmup.json').write_text(json.dumps(warm, indent=2)+'\n')
        if warm['processedAfterDrain'] != warm['accepted']:
            raise RuntimeError('Warmup did not drain; stop rather than contaminate measurement')
    before = {key: scan(table) for key,table in tables.items()}
    prior_ids = {v['eventId']['S'] for k,v in before['processed'].items() if k[1] == 'STATE'}
    planned_count = min(math.ceil(args.rate * args.duration), args.count or math.ceil(args.rate * args.duration))
    if any(identifier(seed, i, 'event') in prior_ids for i in range(planned_count)):
        raise RuntimeError('Seed already exists; use a new name/seed')
    result, requests = phase('measured', args.duration, seed, occurred, args.count)
    after = {key: scan(table) for key,table in tables.items()}
    new_processed = {k:v for k,v in after['processed'].items() if k not in before['processed']}
    markers = {v['eventId']['S'] for k,v in new_processed.items() if k[1] == 'STATE'}
    attempted = {r['eventId']:event(seed,r['index'],occurred) for r in requests}
    accepted_ids = {r['eventId'] for r in requests if r['status'] == 202}
    expected = expected_items([attempted[i] for i in markers if i in attempted], dataset)
    actual = {}
    for key in set(before['aggregates']) | set(after['aggregates']):
        difference = {field:int(after['aggregates'].get(key,{}).get(field,{'N':'0'})['N'])-
                      int(before['aggregates'].get(key,{}).get(field,{'N':'0'})['N']) for field in COUNTERS}
        if any(difference.values()):
            actual[key] = difference
    recent = [v['event']['M']['eventId']['S'] for k,v in new_processed.items() if k[1] != 'STATE']
    result['reconciliation'] = dict(markers=len(markers), missingAccepted=len(accepted_ids-markers),
        unexpectedIds=len(markers-attempted.keys()), ambiguousProcessed=len(markers-accepted_ids),
        aggregatesMatch=actual == expected, recentMatch=collections.Counter(recent)==collections.Counter(markers),
        newQuarantine=len(after['quarantine'].keys()-before['quarantine'].keys()))
    # Replay actual committed events only after all load has drained, and compare the full dataset.
    replay = list(sorted(markers & attempted.keys()))[:10]
    duplicate_responses = [send(i,attempted[eid],time.monotonic()) for i,eid in enumerate(replay)]
    time.sleep(5)
    duplicate_after = {key:scan(table) for key,table in tables.items()}
    result['duplicateCheck'] = dict(sent=len(replay), accepted=sum(r['status']==202 for r in duplicate_responses),
                                    unchanged=after==duplicate_after)
    checks = result['reconciliation']
    result['correct'] = (checks['missingAccepted']==0 and checks['unexpectedIds']==0 and checks['newQuarantine']==0
                         and checks['aggregatesMatch'] and checks['recentMatch'] and bool(replay)
                         and result['duplicateCheck']['unchanged'] and result['duplicateCheck']['accepted']==len(replay))
    (output / 'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print('RESULT', json.dumps(result), flush=True)
    if not result['correct']:
        raise SystemExit('Correctness reconciliation failed')


if __name__ == '__main__':
    main()
