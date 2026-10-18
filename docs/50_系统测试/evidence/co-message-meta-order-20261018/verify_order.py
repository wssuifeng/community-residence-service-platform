"""Verify the shared message metadata using isolated synthetic HTTP only."""
import asyncio,json,sys
from pathlib import Path
from playwright.async_api import async_playwright
OUT=Path(__file__).parent
sys.path.insert(0,str(OUT.parent/'cn-conversation-density-20261017'))
from verify_density import DensityData,BASE

async def main():
 checks=[];errors=[]
 def check(name,value):
  checks.append(dict(name=name,passed=bool(value)))
  if not value:raise AssertionError(name)
 async with async_playwright() as playwright:
  browser=await playwright.chromium.launch(headless=True)
  for role,base in [('ADMIN','admin'),('RESIDENT','resident'),('STAFF','staff')]:
   context=await browser.new_context(viewport=dict(width=1440,height=900))
   data=DensityData(role);await context.route('**/*',data.handle)
   session=dict(token='synthetic-layout-only',expiresAt=4102444800000,user=dict(id=8,username='synthetic',realName='合成测试',role=role,boundCommunities=[2]))
   await context.add_init_script("sessionStorage.setItem('crsp_session',JSON.stringify("+json.dumps(session)+"))")
   page=await context.new_page();page.on('pageerror',lambda error:errors.append(str(error)))
   await page.goto(BASE+f'/{base}/conversations?id=103')
   await page.locator('.chat-message.mine').last.wait_for()
   for width,height in [(1440,900),(768,720)]:
    await page.set_viewport_size(dict(width=width,height=height))
    await page.locator('.chat-message-list').evaluate('el=>el.scrollTop=el.scrollHeight')
    for mine in [True,False]:
     selector='.chat-message.mine' if mine else '.chat-message:not(.mine)'
     boxes=await page.locator(selector).last.evaluate("el=>{const rect=x=>{const r=x.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom}};return {name:rect(el.querySelector('.chat-sender')),time:rect(el.querySelector('time')),avatar:rect(el.querySelector('.chat-avatar')),bubble:rect(el.querySelector('.chat-bubble'))}}")
     name,time=boxes['name'],boxes['time']
     gap=name['left']-time['right'] if mine else time['left']-name['right']
     check(f'{role} {width}: '+('own time left/name right near avatar' if mine else 'other name left/time right retained'),7<=gap<=9 and (name['right']<=boxes['avatar']['left'] if mine else name['left']>=boxes['avatar']['right']))
     check(f'{role} {width}: '+('own' if mine else 'other')+' metadata above bubble',max(name['bottom'],time['bottom'])<=boxes['bubble']['top']+1)
    check(f'{role} {width}: no page overflow',await page.evaluate('document.documentElement.scrollWidth<=innerWidth+1&&document.documentElement.scrollHeight<=innerHeight+1'))
    if role=='ADMIN' and width==1440:await page.locator('.chat-message-list').screenshot(path=str(OUT/'message-order.png'))
   await context.close()
  await browser.close()
 check('no JavaScript errors',not errors)
 (OUT/'browser-summary.json').write_text(json.dumps(dict(scope='isolated Chromium with synthetic HTTP; shared component metadata order only',checks=checks,errors=errors,financialCommands='none',userBrowser='untouched'),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print(json.dumps(dict(passed=len(checks),errors=errors)))
if __name__=='__main__':asyncio.run(main())
