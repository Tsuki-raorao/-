"""Install domain HTTPS beside the existing IP preview and other virtual hosts.

Usage: sudo python3 deploy-domain-https.py <IP_PREVIEW_ADDRESS> <DOMAIN> <WWW_DOMAIN>
No addresses or certificate keys are embedded in this source.
"""
import hashlib,json,os,re,socket,ssl,subprocess,sys,time,urllib.request
from datetime import datetime,timezone
from pathlib import Path

def run(args):return subprocess.run(args,check=True,capture_output=True,text=True).stdout.strip()
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def tls_digest():
 context=ssl._create_unverified_context()
 with socket.create_connection(('127.0.0.1',443),timeout=5) as raw:
  with context.wrap_socket(raw,server_hostname=ip) as conn:return hashlib.sha256(conn.getpeercert(binary_form=True)).hexdigest()
def private(p,text):p.write_text(text);os.chmod(p,0o600)
ip,domain,www=sys.argv[1:];assert re.fullmatch(r'[0-9.]+',ip)
assert all(re.fullmatch(r'[a-z0-9.-]+',d) for d in (domain,www))
site=Path('/etc/nginx/sites-enabled/argus-wx-s.cn');extra=Path('/etc/nginx/sites-enabled/zz-argus-domain-https')
assert site.is_file() and not extra.exists()
source=site.read_text();needle='server_name '+domain+' '+www+' '+ip+';';assert source.count(needle)==1
cert=Path('/etc/letsencrypt/live/argus-domain/fullchain.pem');assert cert.is_file()
# Preserve the earlier lexical order of the relay 443 virtual host. The new TLS
# host is deliberately in zz-* rather than inserted ahead of the relay.
others={str(p):sha(p.resolve()) for p in Path('/etc/nginx/sites-enabled').iterdir() if p!=site and p.is_file()}
default_tls=tls_digest()
backup=Path('/opt/argus/backups')/('domain-https-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'));backup.mkdir(mode=0o700)
private(backup/'argus-before.conf',source);private(backup/'nginx-before.txt',run(['nginx','-T']))
preview=source.replace(needle,'server_name '+ip+';')
start=source.index('server {');body=source[start:]
for pattern in (r'(?m)^\s*listen[^;]+;\s*$',r'(?m)^\s*server_name[^;]+;\s*$'):
 body=re.sub(pattern,'',body)
body=body.replace('server {','server {\n    listen 443 ssl;\n    listen [::]:443 ssl;\n    server_name '+domain+' '+www+';\n    ssl_certificate /etc/letsencrypt/live/argus-domain/fullchain.pem;\n    ssl_certificate_key /etc/letsencrypt/live/argus-domain/privkey.pem;\n    ssl_protocols TLSv1.2 TLSv1.3;\n    ssl_session_cache shared:argus_domain_tls:2m;\n    ssl_session_timeout 1d;\n    ssl_session_tickets off;',1)
root=re.search(r'(?m)^\s*root\s+([^;]+);',source).group(1)
redirect='''# Domain HTTP redirects only; IP preview and ACME remain available.
server {
    listen 80;
    listen [::]:80;
    listen 9090;
    listen [::]:9090;
    server_name DOMAIN WWW;
    root ROOT;
    location ^~ /.well-known/acme-challenge/ { try_files $uri =404; }
    location / { return 308 https://$host$request_uri; }
}
'''.replace('DOMAIN',domain).replace('WWW',www).replace('ROOT',root)
service=Path('/etc/systemd/system/argus-domain-cert-renew.service');timer=Path('/etc/systemd/system/argus-domain-cert-renew.timer')
assert not service.exists() and not timer.exists()
report={'scope':'ARGUS_DOMAIN_HTTPS_ROLLOUT','passed':False,'checks':[]}
def check(v,n):report['checks'].append({'name':n,'passed':bool(v)});assert v,n
try:
 site.write_text(preview);extra.write_text(redirect+'\n'+body)
 run(['nginx','-t']);run(['systemctl','reload','nginx']);time.sleep(1)
 check(all(sha(Path(p))==v for p,v in others.items()),'other virtual-host files unchanged')
 check(tls_digest()==default_tls,'existing IP TLS certificate preserved')
 for host in (domain,www):
  req=urllib.request.Request('http://127.0.0.1/api/health',headers={'Host':host})
  class NoRedirect(urllib.request.HTTPRedirectHandler):
   def redirect_request(self,*args):return None
  try:urllib.request.build_opener(NoRedirect).open(req,timeout=5);raise AssertionError('expected redirect')
  except urllib.error.HTTPError as e:check(e.code==308 and e.headers['Location']=='https://'+host+'/api/health','HTTP domain redirect '+host)
 for port in (80,9090):
  req=urllib.request.Request('http://127.0.0.1:'+str(port)+'/api/health',headers={'Host':ip})
  with urllib.request.urlopen(req,timeout=5) as response:check(json.load(response)['data']['status']=='UP','IP health retained '+str(port))
 service.write_text('[Unit]\nDescription=Renew Argus domain HTTPS certificate\nAfter=network-online.target\nWants=network-online.target\nConditionPathExists=/etc/letsencrypt/renewal/argus-domain.conf\n\n[Service]\nType=oneshot\nExecStart=/opt/collab-relay-acme/venv/bin/certbot renew --cert-name argus-domain --quiet\nPrivateTmp=true\n')
 timer.write_text('[Unit]\nDescription=Check Argus domain certificate twice daily\n\n[Timer]\nOnCalendar=*-*-* 02,14:15:00 UTC\nRandomizedDelaySec=1h\nPersistent=true\n\n[Install]\nWantedBy=timers.target\n')
 run(['systemctl','daemon-reload']);run(['systemctl','enable','--now',timer.name]);check(run(['systemctl','is-active',timer.name])=='active','dedicated renewal timer active')
 report.update(passed=True,certificateNotAfter=run(['openssl','x509','-in',str(cert),'-noout','-enddate']).split('=',1)[1],checkedAt=datetime.now(timezone.utc).isoformat())
except Exception:
 site.write_text(source);extra.unlink(missing_ok=True)
 subprocess.run(['systemctl','disable','--now',timer.name],capture_output=True)
 timer.unlink(missing_ok=True);service.unlink(missing_ok=True)
 run(['systemctl','daemon-reload']);run(['nginx','-t']);run(['systemctl','reload','nginx']);raise
finally:
 private(backup/'result.json',json.dumps(report,indent=2))
print(json.dumps(report))
