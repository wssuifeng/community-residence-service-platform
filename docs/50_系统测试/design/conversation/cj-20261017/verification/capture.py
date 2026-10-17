"""Review the standalone design drawing only; no production API or user browser."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import json
from playwright.sync_api import sync_playwright
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'screens'
OUT.mkdir(exist_ok=True)
checks, errors, requests = [], [], []
def check(name, condition, detail=None):
    checks.append({'name': name, 'passed': bool(condition), 'detail': detail})
    if not condition: raise AssertionError(name + ': ' + str(detail))
def load(page, role='admin', state='normal', capture=True):
    page.goto((ROOT/'design-draft.html').as_uri()+f'?role={role}&state={state}&capture={int(capture)}',wait_until='networkidle')
    page.wait_for_function("window.designState !== undefined")
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True)
    context=browser.new_context(device_scale_factor=1)
    page=context.new_page()
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.on('request', lambda r: requests.append(r.url) if r.url.startswith(('http:', 'https:')) else None)
    for role in ['admin','resident','staff']:
        for w,h in [(1920,1080),(1440,900),(1366,768),(1024,768),(900,768),(768,768)]:
            page.set_viewport_size({'width':w,'height':h});load(page,role)
            g=page.evaluate("""() => {
                const r=s=>document.querySelector(s)?.getBoundingClientRect();
                const c=r('.chat-panel'), x=r('.workspace'), b=r('.business-rail'), i=r('#send');
                return {doc:document.documentElement.scrollWidth,body:document.body.scrollHeight,
                    workspace:x.toJSON(),rail:b.toJSON(),chat:c.toJSON(),send:i?.toJSON(),
                    outer:getComputedStyle(document.querySelector('.outer-nav')).display,
                    list:getComputedStyle(document.querySelector('.conversation-panel')).display};
            }""")
            check(f'{role}/{w}: no page overflow', g['doc']<=w+1 and g['body']<=h+1,g)
            check(f'{role}/{w}: rail remains visible',g['rail']['width']>=60 and g['rail']['height']>400)
            check(f'{role}/{w}: send visible',g['send']['right']<=w and g['send']['bottom']<=h)
            check(f'{role}/{w}: outer navigation correct',(g['outer']!='none')==(role=='admin'))
            if w>=1024:check(f'{role}/{w}: three segments',g['list']!='none' and g['chat']['width']>400,g)
            else:check(f'{role}/{w}: list/chat switch',g['list']=='none')
            if w in [1920,1440]:page.screenshot(path=str(OUT/f'{role}-{w}.png'))
            if w==900 and role=='admin':
                page.screenshot(path=str(OUT/'admin-narrow-chat-900.png'))
                page.get_by_role('button',name='返回会话列表',exact=True).click()
                check('narrow: list opens',page.locator('.conversation-panel').is_visible())
                page.screenshot(path=str(OUT/'admin-narrow-list-900.png'))
                page.locator('[data-key="work:2"]').click()
                check('narrow: selecting returns to chat',page.locator('.chat-panel').is_visible())
    page.set_viewport_size({'width':1440,'height':900});load(page,'admin',capture=False)
    page.locator('[data-category="feedback"]').click()
    check('category: feedback one row',page.locator('[data-key]').count()==1)
    page.locator('[data-key="feedback:3"]').click()
    check('feedback: original anonymous identity retained','匿名居民' in page.locator('#chat-panel').inner_text() and '林悦' not in page.locator('#chat-panel').inner_text())
    page.get_by_role('button',name='会话菜单',exact=True).click();page.locator('#members').click()
    check('feedback: drawer cannot reveal identity','匿名居民' in page.locator('.drawer').inner_text() and '林悦' not in page.locator('.drawer').inner_text())
    page.locator('#close-drawer').click()
    page.locator('#search').fill('林悦')
    check('feedback: cannot search hidden identity',page.locator('[data-key]').count()==0)
    page.locator('#search').fill('夜间')
    check('search: title works',page.locator('[data-key]').count()==1)
    page.locator('#search').fill('');page.locator('[data-category="all"]').click()
    page.locator('[data-key="lease:1"]').click();page.locator('#composer-input').fill('设计草稿保留')
    page.locator('[data-key="work:2"]').click();page.locator('[data-key="lease:1"]').click()
    check('draft: survives switching',page.locator('#composer-input').input_value()=='设计草稿保留')
    page.locator('#composer-input').fill('')
    page.locator('#business-tool').click()
    check('admin: agreement action only',page.locator('.business-menu').get_by_text('发出 / 查看租赁协议').is_visible() and '同意租赁协议' not in page.locator('.business-menu').inner_text())
    check('menu: sits above composer', page.locator('.business-menu').bounding_box()['y']+page.locator('.business-menu').bounding_box()['height']<=page.locator('.chat-composer').bounding_box()['y']+5)
    page.screenshot(path=str(OUT/'admin-business-menu-1440.png'))
    page.locator('[data-role="resident"]').click();page.locator('#business-tool').click()
    check('resident: agreement/viewing actions',page.locator('.business-menu').get_by_text('预约本房源看房').is_visible() and page.locator('.business-menu').get_by_text('查看 / 同意租赁协议').is_visible())
    page.screenshot(path=str(OUT/'resident-business-menu-1440.png'))
    page.locator('[data-role="staff"]').click()
    check('staff: no lease/feedback/direct',not any(x.startswith(('lease:','feedback:','direct:')) for x in page.evaluate('designState().keys')) and page.locator('[data-category="feedback"]').count()==0)
    check('staff: cannot create group',not page.locator('#create-conversation').is_visible())
    load(page,'resident','feedback')
    page.locator('#composer-input').fill('请继续匿名处理');page.locator('#send').click()
    check('feedback: demo send preserves anonymity','林悦' not in page.locator('#chat-panel').inner_text() and '匿名居民：请继续匿名处理' in page.locator('#conversation-list').inner_text())
    check('feedback: demo send does not add own unread',page.locator('[data-key="feedback:3"] .unread').count()==0)
    for state in ['feedback','closed','empty','error','offline','history']:
        load(page,'admin',state)
        if state=='feedback':
            check('state: anonymous feedback','匿名居民' in page.locator('#chat-panel').inner_text())
        elif state=='closed':check('state: closed read only',page.locator('#composer-input').count()==0 and page.locator('.readonly').is_visible())
        elif state=='empty':check('state: empty explanatory',page.get_by_text('从左侧开始一段沟通').is_visible())
        elif state=='error':
            check('state: retry available',page.locator('#retry').is_visible());page.locator('#retry').click();check('state: retry demo recovers',page.locator('#composer-input').is_visible());load(page,'admin',state)
        elif state=='offline':check('state: offline disables send',page.locator('#send').is_disabled())
        elif state=='history':
            check('state: history new message indicator',page.locator('#new-marker').is_visible())
        page.screenshot(path=str(OUT/f'admin-{state}-1440.png'))
    check('no script errors',not errors,errors)
    check('no network/business requests',not requests,requests)
    browser.close()
summary={'created_at':datetime.now(timezone(timedelta(hours=8))).isoformat(),'scope':'standalone deterministic design drawing only; not production tests','checks':checks,'passed':sum(c['passed'] for c in checks),'page_errors':errors,'http_requests':requests,'user_visual_acceptance':'not_evaluated'}
(ROOT/'verification/summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
print(f"DESIGN CHECKS: {summary['passed']}/{len(checks)}; HTTP requests={len(requests)}; page errors={len(errors)}")
