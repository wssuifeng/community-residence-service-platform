# Targeted sidebar CSS review only; never connects to production APIs.
from pathlib import Path
from datetime import datetime, timezone, timedelta
import json
from playwright.sync_api import sync_playwright
PROJECT=next(p for p in Path(__file__).resolve().parents if (p/'frontend/package.json').exists())
ROOT=Path(__file__).resolve().parent
CJ=ROOT.parent/'cj-20261017'
CSS=(PROJECT/'frontend/src/styles/sidebar-scrollbar.css').read_text(encoding='utf-8')
checks=[];errors=[];requests=[]
def check(name,ok,detail=None):
 checks.append({'name':name,'passed':bool(ok),'detail':detail})
 assert ok,(name,detail)
def measure(nav):
 return nav.evaluate('''e=>({scrollHeight:e.scrollHeight,clientHeight:e.clientHeight,top:e.scrollTop,
   width:e.getBoundingClientRect().width,track:getComputedStyle(e,"::-webkit-scrollbar").width,
   thumb:getComputedStyle(e,"::-webkit-scrollbar-thumb").backgroundColor,
   arrows:getComputedStyle(e,"::-webkit-scrollbar-button").display})''')
with sync_playwright() as p:
 b=p.chromium.launch(headless=True,args=['--disable-features=OverlayScrollbar'])
 page=b.new_page(viewport={'width':1440,'height':720})
 page.on('pageerror',lambda e:errors.append(str(e)))
 page.on('request',lambda r:requests.append(r.url) if r.url.startswith(('http:','https:')) else None)
 page.goto((CJ/'design-draft.html').as_uri()+'?role=admin&capture=1',wait_until='networkidle')
 nav=page.locator('#outer-nav')
 page.mouse.move(1200,100);base=measure(nav)
 check('sidebar has overflowing content',base['scrollHeight']>base['clientHeight'],base)
 check('track is 4px, not native auto',base['track']=='4px',base)
 check('idle thumb is transparent',base['thumb']=='rgba(0, 0, 0, 0)',base)
 check('native arrow buttons removed',base['arrows']=='none',base)
 nav.hover();hovered=measure(nav)
 check('hover reveals subtle thumb',hovered['thumb']=='rgba(255, 255, 255, 0.28)',hovered)
 check('hover does not move layout',abs(hovered['width']-base['width'])<.1)
 nav.screenshot(path=str(ROOT/'screens/after-hover-sidebar.png'))
 page.mouse.wheel(0,700);page.wait_for_timeout(150)
 check('wheel still scrolls',measure(nav)['top']>0)
 nav.focus();nav.press('Home');page.wait_for_timeout(150);nav.press('End');page.wait_for_timeout(150)
 check('keyboard reaches last menu',measure(nav)['top']>=base['scrollHeight']-base['clientHeight']-1,measure(nav))
 page.mouse.move(1200,100)
 check('keyboard focus also reveals thumb',measure(nav)['thumb']=='rgba(255, 255, 255, 0.28)')
 nav.evaluate('e=>e.blur()');page.mouse.move(1200,100)
 check('leaving returns to transparent',measure(nav)['thumb']=='rgba(0, 0, 0, 0)')
 nav.screenshot(path=str(ROOT/'screens/after-idle-sidebar.png'))
 for w,h in [(1366,768),(1440,900),(1920,1080)]:
  page.set_viewport_size({'width':w,'height':h})
  page.goto((CJ/'design-draft.html').as_uri()+'?role=admin&capture=1',wait_until='networkidle')
  check(f'{w}: no horizontal overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
  if w in [1440,1920]:page.screenshot(path=str(CJ/f'screens/admin-{w}.png'))
 for state in ['business','closed','empty','error','offline','history','feedback']:
  page.set_viewport_size({'width':1440,'height':900})
  page.goto((CJ/'design-draft.html').as_uri()+f'?role=admin&capture=1&state={state}',wait_until='networkidle')
  name='admin-business-menu-1440.png' if state=='business' else f'admin-{state}-1440.png'
  page.screenshot(path=str(CJ/'screens'/name))
 page.set_viewport_size({'width':900,'height':768})
 page.goto((CJ/'design-draft.html').as_uri()+'?role=admin&capture=1',wait_until='networkidle')
 page.screenshot(path=str(CJ/'screens/admin-narrow-chat-900.png'))
 page.locator('#back-to-list').click();page.screenshot(path=str(CJ/'screens/admin-narrow-list-900.png'))
 # Same production CSS, isolated fixture with real focusable links; no API claims.
 links=''.join(f'<a href="#item{i}" style="display:block;padding:12px;color:white">Menu {i}</a>' for i in range(25))
 page.set_content('<style>'+CSS+'</style><aside class="app-sidebar" style="width:232px;height:260px;overflow-y:auto;background:#10233f">'+links+'</aside>')
 nav=page.locator('.app-sidebar');page.mouse.move(600,500)
 check('production CSS applies to existing sidebar class',measure(nav)['track']=='4px')
 nav.locator('a').first.focus()
 check('production links reveal focus thumb',measure(nav)['thumb']=='rgba(255, 255, 255, 0.28)')
 page.emulate_media(forced_colors='active')
 check('high contrast has wider visible track',measure(nav)['track']=='12px',measure(nav))
 check('high contrast thumb remains visible',measure(nav)['thumb']!='rgba(0, 0, 0, 0)')
 check('stylesheet loaded by production entry',"import './styles/sidebar-scrollbar.css'" in (PROJECT/'frontend/src/main.ts').read_text(encoding='utf-8-sig'))
 check('no script errors',not errors,errors);check('no HTTP requests',not requests,requests)
 b.close()
summary={'created_at':datetime.now(timezone(timedelta(hours=8))).isoformat(),'scope':'sidebar-only design checks and actual production CSS in isolated fixture, not whole application tests','checks':checks,'passed':len(checks),'page_errors':errors,'http_requests':requests,'user_visual_acceptance':'not_evaluated','previous_cj_114':'historical; not rerun or added to this count'}
(ROOT/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(f"SIDEBAR CHECKS: {len(checks)}/{len(checks)}; HTTP requests={len(requests)}")
