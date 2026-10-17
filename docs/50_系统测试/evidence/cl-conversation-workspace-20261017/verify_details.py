"""Only affected detail->workspace paths, with isolated synthetic HTTP data."""
import asyncio,json,re
from urllib.parse import urlparse
from pathlib import Path
from playwright.async_api import async_playwright
from verify_frontend import Synthetic,BASE,DATE,check,checks
OUT=Path(__file__).parent
class Details(Synthetic):
 def __init__(self,role):super().__init__(role);self.fail_fees=True
 async def handle(self,route):
  request=route.request;path=urlparse(request.url).path.removeprefix('/api/v1');result=None;matched=True
  if path=='/work-orders/8' or path=='/work-orders/8/conversation':result=dict(id=8,orderNo='SYNTH-WO-8',communityId=2,residentId=8,residentName='合成居民',title='合成维修工单',content='只验证沟通入口，不操作业务状态。',categoryName='管道维修',contactPhone='测试数据',status='ASSIGNED',priority='NORMAL',assigneeId=8,assigneeName='合成服务人员',conversationId=103,createdAt=DATE)
  elif re.fullmatch(r'/work-orders/8/(timeline|processes|attachments|assignments)',path):result=[]
  elif path=='/feedbacks':result=dict(records=[self.feedback()],total=1,page=1,size=10,pages=1)
  elif path=='/feedbacks/101/attachments':result=[dict(id=11,feedbackId=101,fileName='历史附件.txt',fileUrl='/synthetic-attachment',fileSize=10,fileType='DOCUMENT',createdAt=DATE)]
  elif path=='/leases/42/payments':result=[dict(paymentNo='SYNTHPAY0001',leaseId=42,paymentType='FIRST',months=1,amount=6000,channel='ALIPAY',status='SUCCESS',createdAt=DATE,paidAt=DATE,periodStart='2026-10-18',periodEnd='2026-11-17')]
  elif path=='/leases/42/deposit-refunds':
   if self.fail_fees:self.fail_fees=False;await route.fulfill(status=503,json=dict(code=503,message='合成费用读取失败',data=None));self.calls.append((request.method,'/api/v1'+path));return
   result=[]
  elif path=='/leases/42/terms':result=[dict(id=1,paidMonths=1,remainingMonths=11,nextDueDate='2026-11-18',overdue=False,paidPeriods=[],startDate='2026-10-18',endDate='2027-10-17',contractMonths=12,monthlyRent=3000,status='ACTIVE',termType='INITIAL',contentSnapshot='合成合同期限')]
  elif path=='/leases/42/extensions':result=[]
  elif path=='/leases/42/changes':result=[dict(id=1,changeType='CREATE',operatorType='SYSTEM',operatorName='系统',createdAt=DATE,newValue='合成租约创建')]
  else:matched=False
  if matched:self.calls.append((request.method,'/api/v1'+path));await route.fulfill(json=dict(code=200,message='success',data=result,timestamp=DATE))
  else:await super().handle(route)

