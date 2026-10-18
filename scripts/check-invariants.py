#!/usr/bin/env python3
import os,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1];os.chdir(root)
env=os.environ.copy();env.setdefault('DOCKER_CONFIG',str(root/'.local/docker'))
result=subprocess.run(['docker','compose','--env-file','.local/dev.env','exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --batch --skip-column-names -u chargehub chargehub'],input=(root/'scripts/invariants.sql').read_text(),text=True,capture_output=True,env=env)
p=root/'.local/reports/invariants.tsv';p.parent.mkdir(exist_ok=True);p.write_text(result.stdout)
if result.returncode:sys.stderr.write(result.stderr);sys.exit(result.returncode)
if result.stdout.strip():print(result.stdout);raise SystemExit('Business invariant violations found')
print('All business invariant queries returned zero violations')
