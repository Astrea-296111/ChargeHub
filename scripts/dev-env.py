#!/usr/bin/env python3
"""Generate local-only random development credentials. Never overwrites existing values."""
from pathlib import Path
import secrets
p=Path(__file__).resolve().parents[1]/'.local/dev.env'
p.parent.mkdir(exist_ok=True)
if not p.exists():
 values={
  'CHARGEHUB_DB_PASSWORD': secrets.token_hex(24),
  'MYSQL_ROOT_PASSWORD':secrets.token_hex(24),
  'CHARGEHUB_PAYMENT_KEY':secrets.token_hex(32),
  'CHARGEHUB_DEVICE_KEY':secrets.token_hex(32),
  'CHARGEHUB_CONTROL_KEY':secrets.token_hex(32),
  'CHARGEHUB_DEMO_PASSWORD':secrets.token_urlsafe(24),
  'CHARGEHUB_FIXED_TIME':'2026-10-18T07:00:00Z',
 }
 p.write_text('\n'.join(f'{k}={v}' for k,v in values.items())+'\n');p.chmod(0o600)
 print('Generated .local/dev.env; credentials are local and ignored by Git.')
else: print('Kept existing .local/dev.env')
