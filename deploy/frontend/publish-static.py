"""Publish a verified static bundle, assets first and index last; retain old chunks."""
import hashlib,json,os,re,shutil,sys,tarfile,urllib.request
from pathlib import Path
from datetime import datetime,timezone
archive=Path(sys.argv[1]);expected=sys.argv[2];host=sys.argv[3]
assert re.fullmatch(r'[A-Za-z0-9.-]+',host)
assert hashlib.sha256(archive.read_bytes()).hexdigest()==expected
root=Path('/var/www/argus');assert root.is_dir() and (root/'index.html').is_file()
release=Path('/opt/argus/frontend/releases')/('brand-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
release.mkdir(parents=True);stage=release/'candidate';stage.mkdir()
with tarfile.open(archive,'r:gz') as tar:
 for m in tar.getmembers():
  assert not m.issym() and not m.islnk() and (m.isfile() or m.isdir())
  assert (stage/m.name).resolve().is_relative_to(stage.resolve())
 tar.extractall(stage,filter='data')
assert (stage/'index.html').is_file()
for name in re.findall(r'(?:src|href)="(/assets/[^\"]+)"',(stage/'index.html').read_text()):assert (stage/name.lstrip('/')).is_file()
with tarfile.open(release/'previous-static.tar.gz','w:gz') as tar:tar.add(root,arcname='argus')
os.chmod(release/'previous-static.tar.gz',0o600)
old=(root/'index.html').read_bytes()
def replace(src,dst):
 dst.parent.mkdir(parents=True,exist_ok=True);tmp=dst.with_name(dst.name+'.new-brand');shutil.copyfile(src,tmp);os.chmod(tmp,0o644);os.replace(tmp,dst)
try:
 for src in stage.rglob('*'):
  if src.is_file() and src.name!='index.html':replace(src,root/src.relative_to(stage))
 replace(stage/'index.html',root/'index.html')
 digest=hashlib.sha256((root/'index.html').read_bytes()).hexdigest()
 for port in (80,9090):
  req=urllib.request.Request('http://127.0.0.1:'+str(port)+'/',headers={'Host':host})
  with urllib.request.urlopen(req,timeout=5) as r:assert r.status==200 and hashlib.sha256(r.read()).hexdigest()==digest
except Exception:
 (root/'index.html.rollback').write_bytes(old);os.chmod(root/'index.html.rollback',0o644);os.replace(root/'index.html.rollback',root/'index.html');raise
report={'scope':'FRONTEND_BRAND_STATIC_RELEASE','passed':True,'indexSha256':digest,'archiveSha256':expected,'oldAssetsRetained':True,'portsVerified':[80,9090],'publishedAt':datetime.now(timezone.utc).isoformat()}
(release/'result.json').write_text(json.dumps(report,indent=2));print(json.dumps(report))
