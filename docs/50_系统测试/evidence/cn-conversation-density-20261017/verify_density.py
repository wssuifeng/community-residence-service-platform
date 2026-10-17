"""Independent Chromium + owned synthetic HTTP; never attaches to a user browser."""
import argparse,asyncio,io,json,sys
from pathlib import Path
from urllib.parse import urlparse
from PIL import Image,ImageDraw
from playwright.async_api import async_playwright
OUT=Path(__file__).parent
sys.path.insert(0,str(OUT.parent/'cl-conversation-workspace-20261017'))
from verify_frontend import Synthetic,BASE,DATE

class DensityData(Synthetic):
 def __init__(self,role):
  super().__init__(role)
  for n,message in enumerate(self.messages[103]):
   message.update(content='已收到，谢谢。',senderId=8 if n%2 else 9,senderRole=role if n%2 else ('ADMIN' if role=='RESIDENT' else 'RESIDENT'),senderName='测试本人' if n%2 else '测试对方',messageType='TEXT')
  image=Image.new('RGB',(800,600),'#e4ebf8');ImageDraw.Draw(image).rectangle((80,80,720,520),fill='#9bb3df');buffer=io.BytesIO();image.save(buffer,format='PNG');self.png=buffer.getvalue()
 async def handle(self,route):
  if urlparse(route.request.url).path=='/uploads/cn-fixture.png':await route.fulfill(content_type='image/png',body=self.png);return
  await super().handle(route)

