from pathlib import Path
import json,subprocess
base=Path('docs/50_系统测试/evidence/cv-architecture-implementation-20261018')
exe='D:/nodejs/npm-global/node_modules/agent-browser/bin/agent-browser-win32-x64.exe'
prefix=[exe,'--session','cv-architecture-20261018','--json']
def browser(*args):
 p=subprocess.run(prefix+list(args),capture_output=True,text=True,encoding='utf-8',check=True)
 result=json.loads(p.stdout)
 if not result.get('success'):raise RuntimeError(result)
 return result.get('data',{})
def evaluate(code):return browser('eval',code)['result']
rows=[]
for mode,selector in [('staff','.todo-row'),('lease','.lease-row'),('structure','.grid-block')]:
 for width in [1440,1024]:
  browser('set','viewport',str(width),'1000')
  browser('open',f'http://127.0.0.1:5188/cv-architecture.html?mode={mode}')
  evaluate(f'''(async()=>{{for(let i=0;i<80&&!document.querySelector('{selector}');i++)await new Promise(r=>setTimeout(r,50));if(!document.querySelector('{selector}'))throw new Error('页面尚未加载');return true}})()''')
  data=evaluate('window.__cv.measure()');assert data['documentWidth']<=width,data;assert not data['faults'],data
  if mode=='staff':assert len([c for c in data['calls'] if c['url']=='/staff/workbench/summary'])==1,data
  if mode=='lease':
   assert len(data['calls'])==1,data
   expanded=evaluate('''(async()=>{document.querySelector('.lease-row').click();await window.__cv.settle();return window.__cv.measure()})()''')
   assert len([c for c in expanded['calls'] if c['url'].endswith('/payments')])==1,expanded
   data['afterOneExpanded']=expanded['calls']
  if mode=='structure':
   detail=evaluate('''(async()=>{document.querySelector('.grid-block').click();await window.__cv.settle();return {dialogs:document.querySelectorAll('.el-dialog').length,faults:window.__cv.faults}})()''')
   assert detail['dialogs']==1 and not detail['faults'],detail
   data['detail']=detail
  browser('screenshot',str(base/f'{mode}-{width}.png'))
  rows.append(data)
browser('open','http://127.0.0.1:5188/cv-architecture.html?mode=staff&fail=1')
evaluate('''(async()=>{for(let i=0;i<50&&!document.querySelector('[role=alert]');i++)await new Promise(r=>setTimeout(r,50));return true})()''')
error=evaluate('''({alert:document.querySelector('[role=alert]')?.textContent,hasSummary:!!document.querySelector('.overview-panel'),hasEventStats:!!document.querySelector('[data-testid=today-submitted]')})''')
assert not error['hasSummary'] and error['hasEventStats'],error
browser('screenshot',str(base/'staff-error-1024.png'))
retry=evaluate('''(async()=>{window.__cv.fail.value=false;[...document.querySelectorAll('button')].find(b=>b.textContent.includes('重新加载工作台')).click();await window.__cv.settle();return !!document.querySelector('.overview-panel')})()''');assert retry
(base/'o8-browser.json').write_text(json.dumps({'scope':'Isolated current Vue components; synthetic Axios adapter blocks all unspecified requests. No backend E2E or production data.','layouts':rows,'summaryFailure':error,'retrySucceeded':retry},ensure_ascii=False,indent=2),encoding='utf-8')
print('Layout cases:',len(rows),'Failure and retry: passed')
# Engine exercise is separate from UI layout. Existing 30-channel cap is retained.
browser('open','http://127.0.0.1:5188/cv-architecture.html?mode=cache')
evaluate('''(async()=>{for(let i=0;i<50&&!document.querySelector('.synthetic-message');i++)await new Promise(r=>setTimeout(r,50));return true})()''')
cache=evaluate('''(async()=>{
const cv=window.__cv,paint=()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))
const initial={messages:cv.channel.messages.value.length,reads:cv.windowReads}
const t=performance.now();for(let i=0;i<4;i++)await cv.channel.loadOlder();await paint()
const history={messages:cv.channel.messages.value.length,hasOlder:cv.channel.hasOlder.value,renderAndLoadMs:performance.now()-t,dom:document.querySelectorAll('*').length}
cv.channel.text.value='保留草稿';const start=performance.now();for(let i=2;i<=20;i++){cv.chatId.value=i;await cv.settle()}cv.chatId.value=1;await cv.settle()
return {initial,history,switch20Ms:performance.now()-start,restored:{messages:cv.channel.messages.value.length,draft:cv.channel.text.value},reads:cv.windowReads,faults:cv.faults}
})()''')
assert cache['initial']['messages']==40 and cache['history']['messages']==200,cache
assert cache['restored']['messages']==200 and cache['restored']['draft']=='保留草稿',cache
(base/'o9-cache-measurement.json').write_text(json.dumps({'fixture':'Production shared useChatChannel, synthetic renderer/adapter. Four explicit older-page loads and twenty channel switches. Switching time includes deliberate 180ms settling per switch, not a latency benchmark.','result':cache,'sourceBound':'Existing channels.size > 30 evicts oldest channel. No new capacity/virtualization policy added.'},ensure_ascii=False,indent=2),encoding='utf-8')
print('Cache history:',cache['history']['messages'],'Draft restored:',cache['restored']['draft'])
