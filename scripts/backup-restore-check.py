#!/usr/bin/env python3
"""Backup the local dev database, restore into a NEW isolated database, verify facts.

Contains no DROP or replacement of the running database. Backups contain private
business data and stay in .local, which is ignored by Git and source packaging.
"""
import hashlib,json,os,subprocess,time,uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
os.chdir(ROOT)
env=os.environ.copy();env.setdefault('DOCKER_CONFIG',str(ROOT/'.local/docker'))
base=['docker','compose','--env-file','.local/dev.env','exec','-T','mysql','sh','-c']
def mysql(sql,database=None):
 command='MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --batch --skip-column-names -u root'+(' '+database if database else '')
 return subprocess.run(base+[command],input=sql.encode(),capture_output=True,env=env,check=True).stdout.decode()
start=time.monotonic()
active=int(mysql("SELECT COUNT(*) FROM charge_session WHERE status IN ('START_PENDING','CHARGING');",'chargehub').strip())
if active:raise SystemExit('Finish active dev sessions before this consistency/restore drill')
command='MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --single-transaction --no-tablespaces --routines --events --triggers --hex-blob --set-gtid-purged=OFF chargehub'
dump=subprocess.run(base+[command],capture_output=True,env=env,check=True).stdout
if not dump or b'CREATE DATABASE' in dump or b'\nUSE ' in dump:raise SystemExit('Unexpected dump shape; refusing restore')
backup=ROOT/'.local/backups'/('chargehub-'+uuid.uuid4().hex[:10]+'.sql');backup.parent.mkdir(exist_ok=True)
backup.write_bytes(dump);backup.chmod(0o600);sha=hashlib.sha256(dump).hexdigest()
backup.with_suffix('.sql.sha256').write_text(sha+'  '+backup.name+'\n')
assert hashlib.sha256(backup.read_bytes()).hexdigest()==sha
restored='chargehub_restore_'+uuid.uuid4().hex[:12]+'_test'
mysql('CREATE DATABASE `'+restored+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
mysql(dump.decode(),restored)
violations=mysql((ROOT/'scripts/invariants.sql').read_text(),restored)
if violations.strip():raise SystemExit('Restored snapshot has invariant violations')
tables=['iam_user','charge_order','charge_session','charge_bill','charge_bill_item','payment_order','refund_order','cash_entry','member_quota','quota_ledger','coupon_template','user_coupon','benefit_reservation','flyway_schema_history']
counts={}
for table in tables:
 a=int(mysql('SELECT COUNT(*) FROM `'+table+'`;', 'chargehub').strip());b=int(mysql('SELECT COUNT(*) FROM `'+table+'`;',restored).strip())
 if a!=b:raise SystemExit('Live facts changed during drill or restore count mismatch: '+table)
 counts[table]=b
report={'status':'PASS','backupSha256':sha,'restoreDatabase':restored,'verifiedTableCounts':counts,'invariantViolations':0,'elapsedSeconds':round(time.monotonic()-start,3),'scope':'MySQL backup/restore only; simulator volumes and application rollback are separate checks'}
out=ROOT/'.local/reports/backup-restore.json';out.parent.mkdir(exist_ok=True);out.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
