#!/usr/bin/env python3
"""Exercise an actual RocketMQ broker with the official native Java client, before business integration."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
CLIENT_SHA256 = '71dfe1c545b207a3016919a52be1ab984c385ea94cb8fd622c4e252b5cba37e5'
CLIENT_SHA1 = '647f07cea007742bab52a8c52eb75723689a1ee9'
BROKER_IMAGE = 'apache/rocketmq:5.5.0@sha256:7e8f6c9dbd9df742ed26ba69c00d4ad69e2f86a56f3ca7782ff8144dd0798132'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', default='chargehub')
    parser.add_argument('--image', default='chargehub-app:1.0.0')
    args = parser.parse_args()
    work = ROOT / '.local/messaging-smoke'
    reports = ROOT / '.local/reports'
    work.mkdir(parents=True, exist_ok=True)
    reports.mkdir(parents=True, exist_ok=True)
    jar = work / 'rocketmq-client-java-5.2.2.jar'
    base = 'https://repo.maven.apache.org/maven2/org/apache/rocketmq/rocketmq-client-java/5.2.2/'
    if not jar.exists():
        with urlopen(base + jar.name, timeout=60) as response:
            content = response.read()
        if hashlib.sha256(content).hexdigest() != CLIENT_SHA256:
            raise RuntimeError('Downloaded native client does not match the pinned artifact checksum')
        jar.write_bytes(content)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != CLIENT_SHA256 or hashlib.sha1(jar.read_bytes()).hexdigest() != CLIENT_SHA1:
        raise RuntimeError('Cached native client artifact checksum mismatch')
    java_home = os.environ.get('JAVA_HOME')
    if not java_home and Path('/workspace/tools/jdk-21/bin/javac').exists():
        java_home = '/workspace/tools/jdk-21'
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    if not javac:
        raise RuntimeError('A JDK 21 compiler is required for the standalone native smoke')
    subprocess.run([javac, '--release', '21', '-cp', str(jar), '-d', str(work), str(ROOT / 'scripts/RocketMqSmoke.java')], check=True)
    env = dict(os.environ)
    env.setdefault('DOCKER_CONFIG', str(ROOT / '.local/docker'))
    command = ['docker', 'run', '--rm', '--user', f'{os.getuid()}:{os.getgid()}', '--network', args.project + '_default',
               '--mount', f'type=bind,src={work},dst=/smoke,readonly', '--entrypoint', 'java', args.image,
               '-Duser.home=/tmp', '-cp', '/smoke:/smoke/' + jar.name, 'RocketMqSmoke', 'mq-broker:8081']
    result = subprocess.run(command, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    (reports / 'mq-native-smoke.log').write_text(result.stdout)
    payload = None
    for line in result.stdout.splitlines():
        if line.startswith('{'):
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                pass
    if result.returncode or not payload or not payload.get('bodyVerified') or not payload.get('acknowledged'):
        raise RuntimeError('Real native send/receive/Ack smoke failed; inspect .local/reports/mq-native-smoke.log')
    payload.update(executedAt=datetime.now(timezone.utc).isoformat(), brokerImage=BROKER_IMAGE,
                   clientSha256=CLIENT_SHA256, officialExampleCommit='6e2185bb638b639663bda240cf119804a595e099')
    (reports / 'mq-native-smoke.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(payload))


if __name__ == '__main__':
    main()
