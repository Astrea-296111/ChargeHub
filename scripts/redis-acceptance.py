#!/usr/bin/env python3
"""Actual Redis clear/stop/recovery HTTP gate, confined to a dedicated CI project."""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path
from demo import Client,expect,wait_for
ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',required=True,help='Must start chargehub-ci-; demo project is deliberately rejected')
    args=parser.parse_args()
    if not re.fullmatch('chargehub-ci-[a-z0-9-]+',args.project): raise SystemExit('Only a dedicated chargehub-ci-* project may be stopped or cleared')
    env={**os.environ,'DOCKER_CONFIG':str(ROOT/'.local/docker')}
    compose=['docker','compose','-p',args.project,'--env-file',str(ROOT/'.local/dev.env')]
    def run(command):return subprocess.run(command,env=env,text=True,capture_output=True,check=True).stdout
    def container(service):
        cid=run(compose+['ps','-q',service]).strip()
        details=json.loads(run(['docker','inspect',cid]))[0]
        expect(details['Config']['Labels']['com.docker.compose.project']==args.project,'Container belongs to another project')
        return details
    api=container('api');container('redis')
    binding=api['NetworkSettings']['Ports'].get('8080/tcp')
    expect(bool(binding),'Dedicated CI API must publish its loopback development port')
    port=binding[0]['HostPort'];url='http://127.0.0.1:'+port
    password=os.environ.get('CHARGEHUB_DEMO_PASSWORD','')
    if not password:raise SystemExit('Source generated .local/dev.env before running the gate')
    client=Client(url);client.login('customer',password)
    original=client.call('/v1/stations');client.call('/v1/stations')
    checks=[]
    # This command affects only the checked, dedicated project Redis container.
    run(compose+['exec','-T','redis','redis-cli','FLUSHDB'])
    expect(client.call('/v1/stations')==original,'Empty cache changed source-backed station results')
    checks.append({'check':'Dedicated Redis cleared; real HTTP reload from MySQL is identical','result':'PASS'})
    stopped=False
    try:
        run(compose+['stop','redis']);stopped=True
        start=time.monotonic();unavailable=client.call('/v1/stations');elapsed=time.monotonic()-start
        expect(unavailable==original,'Redis outage changed authoritative catalog results')
        expect(elapsed<3,'Redis fallback exceeded bounded timeout budget')
        checks.append({'check':'Actual Redis process stopped; HTTP fallback remains available','result':'PASS','elapsedSeconds':elapsed})
    finally:
        if stopped:run(compose+['start','redis'])
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        try:
            if 'PONG' in run(compose+['exec','-T','redis','redis-cli','PING']):break
        except subprocess.CalledProcessError:pass
        time.sleep(.25)
    else:raise RuntimeError('Dedicated Redis failed to restart')
    time.sleep(2)
    expect(client.call('/v1/stations')==original,'Recovery changed catalog facts')
    client.call('/v1/stations')
    keys=int(run(compose+['exec','-T','redis','redis-cli','DBSIZE']).strip())
    expect(keys>0,'Recovered cache was never repopulated')
    checks.append({'check':'Redis restarted and real requests repopulate cache','result':'PASS','cacheKeys':keys})
    report={'status':'PASS','simulated':True,'project':args.project,'checks':checks,'scope':'Catalog availability only. Transactional financial invariants are checked by independent MySQL suites.'}
    path=ROOT/'.local/reports/redis.json';path.parent.mkdir(exist_ok=True);path.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
