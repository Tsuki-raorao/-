"""Publish a verified small static archive, assets first and index last."""
import hashlib,json,os,re,shutil,subprocess,sys,tarfile
from datetime import datetime,timezone
from pathlib import Path
archive=Path(sys.argv[1]);expected=sys.argv[2];assert hashlib.sha256(archive.read_bytes()).hexdigest()==expected
root=Path('/var/www/argus');statefile=Path('/opt/argus/backend/releases/argus-first-four-20261019/status.json')
state=json.loads(statefile.read_text());old=(root/'index.html').read_bytes();assert hashlib.sha256(old).hexdigest()==sys.argv[3], 'live index differs from the explicitly verified prior frontend release'
backup=Path('/opt/argus/backups')/('domain-footer-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'));backup.mkdir(mode=0o700)
(backup/'index-before.html').write_bytes(old);(backup/'release-before.json').write_bytes(statefile.read_bytes())
for p in backup.iterdir():os.chmod(p,0o600)
with tarfile.open(archive) as f:
 members=f.getmembers();assert all(m.isfile() and (m.name=='index.html' or re.fullmatch(r'assets/[A-Za-z0-9_.-]+\.(js|css)',m.name)) for m in members)
 assert len({m.name for m in members})==len(members) and sum(m.name=='index.html' for m in members)==1
 for m in sorted(members,key=lambda x:x.name=='index.html'):
  dest=root/m.name;dest.parent.mkdir(parents=True,exist_ok=True);data=f.extractfile(m).read();tmp=dest.with_name(dest.name+'.argus-publish-tmp');tmp.write_bytes(data);os.chmod(tmp,0o644);os.replace(tmp,dest)
new=hashlib.sha256((root/'index.html').read_bytes()).hexdigest()
state['staticPublicationHistory']=state.get('staticPublicationHistory',[])+[{'previousIndexSha256':hashlib.sha256(old).hexdigest(),'previousRecordedIndexSha256':state['staticIndexSha256'],'indexSha256':new,'reason':'DOMAIN_HTTPS_AND_ICP_FOOTER','publishedAt':datetime.now(timezone.utc).isoformat()}]
state['staticIndexSha256']=new
statefile.write_text(json.dumps(state,indent=2));os.chmod(statefile,0o600)
print(json.dumps({'scope':'DOMAIN_ICP_STATIC_RELEASE','passed':True,'indexSha256':new,'previousIndexSha256':hashlib.sha256(old).hexdigest(),'publishedEntries':[m.name for m in members],'oldAssetsRetained':True}))
