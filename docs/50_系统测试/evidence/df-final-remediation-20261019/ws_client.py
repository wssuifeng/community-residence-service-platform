import json,time,threading,uuid
import websocket
class Stomp:
 def __init__(self,token,dest='/user/queue/notifications'):
  self.messages=[];self.errors=[];self.closed=False;self.connected=False;self.token=token
  self.ws=websocket.create_connection('ws://127.0.0.1:8087/ws/000/'+uuid.uuid4().hex+'/websocket',timeout=10,origin='http://127.0.0.1:5181')
  assert self.ws.recv()=='o','SockJS did not open'
  self.send('CONNECT\naccept-version:1.2\nheart-beat:10000,10000\nAuthorization:Bearer '+token+'\n\n\x00')
  frame=self.ws.recv()
  assert 'CONNECTED' in frame,'STOMP refused CONNECT'
  self.connected=True
  self.send('SUBSCRIBE\nid:'+uuid.uuid4().hex+'\ndestination:'+dest+'\nack:auto\n\n\x00')
  self.ws.settimeout(1)
  self.thread=threading.Thread(target=self.read,daemon=True);self.thread.start()
 def send(self,frame):
  self.ws.send(json.dumps([frame]));self.last_sent=time.monotonic()
 def read(self):
  while not self.closed:
   try:
    if time.monotonic()-self.last_sent>=10:self.send('\n')
    raw=self.ws.recv()
    if not raw:
     if not self.closed:self.errors.append('unexpected-close')
     break
    if raw.startswith('a'):
     for item in json.loads(raw[1:]):
      if item.startswith('MESSAGE'):
       head,body=item.split('\n\n',1)
       self.messages.append({'received':time.time(),'monotonic':time.perf_counter(),'payload':json.loads(body.rstrip('\x00'))})
      elif item.startswith('ERROR'):self.errors.append(item[:200])
   except websocket.WebSocketTimeoutException:continue
   except Exception as exc:
    if not self.closed:self.errors.append(type(exc).__name__)
    break
  self.connected=False
 def close(self):
  self.closed=True
  try:self.ws.close()
  except Exception:pass
