"""Independent Chromium + synthetic HTTP fixtures. Never connects a user browser or business backend."""
import asyncio, json, re
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.async_api import async_playwright
OUT=Path(__file__).parent
BASE="http://127.0.0.1:5184"
DATE="2026-10-17T11:00:00"
checks=[]
def check(name, value):
    checks.append({"name":name,"passed":bool(value)})
    if not value: raise AssertionError(name)

class Synthetic:
    def __init__(self,role):
        self.role=role; self.calls=[];self.read={};self.fail=False
        self.types={101:"LEASE_GROUP",102:"VIEWING_GROUP",103:"WORK_ORDER_GROUP",104:"DIRECT"}
        self.messages={i:[dict(id=i*100+n,conversationId=i,senderId=9,senderRole="RESIDENT",senderName="演示居民",content=f"合成沟通记录 {n}",messageType="TEXT",createdAt=DATE) for n in range(1,86)] for i in self.types}
        self.messages[101][-1].update(senderId=0,senderRole="SYSTEM",senderName="系统",content="租赁协议已发出",messageType="LEASE_AGREEMENT",agreementId=71)
        self.fm=[dict(id=1,feedbackId=101,senderId=None,senderType="RESIDENT",senderName="匿名居民",content="反馈图片沟通",messageType="TEXT",createdAt=DATE)]
    def detail(self,i):
        return dict(id=i,type=self.types[i],title={101:"租约沟通 · 5-1-1-101",102:"看房沟通 · 朝南两居",103:"工单沟通 · 水管维修",104:"已结束的直通会话"}[i],status="CLOSED" if i==104 else "ACTIVE",relatedId=42 if i==101 else 8,communityId=2,createdAt=DATE,participants=[dict(userId=9,ownerType="RESIDENT",name="演示居民",role="RESIDENT"),dict(userId=8,ownerType="USER",name="演示管理方",role="ADMIN")])
    def feedback(self):
        return dict(id=101,communityId=2,residentId=None,residentName="匿名居民",isAnonymous=True,title="匿名建议 · 社区设施",content="这里是原始反馈，不得丢失。",status="PENDING",handlerId=None,handlerName=None,createdAt=DATE)
    async def handle(self,route):
        request=route.request;u=urlparse(request.url);path=u.path;q=parse_qs(u.query)
        if path.startswith("/ws"):
            await route.fulfill(status=403,body="isolated");return
        if not path.startswith("/api/"):
            await route.continue_();return
        self.calls.append((request.method,path))
        assert not re.search(r"/(query|pay|refund|first-payment|renew)$",path), "financial command forbidden"
        path=path.removeprefix("/api/v1")
        result=None
        if path=="/conversation-inbox":
            if self.fail:
                self.fail=False;await route.fulfill(status=503,json=dict(code=503,message="合成离线",data=None));return
            rows=[]
            for i in self.types:
                if self.role=="STAFF" and i not in (102,103):continue
                c=self.detail(i);rows.append(dict(c,source="CONVERSATION",communityName="合成社区",houseCode="5-1-1-101",residentLabel=None,lastMessage=self.messages[i][-1]["content"],activityAt=DATE,unreadCount=0 if ("CONVERSATION",i) in self.read else 3))
            if self.role!="STAFF":rows.append(dict(self.feedback(),source="FEEDBACK",type="FEEDBACK",communityName="合成社区",houseCode=None,residentLabel="匿名居民",relatedId=101,lastMessage="反馈图片沟通",activityAt=DATE,unreadCount=0 if ("FEEDBACK",101) in self.read else 2))
            kind=q.get("type",[None])[0];keyword=q.get("keyword",[""])[0]
            rows=[r for r in rows if (not kind or r["type"]==kind) and (not keyword or keyword in r["title"]+str(r["houseCode"])) and (q.get("unreadOnly",["false"])[0]!="true" or r["unreadCount"])]
            result=dict(records=rows,total=len(rows),page=1,size=30,pages=1)
        elif match:=re.fullmatch(r"/conversation-inbox/(CONVERSATION|FEEDBACK)/(\d+)/messages",path):
            source,i=match[1],int(match[2]);rows=self.messages[i] if source=="CONVERSATION" else self.fm
            before=int(q.get("beforeId",["0"])[0]);after=int(q.get("afterId",["0"])[0])
            rows=[m for m in rows if (not before or m["id"]<before) and (not after or m["id"]>after)]
            result=rows[:40] if after else rows[-40:]
        elif match:=re.fullmatch(r"/(conversations|conversation-inbox/FEEDBACK)/(\d+)/read",path):
            source="FEEDBACK" if "FEEDBACK" in path else "CONVERSATION";self.read[(source,int(match[2]))]=request.post_data_json["lastMessageId"]
        elif match:=re.fullmatch(r"/conversations/(\d+)",path):result=self.detail(int(match[1]))
        elif path=="/feedbacks/101":result=self.feedback()
        elif match:=re.fullmatch(r"/(conversations|feedbacks)/(\d+)/messages",path):
            source,i=match[1],int(match[2]);rows=self.messages[i] if source=="conversations" else self.fm
            message=dict(id=rows[-1]["id"]+1,content=request.post_data_json["content"],createdAt=DATE,messageType="TEXT",senderId=8,senderName="我")
            if source=="conversations":message.update(conversationId=i,senderRole=self.role)
            else:message.update(feedbackId=i,senderType="RESIDENT" if self.role=="RESIDENT" else "ADMIN")
            rows.append(message);result=message
        elif path=="/leases/42":result=dict(id=42,tenantId=9,communityId=2,houseId=1,houseLocation="合成社区 1栋1单元101",startDate="2026-10-18",endDate="2027-10-17",monthlyRent=3000,deposit=3000,status="TO_SIGN",agreementStatus="PARTIAL",contractMonths=12,firstPaymentMonths=1,paymentCycleMonths=1,createdAt=DATE,conversationId=101)
        elif path=="/lease-agreements":result=[dict(id=71,leaseId=42,communityId=2,title="合成协议",content="合成协议正文，仅验证查看。",status="PARTIAL",adminConfirmName="演示管理方",adminConfirmTime=DATE,createdAt=DATE)]
        elif path=="/agreement-templates/available":result=[]
        elif "notifications" in path:result=dict(notifications=[],latestSeq=0) if path.endswith("/unread") or path.endswith("/pull") else dict(records=[],total=0,page=1,size=10,pages=0)
        elif "todo" in path:result={}
        elif path.endswith("/changes") or path.endswith("/viewings") or path.endswith("/deposit-refunds") or path.endswith("/payments"):result=[]
        else: result=dict(records=[],total=0,page=1,size=30,pages=0)
        await route.fulfill(json=dict(code=200,message="success",data=result,timestamp=DATE))

