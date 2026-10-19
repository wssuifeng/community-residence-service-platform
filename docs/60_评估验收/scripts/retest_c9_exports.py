"""G-002/G-004 browser retest against isolated backend; actual PNG downloads."""
import datetime as dt
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageStat
from playwright.sync_api import sync_playwright

root=Path.cwd(); out=root/"docs/60_评估验收/evidence/acceptance-c9-fix-20261020"; out.mkdir(exist_ok=True)
errors=[]; requests=[]; downloads=[]; observations=[]
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,channel="msedge")
    version=browser.version
    context=browser.new_context(viewport={"width":1440,"height":1100},accept_downloads=True)
    page=context.new_page();page.on("pageerror",lambda e:errors.append(str(e)))
    page.on("request",lambda r: requests.append({"method":r.method,"path":r.url.split("127.0.0.1:5181")[-1]}) if "/api/v1/" in r.url else None)
    page.goto("http://127.0.0.1:5181/auth/login");page.wait_for_load_state("networkidle")
    page.get_by_role("tab",name="管理员 / 服务人员").click()
    page.get_by_placeholder("请输入用户名或注册手机号").fill("superadmin")
    page.get_by_placeholder("请输入密码",exact=True).fill("Admin@123456")
    page.get_by_role("button",name="登 录").click();page.wait_for_url("**/admin/**");page.wait_for_load_state("networkidle")
    for view,tab in [("overview",None),("workorder","工单"),("resident","居民"),("resource","资源预约")]:
        if view=="overview":
            page.goto("http://127.0.0.1:5181/admin/dashboard?tab=overview")
        elif view=="workorder":
            page.goto("http://127.0.0.1:5181/admin/dashboard?tab=details")
        else:
            page.get_by_role("tab",name=tab,exact=True).click()
        page.wait_for_load_state("networkidle")
        page.locator(".el-loading-mask").first.wait_for(state="hidden")
        page.wait_for_timeout(600)
        text=page.locator("body").inner_text();assert "较上月" not in text
        buttons=page.get_by_role("button",name="导出图片",exact=False)
        count=buttons.count();assert count>=1,(view,text)
        for i in range(count):
            button=buttons.nth(i);label=button.get_attribute("aria-label")
            with page.expect_download() as pending:
                button.click()
            download=pending.value; path=out/f"{view}-{i+1}.png";download.save_as(path)
            with Image.open(path) as image:
                assert image.format=="PNG" and image.width>100 and image.height>100
                assert max(ImageStat.Stat(image.convert("RGB")).stddev)>5
                size=list(image.size)
            downloads.append({"view":view,"label":label,"file":path.name,"suggestedFilename":download.suggested_filename,"size":size,"bytes":path.stat().st_size,"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
        page.screenshot(path=str(out/f"{view}-page.png"),full_page=True)
        observations.append({"view":view,"noFixedMonthComparison":True,"pngDownloads":count})
    context.close();browser.close()
assert not errors,errors
assert not any(any(segment in r["path"] for segment in ["/payment", "/refund", "/channels"]) for r in requests)
assert all(r["method"]=="GET" or "/auth/" in r["path"] for r in requests), requests
sources=["frontend/src/common/statistics/components/EChart.vue","frontend/src/admin/statistics/views/DashboardOverview.vue","frontend/src/admin/statistics/views/StatisticsView.vue"]
result={"recordedAt":dt.datetime.now().isoformat(),"browser":{"family":"Microsoft Edge","version":version},"backendJarSha256":json.loads((root/"output/runtime/acceptance-flow-20261020/state.json").read_text(encoding="utf-8"))["jarSha256"],"frontendSources":{path:hashlib.sha256((root/path).read_bytes()).hexdigest() for path in sources},"normalDatabaseWrites":0,"businessWrites":0,"financialOperations":0,"requests":requests,"observations":observations,"downloads":downloads,"pageErrors":errors,"limitations":["G-002 and fixed placeholder display G-004 only; G-001 time filter and G-005 missing indicators remain open.","R41 full numeric acceptance remains subject to the final filtered SQL/detail comparison."]}
(out/"verification.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({"views":len(observations),"downloads":len(downloads),"pageErrors":len(errors),"financialOperations":0}))