async def main():
 errors=[]
 async with async_playwright() as p:
  browser=await p.chromium.launch(headless=True)
  for role,base in [('ADMIN','admin'),('RESIDENT','resident'),('STAFF','staff')]:
   context=await browser.new_context(viewport={'width':1440,'height':1000});data=Details(role)
   await context.route('**/*',data.handle)
   await context.add_init_script("sessionStorage.setItem('crsp_session',JSON.stringify("+json.dumps(dict(token='synthetic-ui-only',expiresAt=4102444800000,user=dict(id=8,username='synthetic',realName='合成测试',role=role,boundCommunities=[2])))+"))")
   page=await context.new_page();page.on('pageerror',lambda error:errors.append(str(error)))
   if role=='ADMIN':
    await page.goto(BASE+'/admin/leases/42');await page.get_by_role('button',name='费用记录',exact=True).wait_for();await page.wait_for_timeout(200)
    check('lease default overview only',not any(path.endswith(('/changes','/viewings','/payments','/deposit-refunds')) for _,path in data.calls))
    check('lease no embedded chat',await page.locator('.conversation-chat').count()==0)
    await page.screenshot(path=str(OUT/'lease-overview-1440.png'),full_page=True)
    await page.get_by_role('button',name='费用记录',exact=True).click();await page.get_by_text('押金退还记录读取失败',exact=False).wait_for()
    check('fee failure isolated and payment snapshot retained',await page.get_by_text('SYNTHPAY0001',exact=False).count()==1)
    await page.get_by_role('button',name='重试费用记录',exact=True).click();await page.get_by_text('尚无押金退还流水',exact=True).wait_for()
    check('fee retry recovers without channel query',await page.get_by_text('押金退还记录读取失败',exact=False).count()==0)
    await page.get_by_role('button',name='业务记录',exact=True).click();await page.get_by_text('合成租约创建',exact=True).wait_for()
    check('business history retained',await page.get_by_text('变更历史',exact=False).count()>=1)
    await page.get_by_role('button',name='合同与协议',exact=True).click();await page.get_by_text('查看该版本快照',exact=True).click();await page.get_by_text('合成合同期限',exact=False).wait_for()
    check('agreement and term panels retained',await page.get_by_text('合成协议',exact=True).count()>=1)
    await page.screenshot(path=str(OUT/'lease-contract-1440.png'),full_page=True)
    await page.locator('.lease-summary-actions .conversation-entry a').click();await page.get_by_role('log').wait_for()
    check('lease exact original group','id=101' in page.url)
    await page.get_by_role('link',name='返回业务详情',exact=True).click();await page.get_by_role('button',name='概览',exact=True).wait_for()
    check('lease return context retained',urlparse(page.url).path=='/admin/leases/42' and 'section=contract' in page.url)
   await page.goto(f'{BASE}/{base}/work-orders/8');await page.get_by_text('合成维修工单',exact=True).wait_for()
   check(role+' workorder has no embedded chat',await page.locator('.conversation-chat').count()==0)
   if role=='ADMIN':await page.get_by_role('button',name='进入工单沟通',exact=True).click()
   else:await page.locator('.conversation-entry a').click()
   await page.get_by_role('log').wait_for();check(role+' workorder exact group','id=103' in page.url)
   await page.get_by_role('link',name='返回业务详情',exact=True).click();await page.get_by_text('合成维修工单',exact=True).wait_for()
   check(role+' workorder return path retained',urlparse(page.url).path==f'/{base}/work-orders/8')
   if role!='STAFF':
    path='/admin/feedbacks?id=101' if role=='ADMIN' else '/resident/feedbacks/101'
    writes=len([x for x in data.calls if x[0]=='POST'])
    await page.goto(BASE+path);await page.get_by_text('这里是原始反馈，不得丢失。',exact=True).wait_for()
    check(role+' feedback original and historical attachment retained',await page.get_by_text('历史附件.txt',exact=True).count()>=1)
    check(role+' detail read does not accept feedback',len([x for x in data.calls if x[0]=='POST'])==writes)
    check(role+' feedback no embedded chat',await page.locator('.feedback-chat').count()==0)
    await page.locator('.conversation-entry a').click();await page.get_by_role('log').wait_for()
    check(role+' feedback exact source','id=101' in page.url and 'source=FEEDBACK' in page.url)
    await page.get_by_role('link',name='返回业务详情',exact=True).click();await page.get_by_text('这里是原始反馈，不得丢失。',exact=True).wait_for()
   check(role+' no financial commands',not any(method!='GET' and any(x in path for x in ('payment','refund')) for method,path in data.calls))
   await context.close()
  check('detail paths no JavaScript errors',not errors);await browser.close()
 (OUT/'detail-browser-summary.json').write_text(json.dumps(dict(scope='isolated frontend, synthetic HTTP; not backend E2E',checks=checks,errors=errors,transactions='none'),ensure_ascii=False,indent=2),encoding='utf-8')
if __name__=='__main__':asyncio.run(main())
