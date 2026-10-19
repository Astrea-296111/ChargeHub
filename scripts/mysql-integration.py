#!/usr/bin/env python3
"""Run every database acceptance suite against its own freshly created real MySQL database."""
from __future__ import annotations
import argparse,json,os,subprocess,sys,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SUITES={
 'benefit':'CHARGEHUB_TEST',
 'reconcile':'CHARGEHUB_RECONCILE_TEST',
 'workflow':'CHARGEHUB_WORKFLOW',
 'identity':'CHARGEHUB_IDENTITY_TEST',
 'catalog':'CHARGEHUB_CATALOG_TEST',
 'meter':'CHARGEHUB_METER_TEST',
 'finance':'CHARGEHUB_FINANCE_TEST',
 'http':'CHARGEHUB_HTTP_TEST',
 'messaging':'CHARGEHUB_MESSAGING_TEST',
}
REDIS_IMAGE='redis:7.4-alpine@sha256:858f009f9709ce576febc734aa78b8f6d624b82571f9ddb6bda4377c833b3499'
def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--tests',help='Optional comma-separated JUnit class names for a diagnosed targeted rerun')
 args=parser.parse_args();os.chdir(ROOT)
 env=os.environ.copy()
 for line in (ROOT/'.local/dev.env').read_text().splitlines():
  if line and not line.startswith('#'):
   k,v=line.split('=',1);env.setdefault(k,v)
 env['DOCKER_CONFIG']=str(ROOT/'.local/docker')
 compose=['docker','compose','--env-file','.local/dev.env']
 created={};started=time.monotonic();redis_name='chargehub-test-redis-'+uuid.uuid4().hex[:10]
 result_code=1
 try:
  for stem,prefix in SUITES.items():
   database='chargehub_'+stem+'_'+uuid.uuid4().hex[:10]+'_test';created[stem]=database
   sql=f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL ON `{database}`.* TO 'chargehub'@'%';"
   subprocess.run(compose+['exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root'],input=sql,text=True,env=env,check=True)
   env[prefix+'_JDBC_URL']=f'jdbc:mysql://127.0.0.1:3306/{database}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
   env[prefix+'_DB_USER']='chargehub';env[prefix+'_DB_PASSWORD']=env['CHARGEHUB_DB_PASSWORD']
  subprocess.run(['docker','run','-d','--name',redis_name,'-p','127.0.0.1::6379',REDIS_IMAGE],env=env,check=True,capture_output=True)
  port=subprocess.check_output(['docker','port',redis_name,'6379/tcp'],env=env,text=True).strip().split(':')[-1]
  for _ in range(30):
   ping=subprocess.run(['docker','exec',redis_name,'redis-cli','ping'],env=env,capture_output=True,text=True)
   if ping.returncode==0 and 'PONG' in ping.stdout:break
   time.sleep(.2)
  else:raise RuntimeError('Dedicated test Redis failed to become ready')
  env['CHARGEHUB_CATALOG_TEST_REDIS_HOST']='127.0.0.1';env['CHARGEHUB_CATALOG_TEST_REDIS_PORT']=port
  command=['bash','scripts/mvn.sh','-B','-ntp','verify']
  if args.tests:command+=['-Dtest='+args.tests,'-Dsurefire.failIfNoSpecifiedTests=false']
  result_code=subprocess.run(command,env=env).returncode
 finally:
  subprocess.run(['docker','rm','-f',redis_name],env=env,capture_output=True)
  report=ROOT/'.local/reports';report.mkdir(exist_ok=True)
  (report/'mysql-integration.json').write_text(json.dumps({'databases':created,'exitCode':result_code,'selectedTests':args.tests,'elapsedSeconds':round(time.monotonic()-started,3),'dedicatedRedisRemoved':True},indent=2)+'\n')
  print('Isolated test databases retained:',', '.join(created.values()))
 return result_code
if __name__=='__main__':sys.exit(main())
