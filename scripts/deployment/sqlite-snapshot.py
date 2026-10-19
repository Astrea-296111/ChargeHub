#!/usr/bin/env python3
"""SQLite online backup API plus logical verification; destinations must be new."""
import argparse
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path


def facts(connection):
    if connection.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
        raise RuntimeError('SQLite integrity_check failed')
    result={}
    for (table,) in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"):
        quoted='"'+table.replace('"','""')+'"'
        rows=connection.execute('SELECT * FROM '+quoted).fetchall()
        canonical=sorted(json.dumps(row,ensure_ascii=False,separators=(',',':')) for row in rows)
        result[table]={'rows':len(rows),'sha256':hashlib.sha256('\n'.join(canonical).encode()).hexdigest()}
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path)
    parser.add_argument('destination',type=Path)
    parser.add_argument('--expected-sha256')
    args=parser.parse_args()
    if not args.source.is_file() or args.destination.exists():
        raise SystemExit('Source must exist and destination must be a new file; existing databases are never overwritten')
    digest=hashlib.sha256(args.source.read_bytes()).hexdigest()
    if args.expected_sha256 and digest!=args.expected_sha256: raise SystemExit('Backup checksum mismatch')
    args.destination.parent.mkdir(parents=True,exist_ok=True)
    # sqlite3.Connection's context manager commits but does not close. Close explicitly
    # before hashing and make the backup a standalone file, independent of WAL/SHM sidecars.
    with closing(sqlite3.connect(args.source.as_uri()+'?mode=ro',uri=True)) as source:
        before=facts(source)
        with closing(sqlite3.connect(args.destination)) as target:
            source.backup(target)
            target.commit()
            mode=target.execute('PRAGMA journal_mode=DELETE').fetchone()[0]
            if mode.lower()!='delete': raise RuntimeError('Backup must be a standalone non-WAL file')
            target.commit()
            after=facts(target)
    if before!=after: raise SystemExit('SQLite restored logical facts differ')
    args.destination.chmod(0o600)
    print(json.dumps({'status':'PASS','sourceSha256':digest,'sourceHashScope':'main file only; logical facts include committed WAL through SQLite backup API','backupSha256':hashlib.sha256(args.destination.read_bytes()).hexdigest(),'tables':after},indent=2))


if __name__=='__main__': main()
