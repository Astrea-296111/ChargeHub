#!/usr/bin/env python3
"""Provision an isolated fixed-image performance stack; never targets demo services."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
COMPOSE=ROOT/'scripts/performance/compose.yaml'
DOCKER_ENV={**os.environ,'DOCKER_CONFIG':str(ROOT/'.local/docker'),
            'PERF_UID':str(os.getuid()),'PERF_GID':str(os.getgid())}


def command(args,*,data=None,check=True):
    return subprocess.run(args,input=data,text=True,capture_output=True,env=DOCKER_ENV,check=check)


def identity(image):
    value=json.loads(command(['docker','image','inspect',image]).stdout)[0]
    return {'imageId':value['Id'],'repoDigests':value.get('RepoDigests',[]),
            'revision':value.get('Config',{}).get('Labels',{}).get('org.opencontainers.image.revision')}


def sql(compose,text):
    # Credentials remain in the generated container environment, never argv.
    result=command(compose+['exec','-T','mysql','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names -u root "$MYSQL_DATABASE"'],data=text)
    return result.stdout


def seed(compose,history,directory):
    start=time.monotonic()
    rows=[]
    for i in range(100):
        rows.append(f"INSERT IGNORE INTO iam_user(login_name,password_hash) SELECT 'perf{i:04d}',password_hash FROM iam_user WHERE login_name='customer';")
        rows.append(f"INSERT IGNORE INTO iam_user_role(user_id,role_code) SELECT id,'CUSTOMER' FROM iam_user WHERE login_name='perf{i:04d}';")
        rows.append(f"INSERT IGNORE INTO connector(id,station_id,device_id,connector_code) SELECT {10000+i},id,'perf-device-01','PERF-{i:04d}' FROM station ORDER BY id LIMIT 1;")
    sql(compose,'\n'.join(rows))
    existing_history=int(sql(compose,"SELECT COUNT(*) FROM charge_order WHERE order_no LIKE 'f00d0000-%';").strip())
    if existing_history and existing_history!=history:
        raise RuntimeError('Existing isolated run has a different history scale; create a new run instead of replacing data')
    if history and existing_history!=history:
        seed_sql=f'''SET @station=(SELECT id FROM station ORDER BY id LIMIT 1);
SET @tariff=(SELECT active_tariff_id FROM station WHERE id=@station);
SET @snapshot=(SELECT JSON_OBJECT('tariffId',CAST(t.id AS CHAR),'stationId',CAST(s.id AS CHAR),'tariffVersion',t.version_no,'timezone',s.timezone,'ruleHash',t.rule_hash,'slots',(SELECT JSON_ARRAYAGG(JSON_OBJECT('startMinute',start_minute,'endMinute',end_minute,'energyRateFenPerKwh',energy_rate_fen_per_kwh,'serviceRateFenPerKwh',service_rate_fen_per_kwh)) FROM tariff_slot WHERE tariff_id=t.id)) FROM station s JOIN tariff_plan t ON t.id=s.active_tariff_id WHERE s.id=@station);
CREATE TEMPORARY TABLE perf_users AS SELECT id,ROW_NUMBER() OVER(ORDER BY login_name) rn FROM iam_user WHERE login_name REGEXP '^perf[0-9]{{4}}$';
INSERT IGNORE INTO charge_order(order_no,user_id,station_id,connector_id,tariff_id,status,financial_status,tariff_snapshot,payment_deadline,created_at,updated_at)
WITH perf_digits AS (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)
SELECT CONCAT('f00d0000-0000-4000-8000-',LPAD(HEX(numbers.n),12,'0')),u.id,@station,10000+MOD(numbers.n,100),@tariff,'CANCELED','CLOSED',CAST(@snapshot AS JSON),'2025-01-01 00:05:00',TIMESTAMPADD(SECOND,numbers.n,'2025-01-01 00:00:00'),TIMESTAMPADD(SECOND,numbers.n,'2025-01-01 00:00:00')
FROM (SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n n FROM perf_digits a CROSS JOIN perf_digits b CROSS JOIN perf_digits c CROSS JOIN perf_digits d CROSS JOIN perf_digits e CROSS JOIN perf_digits f) numbers JOIN perf_users u ON u.rn=MOD(numbers.n,100)+1 WHERE numbers.n<{history};
ANALYZE TABLE charge_order;
SELECT COUNT(*) FROM charge_order WHERE order_no LIKE 'f00d0000-%';'''
        (directory/'synthetic-history.sql').write_text(seed_sql)
        result=sql(compose,seed_sql)
        (directory/'history-seed-result.txt').write_text(result)
        actual=int(result.strip().splitlines()[-1])
        if actual!=history: raise RuntimeError(f'Historical seed count {actual} != requested {history}')
    statements={
      'user-cursor':"SELECT o.*,s.session_no FROM charge_order o LEFT JOIN charge_session s ON s.order_no=o.order_no WHERE o.user_id=(SELECT id FROM iam_user WHERE login_name='perf0000') AND (o.created_at<'2026-01-01 00:00:00' OR (o.created_at='2026-01-01 00:00:00' AND o.id<9223372036854775807)) ORDER BY o.created_at DESC,o.id DESC LIMIT 21",
      'user-cursor-legacy-comparison':"SELECT * FROM charge_order WHERE user_id=(SELECT id FROM iam_user WHERE login_name='perf0000') AND id<9223372036854775807 ORDER BY id DESC LIMIT 20",
      'station-cursor':"SELECT o.id,o.order_no,o.station_id,o.connector_id,o.status,o.financial_status,o.prepaid_fen,o.exception_code,o.created_at,s.session_no FROM charge_order o LEFT JOIN charge_session s ON s.order_no=o.order_no WHERE (o.created_at>'2025-01-06 00:00:00' OR (o.created_at='2025-01-06 00:00:00' AND o.id>1)) AND o.station_id=1 ORDER BY o.created_at,o.id LIMIT 21",
      'station-cursor-legacy-comparison':"SELECT * FROM charge_order WHERE station_id=1 AND id<9223372036854775807 ORDER BY id DESC LIMIT 20",
      'payment-expiration':"SELECT order_no FROM charge_order WHERE status='WAIT_PAY' AND payment_deadline<'2026-10-18 07:00:00' ORDER BY id LIMIT 100"}
    for name,query in statements.items():
        (directory/(name+'.sql')).write_text(query+';\n')
        (directory/(name+'.explain.json.txt')).write_text(sql(compose,'EXPLAIN FORMAT=JSON '+query+';'))
        (directory/(name+'.analyze.txt')).write_text(sql(compose,'EXPLAIN ANALYZE '+query+';'))
    (directory/'seed-summary.json').write_text(json.dumps({'users':100,'connectors':100,'historicalOrders':history,'reusedHistoricalRows':existing_history==history and history>0,'elapsedSeconds':time.monotonic()-start,'synthetic':True,'historicalStatus':'CANCELED/CLOSED; no invented cash or charging sessions'},indent=2)+'\n')


def snapshot(compose,directory,suffix):
    ids=command(compose+['ps','-q']).stdout.split()
    inspect=json.loads(command(['docker','inspect']+ids).stdout) if ids else []
    selected=[{'name':v['Name'],'imageId':v['Image'],'state':v['State']['Status'],
               'cpus':v['HostConfig'].get('NanoCpus',0)/1e9,'memoryLimitBytes':v['HostConfig'].get('Memory'),
               'cpuSet':v['HostConfig'].get('CpusetCpus'),'health':v['State'].get('Health',{}).get('Status')} for v in inspect]
    (directory/f'containers-{suffix}.json').write_text(json.dumps(selected,indent=2)+'\n')
    if ids:
        (directory/f'docker-stats-{suffix}.jsonl').write_text(command(['docker','stats','--no-stream','--format','{{json .}}']+ids).stdout)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--image',required=True,help='Fixed candidate tag/ID; resolved to immutable local image ID')
    p.add_argument('--simulator-image',default='chargehub-simulator:1.0.0')
    p.add_argument('--sha',required=True,help='Expected 40-character candidate Git SHA recorded in OCI revision label')
    p.add_argument('--phase',choices=['prepare','smoke','mixed','sessions','all'],default='smoke')
    p.add_argument('--duration',type=float,help='Override phase duration for a short smoke; full defaults:900/1800')
    p.add_argument('--history',type=int,default=1000000)
    p.add_argument('--users',type=int,default=100)
    p.add_argument('--run-id',help='Continue only an existing generated chargehub_perf_* run')
    p.add_argument('--down',action='store_true',help='Stop this isolated project after verification; retain evidence/volumes')
    args=p.parse_args()
    if not re.fullmatch('[0-9a-f]{40}',args.sha) or not 0<=args.history<=1000000: p.error('Invalid SHA or history count')
    candidate,simulator=identity(args.image),identity(args.simulator_image)
    if candidate['revision']!=args.sha: raise SystemExit('Candidate OCI revision label does not match --sha; build with --build-arg VCS_REF=<sha>')
    run_id=args.run_id or uuid.uuid4().hex[:12]
    if not re.fullmatch('[a-z0-9]{12}',run_id): p.error('run-id must be twelve lowercase letters/digits')
    directory=ROOT/'.local/performance'/run_id
    directory.mkdir(parents=True,exist_ok=True);directory.chmod(0o700)
    envfile=directory/'env'
    if not envfile.exists():
        values={'PERF_RUN_ID':run_id,'PERF_DATABASE':'chargehub_perf_'+run_id,
                'CHARGEHUB_CANDIDATE_IMAGE':candidate['imageId'],'CHARGEHUB_SIMULATOR_IMAGE':simulator['imageId']}
        values.update({name:secrets.token_hex(24) for name in ['PERF_DB_PASSWORD','PERF_MYSQL_ROOT_PASSWORD','PERF_PASSWORD','PERF_PAYMENT_KEY','PERF_DEVICE_KEY','PERF_CONTROL_KEY']})
        envfile.write_text(''.join(k+'='+v+'\n' for k,v in values.items()));envfile.chmod(0o600)
    else:
        values=dict(line.split('=',1) for line in envfile.read_text().splitlines() if '=' in line)
        if values.get('CHARGEHUB_CANDIDATE_IMAGE')!=candidate['imageId']: raise SystemExit('Existing run belongs to a different immutable candidate')
        previous_report=directory/'run.json'
        if previous_report.exists():
            previous=json.loads(previous_report.read_text())
            if previous.get('result')=='RUNNING': raise SystemExit('Existing run still reports RUNNING; do not start concurrent load against it')
            archive=directory/'attempts'/((previous.get('phase') or 'unknown')+'-'+uuid.uuid4().hex[:10])
            archive.mkdir(parents=True)
            # Preserve smoke and every failure before reusing the same topology.
            # Credentials remain only in the private original env file.
            for entry in list(directory.iterdir()):
                if entry.name not in ('env','attempts'):
                    shutil.move(str(entry),str(archive/entry.name))
    compose=['docker','compose','-p','chargehub-perf-'+run_id,'--project-directory',str(ROOT),'--env-file',str(envfile),'-f',str(COMPOSE),'-f',str(ROOT/'compose-mq.yaml'),'-f',str(ROOT/'scripts/performance/compose-mq-limits.yaml')]
    began=datetime.now(timezone.utc).isoformat()
    machine={'hostLogicalCpuCount':os.cpu_count(),'processCpuAffinity':sorted(os.sched_getaffinity(0)),
             'processCgroupCpuMax':Path('/sys/fs/cgroup/cpu.max').read_text().strip(),
             'processCgroupMemoryMax':Path('/sys/fs/cgroup/memory.max').read_text().strip(),
             'hostLoadAverage':os.getloadavg(),'workspaceFreeBytesBefore':shutil.disk_usage(ROOT).free,
             'docker':json.loads(command(['docker','info','--format','{{json .}}']).stdout)}
    # Keep useful resources/version only, avoiding daemon labels/network credentials.
    machine['docker']={k:machine['docker'].get(k) for k in ['NCPU','MemTotal','ServerVersion','Architecture','OperatingSystem','KernelVersion']}
    machine['hostMeminfo']=Path('/proc/meminfo').read_text().splitlines()[:3]
    report={'runId':run_id,'beganAt':began,'candidate':candidate,'simulator':simulator,'sha':args.sha,'machine':machine,
            'phase':args.phase,'historyRequested':args.history,'plannedApplicationLimit':'4 vCPU / 8 GiB with RocketMQ overlay; load generator additional0.75 vCPU /512MiB',
            'result':'RUNNING','notes':['No demo database, demo volumes, host ports, or public targets are used.','Resource limits and measured host capacity are separate facts.']}
    report['harnessGitHead']=command(['git','-C',str(ROOT),'rev-parse','HEAD']).stdout.strip()
    report['harnessFileSha256']={name:hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in [
        'scripts/performance/run.py','scripts/performance/load.py','scripts/performance/compose.yaml',
        'scripts/performance/compose-mq-limits.yaml','compose-mq.yaml',
        'deploy/rocketmq/broker.conf','deploy/rocketmq/proxy.json']}
    reportfile=directory/'run.json';reportfile.write_text(json.dumps(report,indent=2)+'\n')
    try:
        started=command(compose+['up','-d','--wait','--wait-timeout','240'])
        (directory/'startup.log').write_text(started.stdout+started.stderr)
        # Health is public and credential-free; readiness is checked inside the loadgen network.
        probe="import urllib.request;urllib.request.urlopen('http://api:8080/actuator/health/readiness',timeout=3)"
        command(compose+['run','--rm','--no-deps','loadgen','python','-c',probe])
        seed(compose,args.history,directory)
        snapshot(compose,directory,'before')
        modes=[] if args.phase=='prepare' else ['mixed'] if args.phase in ('mixed','smoke') else ['sessions'] if args.phase=='sessions' else ['mixed','sessions']
        for mode in modes:
            duration=args.duration or (10 if args.phase=='smoke' else 900 if mode=='mixed' else 1800)
            result=command(compose+['run','--rm','--no-deps','loadgen','python','load.py','--mode',mode,'--duration',str(duration),'--users',str(args.users)],check=False)
            (directory/(mode+'.log')).write_text(result.stdout+result.stderr)
            print(result.stdout)
            if result.returncode: raise RuntimeError(mode+' load phase failed; inspect raw requests and summary')
        violations=sql(compose,(ROOT/'scripts/invariants.sql').read_text())
        (directory/'invariants.tsv').write_text(violations)
        if violations.strip(): raise RuntimeError('Post-load business invariant violations')
        snapshot(compose,directory,'after')
        report['result']='PASS'
    except Exception as exception:
        report['result']='FAIL';report['failure']=type(exception).__name__+': '+str(exception)
        if isinstance(exception,subprocess.CalledProcessError):
            try:
                (directory/'failure-private.log').write_text((exception.stdout or '')+(exception.stderr or ''))
            except OSError as evidence_error:
                print('Private failure log could not be written: '+type(evidence_error).__name__,file=sys.stderr)
        # Container logs are deliberately not copied: they can include private data.
    finally:
        report['endedAt']=datetime.now(timezone.utc).isoformat()
        try:
            reportfile.write_text(json.dumps(report,indent=2)+'\n')
        finally:
            if args.down: command(compose+['down'],check=False)
    print(json.dumps({'runId':run_id,'result':report['result'],'evidence':str(directory),'failure':report.get('failure')},indent=2))
    raise SystemExit(0 if report['result']=='PASS' else 1)


if __name__=='__main__': main()
