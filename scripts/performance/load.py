#!/usr/bin/env python3
"""Open-arrival HTTP load generation, only inside the isolated performance stack.

Every scheduled arrival is either sent or explicitly recorded as client-unsent.
Request files contain status/timing/IDs, never passwords, tokens, or auth headers.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import json
import math
import os
import random
import re
import socket
import statistics
import threading
import time
import uuid
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib import error, request

if os.environ.get('CHARGEHUB_PERF_ISOLATED') != 'yes' or not re.fullmatch(r'chargehub_perf_[a-z0-9]+', os.environ.get('PERF_DATABASE', '')):
    raise SystemExit('Refusing load outside a generated, isolated chargehub_perf_* database/container network')
BASES = {'api': 'http://api:8080', 'gateway': 'http://gateway:18081', 'device': 'http://device:18082'}
PASSWORD, CONTROL = os.environ['PERF_PASSWORD'], os.environ['PERF_CONTROL_KEY']
LOCK = threading.Lock()
ROWS, USERS, ACTIVE, SESSION_RESULTS = [], [], {}, []
OUT = None


def now():
    return datetime.now(timezone.utc).isoformat()


def record(row):
    with LOCK:
        ROWS.append(row)
        OUT.write(json.dumps(row, separators=(',', ':')) + '\n')
        OUT.flush()


def call(user, method, path, body=None, *, target='api', phase='setup', arrival=None, due=None, operation=None):
    started = time.perf_counter()
    row = {'phase': phase, 'target': target, 'method': method, 'path': path, 'arrival': arrival,
           'operation': operation, 'startedAt': now(), 'queueMs': max(0, (started - due) * 1000) if due else 0}
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if user and user.get('token'):
        headers['Authorization'] = 'Bearer ' + user['token']
    if target != 'api':
        headers['X-Control-Key'] = CONTROL
    if method != 'GET':
        headers['Idempotency-Key'] = str(uuid.uuid4())
    raw = None if body is None else json.dumps(body, separators=(',', ':')).encode()
    data = None
    try:
        req = request.Request(BASES[target] + path, raw, headers, method=method)
        try:
            response = request.urlopen(req, timeout=5)
        except error.HTTPError as exception:
            response = exception
        with response:
            payload = json.loads(response.read(2 * 1024 * 1024))
            row.update(status=response.status, code=payload.get('code'), requestId=payload.get('requestId'))
            data = payload.get('data')
        row['classification'] = ('success' if 200 <= row['status'] < 300 else
                                 'business_rejection' if row['status'] in (400, 403, 404, 409, 422, 429) else
                                 'authentication_failure' if row['status'] == 401 else 'server_error')
    except (socket.timeout, TimeoutError):
        row.update(status=None, classification='timeout')
    except error.URLError as exception:
        row.update(status=None, classification='timeout' if isinstance(exception.reason, (socket.timeout, TimeoutError)) else 'transport_error')
    except (ValueError, OSError):
        row.update(status=None, classification='invalid_response')
    row['latencyMs'] = (time.perf_counter() - started) * 1000
    row['arrivalLatencyMs'] = row['queueMs'] + row['latencyMs']
    record(row)
    return row, data


def require(row, data, what):
    if row['classification'] != 'success':
        raise RuntimeError(what + ': ' + json.dumps({k: row.get(k) for k in ('status', 'classification', 'code')}))
    return data


def login(index):
    user = {'username': f'perf{index:04d}'}
    row, data = call(None, 'POST', '/v1/auth/token', {'username': user['username'], 'password': PASSWORD})
    value = require(row, data, 'login')
    user.update(token=value['accessToken'], expires=time.monotonic() + value['expiresIn'])
    return user


def refresh_login(user):
    if time.monotonic() > user['expires'] - 20:
        row, data = call(None, 'POST', '/v1/auth/token', {'username': user['username'], 'password': PASSWORD}, phase='cleanup')
        value = require(row, data, 'token refresh')
        user.update(token=value['accessToken'], expires=time.monotonic() + value['expiresIn'])


def poll(user, path, predicate, target='api', timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        row, data = call(user, 'POST' if target != 'api' else 'GET', path,
                         {} if target != 'api' else None, target=target, phase='cleanup')
        if row['classification'] == 'success' and predicate(data):
            return data
        time.sleep(.5)
    raise RuntimeError('Timed out: ' + path)


def schedule(total, rate, function, workers):
    start = time.perf_counter()
    semaphore = threading.BoundedSemaphore(workers)
    futures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for i in range(total):
            due = start + i / rate
            delay = due - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            # A delayed scheduler must catch up against the original clock,
            # rather than silently discard work while workers are available.
            # The bounded in-flight capacity is the only admission limit.
            if not semaphore.acquire(blocking=False):
                record({'phase':'load','arrival':i,'scheduledAtSeconds':i/rate,'classification':'client_unsent',
                        'reason':'client_capacity','queueMs':max(0,(time.perf_counter()-due)*1000),
                        'status':None,'latencyMs':None})
                continue
            def work(index=i, intended=due):
                try:
                    function(index, intended)
                finally:
                    semaphore.release()
            futures.append(pool.submit(work))
        # Keep the prescribed window, including its last arrival interval.
        remaining = start + total / rate - time.perf_counter()
        if remaining > 0:
            time.sleep(remaining)
        for future in futures:
            future.result()
    return time.perf_counter() - start


def mixed(args):
    # Deterministic offered operation mix. Rejections remain in every denominator.
    rng = random.Random(args.seed)
    operations = rng.choices(['stations','orders','benefits','quote','create','cancel','claim'],
                             weights=[35,25,10,15,7,6,2], k=round(args.duration * args.rate))
    def send(i, due):
        user = USERS[i % len(USERS)]
        op = operations[i]
        target = {'connectorId':str(10000 + i % len(USERS)), 'useMembership':False, 'couponId':None}
        method, path, body = 'GET', '/v1/stations', None
        if op == 'orders': path = '/v1/charge-orders?limit=20'
        elif op == 'benefits': path = '/v1/me/benefits'
        elif op == 'quote': method,path,body='POST','/v1/charge-quotes',{**target,'estimatedEnergyWh':20000}
        elif op == 'create': method,path,body='POST','/v1/charge-orders',{**target,'expectedTariffVersion':1}
        elif op == 'cancel':
            with LOCK: order_no = ACTIVE.get(user['username'])
            if order_no: method,path,body='POST','/v1/charge-orders/'+order_no+'/cancel',{}
            else: path='/v1/charge-orders?limit=20'
        elif op == 'claim': method,path,body='POST','/v1/coupon-templates/1/claims',{}
        row,data=call(user,method,path,body,phase='load',arrival=i,due=due,operation=op)
        if row['classification']=='success':
            with LOCK:
                if op=='create': ACTIVE[user['username']]=data['orderNo']
                elif op=='cancel' and method=='POST': ACTIVE.pop(user['username'],None)
    elapsed=schedule(len(operations),args.rate,send,args.workers)
    # Release all unpaid reservations before a later sessions phase.
    for user in USERS:
        no=ACTIVE.get(user['username'])
        if no:
            row,data=call(user,'POST','/v1/charge-orders/'+no+'/cancel',{},phase='cleanup')
            require(row,data,'mixed cleanup cancel')
    return elapsed, len(operations)


def start_session(index):
    user=USERS[index]
    selection={'connectorId':str(10000+index),'expectedTariffVersion':1,'useMembership':False,'couponId':None}
    row,data=call(user,'POST','/v1/charge-orders',selection)
    order=require(row,data,'session create')
    no=order['orderNo']
    row,data=call(user,'POST','/v1/charge-orders/'+no+'/payment',{})
    payment=require(row,data,'session payment')
    # The platform creates its independent channel trade asynchronously.
    poll(None,'/dev/payments/'+payment['payNo']+'/capture',lambda d:d['status']=='SUCCESS','gateway')
    charging=poll(user,'/v1/charge-orders/'+no,lambda d:d['status']=='CHARGING')
    return {'user':user,'orderNo':no,'sessionNo':charging['sessionNo'],
            'start':datetime.fromisoformat(charging['startedAt'].replace('Z','+00:00'))}


def sessions(args):
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        running=list(pool.map(start_session,range(len(USERS))))
    # Spread the users across each 5-second window; each session keeps 5s cadence.
    ticks=int(args.duration/5)
    if ticks < 1: raise RuntimeError('Session phase duration must be at least 5 seconds')
    total=ticks*len(running)
    def meter(i,due):
        session=running[i%len(running)];tick=i//len(running)+1
        occurred=(session['start']+timedelta(seconds=tick*5)).isoformat().replace('+00:00','Z')
        call(None,'POST','/dev/sessions/'+session['sessionNo']+'/advance',
             {'deltaWh':10,'occurredAt':occurred},target='device',phase='load',arrival=i,due=due,operation='meter_5s')
    elapsed=schedule(total,len(running)/5,meter,args.workers)
    def stop(session):
        at=(session['start']+timedelta(seconds=ticks*5+1)).isoformat().replace('+00:00','Z')
        row,data=call(None,'POST','/dev/sessions/'+session['sessionNo']+'/advance',
                      {'deltaWh':0,'occurredAt':at,'stop':True},target='device',phase='cleanup')
        require(row,data,'device stop')
        refresh_login(session['user'])
        funds=poll(session['user'],'/v1/charge-orders/'+session['orderNo']+'/funds',lambda d:d['financialStatus']=='CLOSED',timeout=180)
        if funds['capturedFen']!=10000 or funds['netReceivedFen']+funds['refundSuccessFen']!=10000:
            raise RuntimeError('Session monetary invariant failed')
        row,bill=call(session['user'],'GET','/v1/charge-orders/'+session['orderNo']+'/bill',phase='cleanup')
        require(row,bill,'formal session bill')
        amounts=bill['amounts']
        if amounts['totalWh']!=ticks*10 or amounts['payableFen']!=funds['netReceivedFen']:
            raise RuntimeError('Complete sampled energy or formal bill/cash equality was not preserved')
        row,order=call(session['user'],'GET','/v1/charge-orders/'+session['orderNo'],phase='cleanup')
        require(row,order,'final session state')
        if order['status']!='COMPLETED':raise RuntimeError('A sampled device session did not complete')
        with LOCK:
            SESSION_RESULTS.append({'orderNo':session['orderNo'],'sessionNo':session['sessionNo'],
                'totalWh':amounts['totalWh'],'payableFen':amounts['payableFen'],
                'capturedFen':funds['capturedFen'],'refundSuccessFen':funds['refundSuccessFen'],
                'financialStatus':funds['financialStatus'],'status':order['status']})
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(stop,running))
    return elapsed,total


def percentile(values,p):
    return sorted(values)[max(0,math.ceil(len(values)*p)-1)] if values else None


def request_slo(sent,mode):
    queries=[r['arrivalLatencyMs'] for r in sent if r.get('target')=='api' and r.get('method')=='GET' and r['classification']=='success']
    creates=[r['arrivalLatencyMs'] for r in sent if r.get('method')=='POST' and r.get('path')=='/v1/charge-orders' and r['classification']=='success']
    slo={'basis':'scheduled arrival to completed response, including client queueMs',
         'querySuccessfulP95Ms':percentile(queries,.95),'queryLimitMs':300,
         'createSuccessfulP95Ms':percentile(creates,.95),'createLimitMs':500,
         'querySamples':len(queries),'createSamples':len(creates)}
    if mode=='mixed':
        slo['result']='PASS' if queries and creates and slo['querySuccessfulP95Ms']<300 and slo['createSuccessfulP95Ms']<500 else 'FAIL'
    else:slo['result']='NOT_APPLICABLE_DEVICE_EVENT_PHASE'
    return slo


def main():
    global OUT, USERS
    parser=argparse.ArgumentParser()
    parser.add_argument('--mode',choices=['mixed','sessions'],required=True)
    parser.add_argument('--duration',type=float)
    parser.add_argument('--rate',type=float,default=50)
    parser.add_argument('--users',type=int,default=100)
    parser.add_argument('--workers',type=int,default=64)
    parser.add_argument('--seed',type=int,default=20261018)
    args=parser.parse_args()
    args.duration=args.duration or (900 if args.mode=='mixed' else 1800)
    if not 1<=args.users<=100 or args.duration<=0 or args.rate<=0: raise SystemExit('Invalid load parameters')
    directory=Path('/reports')/args.mode;directory.mkdir(parents=True,exist_ok=True)
    OUT=(directory/'requests.jsonl').open('w',buffering=1)
    began=now()
    status='FAIL';failure=None;elapsed=0;arrivals=0
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            USERS=list(pool.map(login,range(args.users)))
        # Login cost is retained as setup evidence, separate from fixed-rate phase.
        elapsed,arrivals=(mixed(args) if args.mode=='mixed' else sessions(args))
        status='PASS'
    except Exception as exception:
        failure=type(exception).__name__+': '+str(exception)
    finally:
        OUT.close()
    rows=[r for r in ROWS if r['phase']=='load'];sent=[r for r in rows if r['classification']!='client_unsent']
    counts=Counter(r['classification'] for r in rows)
    latencies=[r['latencyMs'] for r in sent if r.get('latencyMs') is not None]
    arrival_latencies=[r['arrivalLatencyMs'] for r in sent if r.get('arrivalLatencyMs') is not None]
    lags=[r.get('queueMs',0) for r in rows]
    per_operation={}
    for operation in sorted({r.get('operation') or 'unknown' for r in sent}):
        samples=[r for r in sent if (r.get('operation') or 'unknown')==operation]
        success=[r['latencyMs'] for r in samples if r['classification']=='success']
        all_latencies=[r['latencyMs'] for r in samples if r.get('latencyMs') is not None]
        success_arrival=[r['arrivalLatencyMs'] for r in samples if r['classification']=='success']
        all_arrival=[r['arrivalLatencyMs'] for r in samples if r.get('arrivalLatencyMs') is not None]
        per_operation[operation]={'requests':len(samples),'successfulRequests':len(success),
            'classifications':dict(Counter(r['classification'] for r in samples)),
            'allLatencyMs':{'P50':percentile(all_latencies,.5),'P95':percentile(all_latencies,.95),'P99':percentile(all_latencies,.99)},
            'successfulLatencyMs':{'P50':percentile(success,.5),'P95':percentile(success,.95),'P99':percentile(success,.99)},
            'allArrivalLatencyMs':{'P50':percentile(all_arrival,.5),'P95':percentile(all_arrival,.95),'P99':percentile(all_arrival,.99)},
            'successfulArrivalLatencyMs':{'P50':percentile(success_arrival,.5),'P95':percentile(success_arrival,.95),'P99':percentile(success_arrival,.99)}}
    slo=request_slo(sent,args.mode)
    report={'status':status,'simulated':True,'runId':os.environ['PERF_RUN_ID'],'mode':args.mode,
            'beganAt':began,'endedAt':now(),'parameters':vars(args),'loadElapsedSeconds':elapsed,
            'scheduledArrivals':arrivals,'observedArrivalRows':len(rows),'sentArrivals':len(sent),
            'classifications':dict(counts),'completedRequestsPerSecond':len(sent)/elapsed if elapsed else 0,
            'latencyMs':{'P50':percentile(latencies,.5),'P95':percentile(latencies,.95),'P99':percentile(latencies,.99)},
            'arrivalLatencyMs':{'P50':percentile(arrival_latencies,.5),'P95':percentile(arrival_latencies,.95),'P99':percentile(arrival_latencies,.99)},
            'lateArrivalCount':sum(lag>0 for lag in lags),'lateOverIntervalCount':sum(lag>1000/args.rate for lag in lags),
            'maxLagMs':max(lags,default=0),'lateOverIntervalThresholdMs':1000/args.rate,
            'queueMsP95':percentile([r.get('queueMs',0) for r in sent],.95),'totalRawRows':len(ROWS),
            'allPhasesClassifications':dict(Counter(r['classification'] for r in ROWS)),
            'byPhase':{phase:dict(Counter(r['classification'] for r in ROWS if r['phase']==phase)) for phase in sorted({r['phase'] for r in ROWS})},
            'perOperation':per_operation,'slo':slo,'completedSessionCount':len(SESSION_RESULTS),
            'sessionResults':SESSION_RESULTS,'rawRequests':'requests.jsonl','failure':failure,'scope':'Measured synthetic isolated load. This report is not a production throughput claim.'}
    if slo['result']=='FAIL': report['status']='FAIL'
    if counts['timeout'] or counts['transport_error'] or counts['server_error'] or counts['authentication_failure'] or counts['invalid_response'] or counts['client_unsent']:
        report['status']='FAIL'
    if len(rows)!=arrivals and status=='PASS': report['status']='FAIL';report['failure']='Lost scheduled arrival evidence'
    (directory/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    raise SystemExit(0 if report['status']=='PASS' else 1)


if __name__=='__main__': main()
