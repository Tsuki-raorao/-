"""Loopback-only acceptance proxy. Fixed API targets; no arbitrary URL proxy.
Agent fault injection and web hosting use separate ports, never production ports.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import os
from pathlib import Path
import threading
import time

ROOT = Path('/opt/argus/acceptance-20261019')
TOKEN = os.environ['ACCEPTANCE_PROXY_TOKEN']
state = {'mode': 'normal', 'hidden': [], 'taskPosts': 0, 'droppedAcks': 0}
lock = threading.Lock()

class Proxy(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not persist tokens or log lines containing arbitrary query strings.

    def send(self, code, body, content='application/json'):
        if not isinstance(body, bytes):
            body = json.dumps(body).encode()
        self.send_response(code); self.send_header('Content-Type', content)
        self.send_header('Content-Length', str(len(body))); self.send_header('Cache-Control', 'no-store')
        self.end_headers(); self.wfile.write(body)

    def dispatch(self):
        size = int(self.headers.get('Content-Length', '0'))
        if size < 0 or size > 65536:
            return self.send(413, {'error': 'bounded request body'})
        body = self.rfile.read(size) if size else None
        agent = self.server.server_port == 18091
        if self.path.startswith('/__acceptance/'):
            if self.headers.get('Authorization') != 'Bearer '+TOKEN:
                return self.send(401, {'error': 'unauthorized'})
            if self.command == 'POST' and self.path == '/__acceptance/fault':
                mode = json.loads(body or b'{}').get('mode')
                if mode not in ('normal', 'health-down', 'hide-task-result'):
                    return self.send(400, {'error': 'unknown fault'})
                with lock:
                    state['mode'] = mode
                    if mode == 'normal': state['hidden'] = []
            with lock: snapshot = dict(state)
            return self.send(200, snapshot)
        if not agent and not self.path.startswith('/api/'):
            target = (ROOT/'frontend'/self.path.split('?',1)[0].lstrip('/')).resolve()
            if not target.is_relative_to((ROOT/'frontend').resolve()):
                return self.send(404, {})
            if not target.is_file(): target = ROOT/'frontend/index.html'
            content = {'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.png':'image/png'}.get(target.suffix,'application/octet-stream')
            return self.send(200,target.read_bytes(),content)
        if agent and not self.path.startswith('/api/agent/'):
            return self.send(404,{})
        with lock: mode = state['mode']; hidden = list(state['hidden'])
        if agent and mode == 'health-down' and self.path == '/api/agent/health':
            return self.send(503, {'error':'acceptance health fault'})
        if agent and mode == 'hide-task-result' and self.command == 'GET' and self.path.split('/')[-1] in hidden:
            return self.send(504, {'error':'acceptance response hidden'})
        headers = {k:v for k,v in self.headers.items() if k.lower() in ('authorization','content-type','idempotency-key')}
        connection = http.client.HTTPConnection('127.0.0.1',18090 if agent else 19091,timeout=12)
        try:
            connection.request(self.command,self.path,body,headers)
            response = connection.getresponse(); result = response.read()
            if agent and self.command == 'POST' and self.path == '/api/agent/tasks':
                with lock:
                    state['taskPosts'] += 1
                    if mode == 'hide-task-result':
                        command = json.loads(body or b'{}')
                        state['hidden'].append(command.get('taskId',command.get('commandId','')))
                        state['droppedAcks'] += 1
                if mode == 'hide-task-result': return self.send(504,{'error':'acceptance accepted response hidden'})
            return self.send(response.status,result,response.getheader('Content-Type','application/json'))
        except (OSError, http.client.HTTPException):
            return self.send(502,{'error':'acceptance upstream unavailable'})
        finally:
            connection.close()

    do_GET = dispatch
    do_POST = dispatch

servers = [ThreadingHTTPServer(('127.0.0.1',port),Proxy) for port in (18091,19090)]
for server in servers[:-1]:
    threading.Thread(target=server.serve_forever,daemon=True).start()
servers[-1].serve_forever()
