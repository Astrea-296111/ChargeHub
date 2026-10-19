#!/usr/bin/env python3
"""Prepare private file mounts; the single monitoring password source is dev.env."""
import os
import secrets
from pathlib import Path
root = Path(__file__).resolve().parents[2]
directory = root / '.local/monitoring'
directory.mkdir(parents=True, exist_ok=True)
directory.chmod(0o700)
value = os.environ.get('CHARGEHUB_MONITOR_PASSWORD', '')
if not value:
    envfile = root / '.local/dev.env'
    if envfile.exists():
        value = next((line.split('=', 1)[1] for line in envfile.read_text().splitlines()
                      if line.startswith('CHARGEHUB_MONITOR_PASSWORD=')), '')
if len(value) < 16:
    raise SystemExit('Run python3 scripts/dev-env.py first; monitoring password must be generated there.')
monitor = directory / 'monitor-password'
if not monitor.exists() or monitor.read_text().strip() != value:
    if monitor.exists():
        monitor.chmod(0o600)
    monitor.write_text(value + '\n')
    monitor.chmod(0o444)
grafana = directory / 'grafana-admin-password'
if not grafana.exists():
    grafana.write_text(secrets.token_urlsafe(32) + '\n')
    grafana.chmod(0o444)
print('Monitoring file mounts ready under private .local/monitoring; credentials not printed.')
