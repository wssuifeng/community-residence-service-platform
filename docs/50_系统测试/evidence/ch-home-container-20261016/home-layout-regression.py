"""仅首页/其共享布局几何检查；API使用合成响应，不访问业务链或交易。"""
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
import json
from playwright.sync_api import sync_playwright

OUT = Path(__file__).resolve().parent
WEB = "http://127.0.0.1:5176"
checks, dimensions = [], []

def check(name, passed):
    assert passed, name
    checks.append({"name": name, "passed": True})

def intercept(route):
    url = route.request.url
    if url.startswith(WEB + "/api/"):
        records = []
        if "/notices" in url:
            records = [{"id": 90001, "title": "首页合成长标题" + "便民服务" * 20,
                        "content": "仅用于首页布局", "publishTime": "2026-10-16T12:00:00", "isPinned": 0}]
        route.fulfill(json={"code": 200, "message": "success", "data": {"records": records, "total": len(records), "page": 1, "size": 6}})
    elif url.startswith((WEB + "/", "data:", "blob:")):
        route.continue_()
    else:
        route.abort()

with sync_playwright() as pw:
    browser = pw.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width": 1366, "height": 800})
    page.route("**/*", intercept)
    for width in [1920, 1366, 1024, 768, 360]:
        for gutter in [False, True]:
            page.set_viewport_size({"width": width, "height": 800})
            page.goto(WEB + "/guest/home")
            page.wait_for_load_state("networkidle")
            if gutter:
                page.add_style_tag(content="html{scrollbar-gutter:stable;overflow-y:scroll}")
            data = page.evaluate("""() => {
                const rect = s => document.querySelector(s).getBoundingClientRect().toJSON();
                const main = getComputedStyle(document.querySelector('.guest-main'));
                const hero = getComputedStyle(document.querySelector('.hero'));
                return {layout:rect('.guest-layout'),main:rect('.guest-main'),hero:rect('.hero'),cards:rect('.service-cards'),
                    scroll:document.documentElement.scrollWidth,mainMax:main.maxWidth,
                    mainPadding:[main.paddingTop,main.paddingLeft,main.paddingRight],
                    imageSizing:hero.backgroundSize,imagePosition:hero.backgroundPosition,
                    clip:[getComputedStyle(document.documentElement).overflowX,getComputedStyle(document.body).overflowX]};
            }""")
            prefix = str(width) + ('-scrollbar' if gutter else '-normal')
            check(prefix + ' 原1200px内容容器保留', data['mainMax'] == '1200px' and abs(data['main']['width'] - min(1200,data['layout']['width'])) < 1)
            check(prefix + ' 原容器间距保留', data['mainPadding'] == ['24px','16px','16px'])
            check(prefix + ' 通栏不含滚动条宽度', abs(data['hero']['x']) < 1 and abs(data['hero']['width']-data['layout']['width']) < 1)
            check(prefix + ' 原高度规则保留', abs(data['hero']['height'] - 464) < 1)
            check(prefix + ' 等比覆盖不拉伸', data['imageSizing'] == 'auto, cover')
            check(prefix + ' 不靠全站横向裁切', not any(x in ['hidden','clip'] for x in data['clip']))
            check(prefix + ' 长公告无整页横溢', data['scroll'] <= data['layout']['width']+1)
            check(prefix + ' 内容区不额外嵌套缩放', abs(data['cards']['x'] - data['main']['x'] - 16) < 1 and abs(data['cards']['width']-data['main']['width']+32)<1)
            page.evaluate('window.scrollTo(0,600)')
            page.wait_for_timeout(100)
            check(prefix + ' 原固定导航不被容器查询破坏', abs(page.locator('.app-header').evaluate('(n)=>n.getBoundingClientRect().top'))<1)
            page.evaluate('window.scrollTo(0,0)')
            page.wait_for_timeout(250)
            check(prefix + ' 返回顶部仍为原透明导航', page.locator('.guest-layout').evaluate("n=>n.classList.contains('is-immersive')"))
            dimensions.append({'viewport':width,'gutter':gutter,**data})
            if gutter and width in [1366,768]:
                page.screenshot(path=str(OUT / f'home-{width}.png'))
    image = page.evaluate("""async () => { const img = new Image(); img.src='/images/guest-hero-dusk.png'; await img.decode(); return {width:img.naturalWidth,height:img.naturalHeight}; }""")
    check('原图片资源可加载', image['width'] > 0 and image['height'] > 0)
    browser.close()
summary = {'checkedAt':datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds'),
    'checks':checks,'passed':len(checks),'failed':0,'dimensions':dimensions,'image':image,
    'syntheticApiResponses':True,'businessApiCalled':False,'tradingCalled':False,'userBrowserOperated':False}
(OUT/'home-layout-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n','utf-8')
print(json.dumps({'passed':len(checks),'failed':0,'scope':'homepage geometry only'},ensure_ascii=False))
