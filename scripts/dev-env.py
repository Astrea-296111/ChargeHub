#!/usr/bin/env python3
"""Add missing local development credentials, preserving all existing values."""
from pathlib import Path
import secrets
p=Path(__file__).resolve().parents[1]/'.local/dev.env'
p.parent.mkdir(exist_ok=True)
original=p.read_text() if p.exists() else ''
existing={line.split('=',1)[0] for line in original.splitlines() if '=' in line and not line.startswith('#')}
defaults={
 'CHARGEHUB_DB_PASSWORD':lambda:secrets.token_hex(24),
 'MYSQL_ROOT_PASSWORD':lambda:secrets.token_hex(24),
 'CHARGEHUB_PAYMENT_KEY':lambda:secrets.token_hex(32),
 'CHARGEHUB_DEVICE_KEY':lambda:secrets.token_hex(32),
 'CHARGEHUB_CONTROL_KEY':lambda:secrets.token_hex(32),
 'CHARGEHUB_DEMO_PASSWORD':lambda:secrets.token_urlsafe(24),
 'CHARGEHUB_MONITOR_PASSWORD':lambda:secrets.token_urlsafe(24),
 'CHARGEHUB_FIXED_TIME':lambda:'2026-10-18T07:00:00Z',
}
added=[f'{name}={factory()}' for name,factory in defaults.items() if name not in existing]
if added:
 p.write_text(original+('\n' if original and not original.endswith('\n') else '')+'\n'.join(added)+'\n')
 p.chmod(0o600)
 print('Added missing development configuration names: '+', '.join(line.split('=',1)[0] for line in added))
else:print('Kept existing local development configuration')
