import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

SCRIPT=Path(__file__).with_name('sqlite-snapshot.py').resolve()
class SQLiteSnapshotTest(unittest.TestCase):
    def test_live_wal_facts_become_standalone_backup_restorable_from_readonly_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=root/'source.sqlite3';reports=root/'reports';reports.mkdir();backup=reports/'backup.sqlite3';restored=root/'restored.sqlite3'
            with sqlite3.connect(source) as live:
                live.execute('PRAGMA journal_mode=WAL');live.execute('CREATE TABLE facts(id INTEGER PRIMARY KEY,value TEXT NOT NULL)')
                live.executemany('INSERT INTO facts(value) VALUES(?)',[(str(i),) for i in range(5)]);live.commit()
                first=subprocess.run([sys.executable,str(SCRIPT),str(source),str(backup)],capture_output=True,text=True,check=True)
                proof=json.loads(first.stdout);self.assertEqual(proof['tables']['facts']['rows'],5)
                self.assertEqual(proof['backupSha256'],hashlib.sha256(backup.read_bytes()).hexdigest())
                with sqlite3.connect(backup.as_uri()+'?mode=ro',uri=True) as readonly:self.assertEqual(readonly.execute('PRAGMA journal_mode').fetchone()[0],'delete')
                reports.chmod(0o555)
                try:
                    final=subprocess.run([sys.executable,str(SCRIPT),str(backup),str(restored),'--expected-sha256',proof['backupSha256']],capture_output=True,text=True,check=True)
                finally:reports.chmod(0o755)
                self.assertEqual(json.loads(final.stdout)['tables'],proof['tables'])
    def test_wrong_backup_checksum_never_creates_restore_destination(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=root/'source.sqlite3';restored=root/'restore.sqlite3'
            with sqlite3.connect(source) as db:db.execute('CREATE TABLE independent(id INTEGER)')
            result=subprocess.run([sys.executable,str(SCRIPT),str(source),str(restored),'--expected-sha256','0'*64],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0);self.assertFalse(restored.exists());self.assertIn('Backup checksum mismatch',result.stderr)

if __name__=='__main__':unittest.main()
