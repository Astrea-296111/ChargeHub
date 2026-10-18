#!/usr/bin/env python3
"""Run real MySQL tests in fresh isolated databases, leaving normal demo facts untouched."""
import os,subprocess,uuid,json,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1];os.chdir(root)
env=os.environ.copy()
for line in (root/'.local/dev.env').read_text().splitlines():
 if line and not line.startswith('#'):
  k,v=line.split('=',1);env.setdefault(k,v)
env['DOCKER_CONFIG']=str(root/'.local/docker')
created={}
for name,prefix in [('CHARGEHUB_TEST_JDBC_URL','benefit'),('CHARGEHUB_RECONCILE_TEST_JDBC_URL','reconcile'),('CHARGEHUB_WORKFLOW_JDBC_URL','workflow')]:
 db='chargehub_'+prefix+'_'+uuid.uuid4().hex[:10]+'_test';created[prefix]=db
 sql=f"CREATE DATABASE `{db}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL ON `{db}`.* TO 'chargehub'@'%';"
 subprocess.run(['docker','compose','--env-file','.local/dev.env','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -u root'],input=sql,text=True,env=env,check=True)
 env[name]=f'jdbc:mysql://127.0.0.1:3306/{db}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8'
for prefix in ['CHARGEHUB_TEST','CHARGEHUB_RECONCILE_TEST','CHARGEHUB_WORKFLOW']:
 env[prefix+'_DB_USER']='chargehub';env[prefix+'_DB_PASSWORD']=env['CHARGEHUB_DB_PASSWORD']
result=subprocess.run(['bash','scripts/mvn.sh','-B','-ntp','verify'],env=env)
report=root/'.local/reports';report.mkdir(exist_ok=True)
(report/'mysql-integration.json').write_text(json.dumps({'databases':created,'exitCode':result.returncode},indent=2))
print('Isolated test databases retained:',', '.join(created.values()))
sys.exit(result.returncode)
