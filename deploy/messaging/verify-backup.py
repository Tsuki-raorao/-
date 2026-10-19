"""Read-only validation of the private pre-release backup manifest and gzip."""
import gzip
import hashlib
import json
from pathlib import Path
import sys

root=Path(sys.argv[1]).resolve()
if not root.is_relative_to(Path('/opt/argus/backups')) or not root.name.startswith('first-four-'):raise SystemExit('explicit application backup required')
manifest=json.loads((root/'manifest.json').read_text())
for name,facts in manifest['files'].items():
    path=(root/name).resolve()
    if not path.is_relative_to(root) or not path.is_file() or (root/name).is_symlink():raise SystemExit('unexpected/missing manifest entry')
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(65536),b''):digest.update(block)
    if path.stat().st_size!=facts['bytes'] or digest.hexdigest()!=facts['sha256']:raise SystemExit('backup file checksum mismatch')
with gzip.open(root/'database.sql.gz','rb') as stream:
    size=0
    for block in iter(lambda:stream.read(65536),b''):size+=len(block)
if size==0:raise SystemExit('empty database backup')
print(json.dumps({'manifestVerified':True,'filesVerified':len(manifest['files']),'databaseGzipVerified':True,
                  'databaseSqlBytes':size,'flywayVersionBefore':manifest['flywayVersionBefore']}))
