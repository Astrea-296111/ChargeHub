#!/usr/bin/env python3
import time,urllib.request,sys,json
for _ in range(60):
 try:
  with urllib.request.urlopen('http://127.0.0.1:8080/actuator/health',timeout=2) as r:
   if json.load(r).get('status')=='UP':print('API database health UP');sys.exit(0)
 except Exception:pass
 time.sleep(1)
raise SystemExit('API did not become healthy; inspect docker compose logs api worker')