async def main():
    errors=[]
    async with async_playwright() as p:
        browser=await p.chromium.launch(headless=True)
        for role,base in [("ADMIN","admin"),("RESIDENT","resident"),("STAFF","staff")]:
            context=await browser.new_context(viewport={"width":1440,"height":1000})
            synthetic=Synthetic(role)
            await context.route("**/*",synthetic.handle)
            await context.add_init_script("sessionStorage.setItem('crsp_session',JSON.stringify("+json.dumps(dict(token="synthetic-ui-only",expiresAt=4102444800000,user=dict(id=8,username="synthetic",realName="合成测试",role=role,boundCommunities=[2])))+"))")
            page=await context.new_page();page.on("pageerror",lambda e:errors.append(str(e)))
            cid=103
            await page.goto(f"{BASE}/{base}/conversations?id={cid}")
            await page.get_by_role("log",name="消息记录").wait_for()
            await page.get_by_role("textbox",name="消息内容").wait_for()
            await page.wait_for_timeout(350)
            check(role+" reads visible messages",("CONVERSATION",cid) in synthetic.read)
            check(role+" initial history bounded",await page.locator(".chat-message").count()==40)
            await page.screenshot(path=str(OUT/f"{base}-1440.png"),full_page=True)
            check(role+" no horizontal overflow",await page.evaluate("document.documentElement.scrollWidth<=innerWidth+1"))
            await page.get_by_role("textbox",name="消息内容").fill("切换后应保留的草稿")
            await page.locator(".inbox-row").filter(has_text="看房沟通").click()
            await page.get_by_role("textbox",name="消息内容").wait_for()
            check(role+" other conversation draft isolated",await page.get_by_role("textbox",name="消息内容").input_value()=="")
            await page.locator(".inbox-row").filter(has_text="工单沟通").click()
            await page.wait_for_timeout(200)
            check(role+" draft restored",await page.get_by_role("textbox",name="消息内容").input_value()=="切换后应保留的草稿")
            await page.get_by_role("button",name="发送",exact=True).click()
            await page.get_by_role("log").get_by_text("切换后应保留的草稿",exact=True).wait_for()
            check(role+" sent message visible",await page.get_by_role("textbox",name="消息内容").input_value()=="")
            before=await page.locator(".chat-message").count()
            await page.get_by_role("button",name="加载更早消息",exact=True).click()
            await page.wait_for_timeout(150)
            check(role+" history prepended",await page.locator(".chat-message").count()>before)
            if role!="STAFF":
                await page.locator(".inbox-row").filter(has_text="匿名建议").click()
                await page.get_by_text("这里是原始反馈，不得丢失。",exact=True).wait_for()
                check(role+" feedback same-id source distinct","source=FEEDBACK" in page.url)
                check(role+" anonymous safe","不可泄露实名" not in await page.content())
                await page.get_by_role("button",name="会话菜单",exact=True).click()
                await page.get_by_role("button",name="查看成员",exact=True).click()
                await page.get_by_role("dialog").get_by_text("匿名居民",exact=True).wait_for()
                check(role+" member safe label",await page.get_by_role("dialog").get_by_text("匿名居民",exact=True).count()==1)
                await page.get_by_role("dialog").locator(".el-dialog__headerbtn").click()
                await page.locator(".inbox-row").filter(has_text="已结束的直通会话").click()
                await page.get_by_text("会话已关闭，历史消息只读留档",exact=True).wait_for()
                check(role+" closed hides composer",await page.get_by_role("textbox",name="消息内容").count()==0)
                await page.locator(".inbox-row").filter(has_text="租约沟通").click()
                await page.get_by_role("button",name="会话业务功能").click()
                check(role+" protocol action retained",await page.get_by_role("button",name="查看 / 同意协议" if role=="RESIDENT" else "发送 / 查看协议",exact=True).count()==1)
                await page.get_by_role("button",name="查看 / 同意协议" if role=="RESIDENT" else "发送 / 查看协议",exact=True).click()
                await page.get_by_role("dialog").get_by_text("合成协议",exact=True).wait_for()
                await page.get_by_role("dialog").locator(".el-dialog__headerbtn").first.click()
            else:check("staff no feedback category",await page.locator(".source-nav").get_by_text("反馈",exact=True).count()==0)
            for width in [1920,1366,1024,768,390]:
                await page.set_viewport_size({"width":width,"height":900});await page.wait_for_timeout(100)
                check(role+f" width {width} no horizontal overflow",await page.evaluate("document.documentElement.scrollWidth<=innerWidth+1"))
                if width>=768:
                    check(role+f" width {width} chat visible",await page.locator(".conversation-pane").is_visible())
                    check(role+f" width {width} chat fits",await page.locator(".conversation-pane").evaluate("el=>el.getBoundingClientRect().right<=innerWidth+1"))
                else:check(role+" desktop minimum width guard",await page.get_by_text("本系统面向桌面端使用，请使用 768px 以上宽度的设备访问。",exact=False).is_visible())
                if width in (1920,1024,768):await page.screenshot(path=str(OUT/f"{base}-{width}.png"),full_page=True)
            await context.close()
        check("no financial command",True)
        check("no JavaScript errors",not errors)
        await browser.close()
    (OUT/"frontend-browser-summary.json").write_text(json.dumps(dict(scope="isolated frontend with synthetic HTTP fixtures, not backend E2E",checks=checks,errors=errors,normalServices="untouched",transactions="none"),ensure_ascii=False,indent=2),encoding="utf-8")
if __name__=="__main__":asyncio.run(main())
