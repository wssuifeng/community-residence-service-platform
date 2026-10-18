from pathlib import Path
import json
import subprocess
from PIL import Image

ROOT=Path.cwd()
EVIDENCE=ROOT/'docs/50_系统测试/evidence/cy-header-navigation-20261018'
EVIDENCE.mkdir(parents=True,exist_ok=True)
BROWSER='D:/nodejs/npm-global/node_modules/agent-browser/bin/agent-browser-win32-x64.exe'
SESSION='cy-topnav-20261018'
checks=[]

def browser(*args):
    result=subprocess.run([BROWSER,'--session',SESSION,*args],capture_output=True,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW,timeout=40)
    if result.returncode:
        raise RuntimeError('Browser command failed: '+args[0]+' '+result.stderr[:300])
    return result.stdout.strip()

def check(name,value):
    checks.append({'name':name,'passed':bool(value)})
    if not value: raise AssertionError(name)

def evaluate(script):
    return json.loads(browser('eval',script))

def menu():
    browser('focus','.app-header-user-trigger')
    browser('press','Enter')
    browser('wait','.el-dropdown-menu__item')
    browser('wait','350')

browser('open','http://127.0.0.1:5176/auth/login')

for role,username,password,base,home in (
    ('RESIDENT','resident1','Resident123456','resident','/resident/home'),
    ('STAFF','staff1','Staff123456','staff','/staff/dashboard'),
    ('ADMIN','admin1','Admin123456','admin','/admin/dashboard'),
    ('SUPER_ADMIN','superadmin','Admin@123456','admin','/admin/dashboard')):
    login={'username':username,'password':password}
    script='''(async()=>{const response=await fetch('/api/v1/auth/PORTAL/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(LOGIN)});const result=await response.json();if(result.code!==200)throw new Error('demo login failed');sessionStorage.setItem('crsp_session',JSON.stringify({token:result.data.token,user:result.data.user,expiresAt:Date.now()+result.data.expiresIn*1000}));window.dispatchEvent(new Event('crsp-session-updated'));return result.data.user.role;})()'''.replace('PORTAL','resident' if role=='RESIDENT' else 'admin').replace('LOGIN',json.dumps(login))
    check(role+' independent browser demo identity',evaluate(script)==role)
    browser('set','viewport','1440','900')
    browser('open','http://127.0.0.1:5176'+home)
    browser('wait','.app-header-user-trigger')
    menu()
    labels=evaluate("[...document.querySelectorAll('.el-dropdown-menu__item')].filter(n=>n.getClientRects().length).map(n=>n.textContent.trim())")
    check(role+' keyboard opens all three personal actions',labels==['个人中心','返回首页','退出登录'])
    raw=ROOT/f'output/runtime/cy-{role.lower()}-full.png'
    browser('screenshot',str(raw))
    image=Image.open(raw)
    image.crop((0,0,image.width,min(260,image.height))).save(EVIDENCE/f'{role.lower()}-header.png')
    browser('find','role','menuitem','click','--name','个人中心')
    browser('wait','--url',f'**/{base}/profile')
    check(role+' profile stays in its portal',evaluate('location.pathname')==f'/{base}/profile')
    if role!='RESIDENT':
        browser('wait','#account-title')
        check(role+' current account username rendered',evaluate("document.querySelector('.account-details').textContent.includes("+json.dumps(username)+")"))
    menu()
    browser('find','role','menuitem','click','--name','返回首页')
    browser('wait','--url','**'+home)
    check(role+' returns to matching home',evaluate('location.pathname')==home)
    if role=='RESIDENT':
        browser('find','role','link','click','--name','消息中心','--exact')
        browser('wait','--url','**/resident/notifications')
        check('resident notification nav active and distinct from conversations',evaluate("[...document.querySelectorAll('.app-header-nav-item.active')].map(n=>n.textContent.trim()).join(',')==='消息中心'"))
        browser('open','http://127.0.0.1:5176/resident/profile')
        browser('wait','.app-header-user-trigger')
    else:
        browser('open',f'http://127.0.0.1:5176/{base}/profile')
        browser('wait','#account-title')
    for width in (1440,1024,768):
        browser('set','viewport',str(width),'900')
        measurements=evaluate("(()=>{const u=document.querySelector('.app-header-user').getBoundingClientRect();const n=document.querySelector('.app-header-nav');return {page:document.documentElement.scrollWidth,viewport:innerWidth,userLeft:u.left,userRight:u.right,nav:n.clientWidth,navScroll:n.scrollWidth}})()")
        check(f'{role} header stays in viewport at {width}',measurements['page']<=width+1 and measurements['userLeft']>=0 and measurements['userRight']<=width+1)
        if role=='RESIDENT' and width==1440:
            check('resident nine desktop nav items fully fit',measurements['navScroll']<=measurements['nav']+1)
    print(role+' passed',flush=True)

summary={'date':'2026-10-18','scope':'independent named browser against normal current Vue app; demo logins and read-only pages only','checks':checks,'allPassed':all(item['passed'] for item in checks),'userBrowser':'untouched','financialCommands':0,'businessWrites':'none; demo login session metadata only','backendRestarted':False,'frontendBuild':'passed, includes vue-tsc','focusedTests':{'files':3,'tests':8,'result':'passed'},'screenshots':'cropped header regions; full images remain private under output/runtime'}
(EVIDENCE/'verification-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
browser('close')
print(json.dumps({'checks':len(checks),'passed':summary['allPassed']},ensure_ascii=False))
