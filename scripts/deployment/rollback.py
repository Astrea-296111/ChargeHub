#!/usr/bin/env python3
"""A46: old empty schema -> candidate migration -> real callback facts -> old image.

All data is created in new project volumes/databases. Dumps/backups remain private;
only the sanitized report is suitable for CI artifacts. No DROP or in-place restore.
"""
import argparse
import hashlib
import json
import os
import re
import secrets
import subprocess
import sys
import time
import uuid
from datetime import datetime,timezone
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
ENV={**os.environ,'DOCKER_CONFIG':str(ROOT/'.local/docker')}
PYTHON='python:3.12-slim@sha256:a6e34c598f2467ed0e9a8d349809fcd8b5c603269512df273a0bb1784edc11b1'


def run(args,data=None,check=True):
    return subprocess.run(args,input=data,text=True,capture_output=True,env=ENV,check=check)


def image(name):
    value=json.loads(run(['docker','image','inspect',name]).stdout)[0]
    return {'id':value['Id'],'revision':(value.get('Config',{}).get('Labels') or {}).get('org.opencontainers.image.revision')}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--candidate-image',required=True)
    p.add_argument('--previous-image',default='chargehub-app:baseline-f8dede3')
    p.add_argument('--simulator-image',default='chargehub-simulator:1.0.0')
    p.add_argument('--keep-running',action='store_true')
    args=p.parse_args()
    candidate,previous,simulator=image(args.candidate_image),image(args.previous_image),image(args.simulator_image)
    uid=uuid.uuid4().hex[:12];project='chargehub-rollback-'+uid
    database='chargehub_rollback_'+uid;restored='chargehub_restore_'+uid
    directory=ROOT/'.local/rollback'/uid;directory.mkdir(parents=True);directory.chmod(0o700)
    values={'ROLLBACK_RUN_ID':uid,'ROLLBACK_DATABASE':database,'ROLLBACK_IMAGE_ID':previous['id'],
            'ROLLBACK_SIMULATOR_IMAGE':simulator['id'],'ROLLBACK_MYSQL_VOLUME':project+'-mysql',
            'ROLLBACK_GATEWAY_VOLUME':project+'-gateway','ROLLBACK_DEVICE_VOLUME':project+'-device'}
    values.update({name:secrets.token_hex(24) for name in ['ROLLBACK_DB_PASSWORD','ROLLBACK_MYSQL_ROOT_PASSWORD','ROLLBACK_PASSWORD','ROLLBACK_PAYMENT_KEY','ROLLBACK_DEVICE_KEY','ROLLBACK_CONTROL_KEY']})
    envfile=directory/'env'
    def writeenv(): envfile.write_text(''.join(k+'='+v+'\n' for k,v in values.items()));envfile.chmod(0o600)
    writeenv()
    compose=['docker','compose','--project-directory',str(ROOT),'-p',project,'--env-file',str(envfile),'-f',str(ROOT/'scripts/deployment/compose-rollback.yaml')]
    report={'status':'RUNNING','startedAt':datetime.now(timezone.utc).isoformat(),'project':project,
            'candidate':candidate,'previous':previous,'simulator':simulator,'checks':[],
            'scope':'Isolated MySQL migration/application rollback and independent SQLite backup/restore; no production data, external MQ journal, or demo volumes are touched.'}
    reportpath=ROOT/'.local/reports/rollback.json';reportpath.parent.mkdir(exist_ok=True)
    def sql(statement,db=database):
        if not re.fullmatch('[a-z0-9_]+',db): raise RuntimeError('Invalid generated database name')
        return run(compose+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names -u root '+db],statement).stdout
    def client(mode):
        result=run(compose+['run','--rm','--no-deps','client','python','/suite/deployment/rollback-client.py',mode])
        (directory/(mode+'-'+str(len(report['checks']))+'.log')).write_text(result.stdout+result.stderr)
        return result.stdout
    def ready():
        probe="import json,time,urllib.request\nfor i in range(120):\n try:\n  r=urllib.request.urlopen('http://api:8080/actuator/health/readiness',timeout=2)\n  if json.load(r).get('status')=='UP':break\n except Exception:pass\n time.sleep(1)\nelse:raise SystemExit('readiness timeout')"
        run(compose+['run','--rm','--no-deps','client','python','-c',probe])
    def switch(imageid):
        values['ROLLBACK_IMAGE_ID']=imageid;writeenv()
        run(compose+['up','-d','--no-deps','--force-recreate','api']);ready()
    immutable_tables=['device_event','device_event_conflict','payment_callback','cash_entry','charge_bill','charge_bill_item','payment_order','refund_order','charge_session','meter_sample','benefit_reservation']
    def counts(db=database):
        return {t:{'rows':int(sql('SELECT COUNT(*) FROM '+t+';',db).strip()),
                   'sha256':hashlib.sha256(sql('SELECT * FROM '+t+' ORDER BY id;',db).encode()).hexdigest()} for t in immutable_tables}
    try:
        run(compose+['up','-d','mysql','gateway','device','api']);ready();client('bootstrap')
        report['checks'].append({'check':'Old image initializes genuinely empty MySQL and creates/cancels an order','result':'PASS'})
        switch(candidate['id']);client('exercise')
        versions=sql('SELECT version,success FROM flyway_schema_history ORDER BY installed_rank;')
        report['checks'].append({'check':'Candidate upgrades old schema and completes independently signed payment/device/refund facts','result':'PASS','flywayVersions':versions.strip().splitlines()})
        deadline=time.monotonic()+60
        while int(sql("SELECT COUNT(*) FROM outbox_event WHERE status IN ('READY','PROCESSING');").strip()) and time.monotonic()<deadline:time.sleep(.5)
        run(compose+['stop','api','gateway','device'])
        before=counts()
        dump=run(compose+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --no-tablespaces --routines --events --triggers --hex-blob --set-gtid-purged=OFF '+database]).stdout
        if not dump or '\nUSE ' in dump or 'CREATE DATABASE' in dump:raise RuntimeError('Unsafe dump shape')
        dumpfile=directory/'mysql.sql';dumpfile.write_text(dump);dumpfile.chmod(0o600)
        report['mysqlBackupSha256']=hashlib.sha256(dump.encode()).hexdigest()
        backups={}
        for service,filename in [('gateway','payment.sqlite3'),('device','device.sqlite3')]:
            volume=values['ROLLBACK_'+service.upper()+'_VOLUME']
            result=run(['docker','run','--rm','--mount','type=volume,src='+volume+',dst=/source,readonly',
                        '--mount','type=bind,src='+str(directory)+',dst=/reports','--mount','type=bind,src='+str(ROOT/'scripts/deployment')+',dst=/tools,readonly',
                        PYTHON,'python','/tools/sqlite-snapshot.py','/source/'+filename,'/reports/'+filename])
            backups[service]=json.loads(result.stdout)
        report['sqliteBackups']=backups
        run(compose+['start','gateway','device']);switch(previous['id']);client('verify')
        if counts()!=before:raise RuntimeError('Old application changed immutable callback/billing/cash counts')
        report['checks'].append({'check':'Previous image starts against upgraded schema and reads retained real transaction','result':'PASS','verifiedTableCounts':before})
        run(compose+['stop','api','gateway','device'])
        sql('CREATE DATABASE `'+restored+'` CHARACTER SET utf8mb4; GRANT ALL ON `'+restored+'`.* TO \'rollback\'@\'%\';')
        sql(dump,restored)
        if counts(restored)!=before:raise RuntimeError('Restored MySQL immutable counts differ')
        violations=sql((ROOT/'scripts/invariants.sql').read_text(),restored)
        if violations.strip():raise RuntimeError('Restored MySQL invariant violation')
        for service,filename in [('gateway','payment.sqlite3'),('device','device.sqlite3')]:
            volume=project+'-'+service+'-restored';run(['docker','volume','create',volume])
            result=run(['docker','run','--rm','--mount','type=volume,src='+volume+',dst=/restore',
                        '--mount','type=bind,src='+str(directory)+',dst=/reports,readonly','--mount','type=bind,src='+str(ROOT/'scripts/deployment')+',dst=/tools,readonly',
                        PYTHON,'python','/tools/sqlite-snapshot.py','/reports/'+filename,'/restore/'+filename,'--expected-sha256',backups[service]['backupSha256']])
            verified=json.loads(result.stdout)
            if verified['tables']!=backups[service]['tables']:raise RuntimeError('Restored simulator facts differ')
            values['ROLLBACK_'+service.upper()+'_VOLUME']=volume
        values['ROLLBACK_DATABASE']=restored;values['ROLLBACK_IMAGE_ID']=candidate['id'];writeenv()
        run(compose+['up','-d','--no-deps','--force-recreate','gateway','device','api']);ready();client('verify')
        report['checks'].append({'check':'MySQL restored to a new database and both SQLite snapshots restored to new volumes; candidate reads retained facts','result':'PASS','restoredDatabase':restored,'invariantViolations':0})
        report['status']='PASS'
    except Exception as exception:
        report['status']='FAIL';report['failure']=type(exception).__name__+': '+str(exception)
        if isinstance(exception,subprocess.CalledProcessError):
            # Captured stderr is kept private rather than copied into reports.
            (directory/'failure-private.log').write_text(exception.stderr or '')
    finally:
        if not args.keep_running:run(compose+['down'],check=False)
        report['endedAt']=datetime.now(timezone.utc).isoformat()
        reportpath.write_text(json.dumps(report,indent=2)+'\n')
        (directory/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2));raise SystemExit(0 if report['status']=='PASS' else 1)


if __name__=='__main__':main()