GEOMETRY="""() => {
 const rect = el => {const r=el.getBoundingClientRect();return {top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}};
 const log=document.querySelector('.chat-message-list'),bounds=log.getBoundingClientRect(),rows=[...log.querySelectorAll('.chat-message')];
 return {viewport:{width:innerWidth,height:innerHeight},pageOverflow:Math.max(0,document.documentElement.scrollHeight-innerHeight),horizontalOverflow:Math.max(0,document.documentElement.scrollWidth-innerWidth),log:rect(log),workspace:rect(document.querySelector('.conversation-workspace')),composer:rect(document.querySelector('.chat-composer')),heading:rect(document.querySelector('.chat-heading')),bubbleHeight:rows.at(-1).querySelector('.chat-bubble').getBoundingClientRect().height,rowHeight:rows.at(-1).getBoundingClientRect().height,visibleMessages:rows.filter(row=>{const r=row.getBoundingClientRect();return r.top>=bounds.top-1&&r.bottom<=bounds.bottom+1}).length};
}"""
async def main(phase):
 checks=[];errors=[];metrics=[]
 def check(name,condition):
  checks.append({'name':name,'passed':bool(condition)})
  if not condition:raise AssertionError(name)
 before=json.loads((OUT/'before-summary.json').read_text(encoding='utf-8')) if phase=='after' else None
 sizes=[(1440,900),(1366,768),(1280,720)] if phase=='before' else [(1440,900),(1366,768),(1280,720),(1920,1080),(1024,768),(768,720)]
 async with async_playwright() as playwright:
  browser=await playwright.chromium.launch(headless=True)
  for role,base in [('ADMIN','admin'),('RESIDENT','resident'),('STAFF','staff')]:
   context=await browser.new_context(viewport={'width':1440,'height':900});data=DensityData(role)
   await context.route('**/*',data.handle)
   session=dict(token='synthetic-density-only',expiresAt=4102444800000,user=dict(id=8,username='synthetic',realName='合成测试',role=role,boundCommunities=[2]))
   await context.add_init_script("sessionStorage.setItem('crsp_session',JSON.stringify("+json.dumps(session)+"))")
   page=await context.new_page();page.on('pageerror',lambda error:errors.append(str(error)))
   await page.goto(BASE+f'/{base}/conversations?id=103');await page.get_by_role('textbox',name='消息内容').wait_for();await page.locator('.chat-message').last.wait_for()
   for width,height in sizes:
    await page.set_viewport_size({'width':width,'height':height});await page.wait_for_timeout(100)
    await page.locator('.chat-message-list').evaluate('el=>el.scrollTop=el.scrollHeight');await page.wait_for_timeout(50)
    metric=await page.evaluate(GEOMETRY);metric['role']=role;metrics.append(metric)
    if phase=='after':
     key=f'{role} {width}x{height}'
     check(key+' no outer vertical scrollbar',metric['pageOverflow']<=1)
     check(key+' no horizontal overflow',metric['horizontalOverflow']<=1)
     check(key+' composer and send stay inside viewport',metric['composer']['bottom']<=height+1)
     check(key+' message area has majority of workspace height',metric['log']['height']>=metric['workspace']['height']*.58)
     check(key+' single-line content bubble compact',metric['bubbleHeight']<=44)
     check(key+' full short messages visible',metric['visibleMessages']>=5)
     check(key+' timestamp outside bubble on upper metadata row',await page.locator('.chat-message').last.evaluate("el=>{const b=el.querySelector('.chat-bubble').getBoundingClientRect(),m=el.querySelector('.chat-meta').getBoundingClientRect(),t=el.querySelector('time').getBoundingClientRect();return !el.querySelector('.chat-bubble time')&&t.bottom<=b.top+1&&Math.abs(t.right-m.right)<2}"))
     check(key+' sender and timestamp form one compact group',await page.locator('.chat-message').last.evaluate("el=>{const s=el.querySelector('.chat-sender').getBoundingClientRect(),t=el.querySelector('time').getBoundingClientRect();return t.left-s.right>=7&&t.left-s.right<=9&&Math.abs(t.top-s.top)<3}"))
     old=next((m for m in before['metrics'] if m['role']==role and m['viewport']==metric['viewport']),None)
     if old:check(key+' more message area than original',metric['log']['height']>old['log']['height']+40)
    if width in (1440,1366):await page.screenshot(path=str(OUT/f'{phase}-{base}-{width}.png'),full_page=True)
   if phase=='after':
    await page.set_viewport_size({'width':1440,'height':900});await page.get_by_role('textbox',name='消息内容').fill('紧凑布局收发核查');await page.get_by_role('button',name='发送',exact=True).click();await page.get_by_text('紧凑布局收发核查',exact=True).wait_for()
    check(role+' text send and reload still work',any(method=='POST' and path.endswith('/messages') for method,path in data.calls))
    last=data.messages[103][-1];data.messages[103].append(dict(last,id=last['id']+1,content='[图片]',messageType='IMAGE',image=dict(fileUrl='/uploads/cn-fixture.png',fileName='合成图片.png',fileSize=len(data.png))))
    await page.get_by_role('button',name='刷新',exact=True).click();await page.wait_for_timeout(5200);await page.locator('.chat-message-list').evaluate('el=>el.scrollTop=el.scrollHeight');await page.locator('.chat-image img').wait_for()
    image=await page.locator('.chat-image').bounding_box();check(role+' image thumbnail bounded',image['height']<=164 and image['width']<=240)
    await page.locator('.chat-image img').click();await page.locator('.el-image-viewer__wrapper').wait_for();check(role+' image preview retained',await page.locator('.el-image-viewer__wrapper').is_visible());await page.keyboard.press('Escape')
    await page.set_viewport_size({'width':1280,'height':600})
    await page.get_by_role('textbox',name='消息内容').evaluate("el=>el.style.height='500px'")
    short=await page.evaluate(GEOMETRY)
    check(role+' short viewport with enlarged input keeps send accessible',short['pageOverflow']<=1 and short['horizontalOverflow']<=1 and short['composer']['bottom']<=601)
    check(role+' enlarged input still leaves message history usable',short['log']['height']>=180)
    await page.get_by_role('textbox',name='消息内容').evaluate("el=>el.style.removeProperty('height')")
    await page.set_viewport_size({'width':768,'height':720})
    last=data.messages[103][-1];data.messages[103].append(dict(last,id=last['id']+1,content='房屋维修说明'+('W'*160)+'\\n'+('中文长段落内容'*30),senderName='很长的居民姓名用于验证省略显示而不挤出时间',image=None,messageType='TEXT'))
    await page.get_by_role('button',name='刷新',exact=True).click();await page.wait_for_timeout(5200)
    await page.locator('.chat-message-list').evaluate('el=>el.scrollTop=el.scrollHeight')
    check(role+' long text and long name stay in message area',await page.locator('.chat-message').last.evaluate("el=>{const l=el.closest('.chat-message-list').getBoundingClientRect(),b=el.querySelector('.chat-bubble').getBoundingClientRect(),t=el.querySelector('time').getBoundingClientRect(),s=el.querySelector('.chat-sender');return b.left>=l.left&&b.right<=l.right&&t.width>25&&s.clientWidth>0&&document.documentElement.scrollWidth<=innerWidth+1}"))
    await page.screenshot(path=str(OUT/f'after-{base}-768-long-text.png'),full_page=True)

   await context.close()
  await browser.close()
 check('no JavaScript errors',not errors)
 result=dict(scope='isolated frontend with synthetic HTTP; not backend E2E or manual acceptance',phase=phase,checks=checks,metrics=metrics,errors=errors,normalServices='untouched',financialCommands='none')
 (OUT/f'{phase}-summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print(json.dumps({'phase':phase,'checks':len(checks),'metrics':len(metrics),'errors':errors}))
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--phase',choices=['before','after'],required=True);asyncio.run(main(parser.parse_args().phase))
