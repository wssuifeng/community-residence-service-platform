from support import *
from playwright.sync_api import sync_playwright
from datetime import datetime
import base64,io
from PIL import Image
s=load_sessions();sa=s['superadmin']['token'];checks=[];blocked=[]
def check(name,ok,actual=None):
 checks.append({'name':name,'passed':bool(ok),'actual':actual});save('browser-partial.json',{'checks':checks})
 if not ok:save('browser-partial.json',{'checks':checks});raise AssertionError(name+' '+str(actual))
# The document component is mounted unchanged in an isolated Vite test page.
harness=ROOT/'frontend/dc-closeout.html'
harness.write_text("""<!doctype html><html lang="zh-CN"><meta charset="utf-8"><div id="app"></div><script type="module">
import {createApp,h,ref} from '/node_modules/.vite/deps/vue.js';
import ElementPlus from '/node_modules/.vite/deps/element-plus.js';
import '/node_modules/element-plus/dist/index.css';
import FileUploader from '/src/common/upload/components/FileUploader.vue';
createApp({setup(){const files=ref([]);return()=>h('main',[h('h1','隔离文件上传验证'),h(FileUploader,{modelValue:files.value,'onUpdate:modelValue':v=>files.value=v}),h('textarea',{placeholder:'独立表单字段'}),h('pre',{id:'file-result'},JSON.stringify(files.value))])}}).use(ElementPlus).mount('#app');</script></html>""",'utf-8')
try:
 with sync_playwright() as pw:
  browser=pw.chromium.launch(channel='msedge',headless=True);contexts=[]
  def page_for(name):
   c=browser.new_context(viewport={'width':1440,'height':1000});contexts.append(c)
   auth=s[name];session={'token':auth['token'],'user':auth['user'],'expiresAt':int(time.time()*1000)+auth['expiresIn']*1000}
   c.add_init_script('sessionStorage.setItem("crsp_session",'+json.dumps(json.dumps(session))+')')
   def guard(route):
    url=route.request.url
    if any(x in url for x in ['/api/v1/payment','/deposit-refunds','/rent-payments','/first-payments','/renew-payments']):blocked.append(url.split('?')[0]);route.abort();return
    if url.startswith(('http://127.0.0.1:5181/','http://127.0.0.1:8087/','blob:','data:')):route.continue_()
    else:route.abort()
   c.route('**/*',guard);return c.new_page(),c
  if os.environ.get('DC_CHANNEL_ONLY'):
   checks.extend(x for x in json.loads((OUT/'browser-partial.json').read_text('utf-8'))['checks'] if x['name'].startswith('SP-025'))
  else:
   page,context=page_for('resident1');page.goto(WEB+'/resident/work-orders/create');page.locator('input[type=file]').wait_for(state='attached');page.get_by_placeholder('一句话描述问题').fill('dc上传异常仍可编辑')
   image=io.BytesIO();Image.new('RGB',(2,2),(255,0,0)).save(image,format='JPEG');jpg=image.getvalue()
   inp=page.locator('input[type=file]');requests_seen=[]
   page.on('request',lambda req:requests_seen.append(req.url) if '/api/v1/upload' in req.url else None)
   def choose(name,buf,mime):inp.set_input_files({'name':name,'mimeType':mime,'buffer':buf});page.wait_for_timeout(700)
   choose('dc-valid.jpg',jpg,'image/jpeg');page.locator('.image-item img').wait_for();page.wait_for_function("document.querySelector('.image-item img').naturalWidth>0")
   check('SP-025合法JPG真实上传并回显',page.locator('.image-item').count()==1)
   choose('dc-invalid.webp',b'RIFFbadWEBP','image/webp');check('SP-025WEBP拒绝且明确提示',page.locator('.upload-failures').is_visible() and 'dc-invalid.webp' in page.locator('.upload-failures').inner_text())
   before=len(requests_seen);choose('dc-large.jpg',b'0'*(6*1024*1024),'image/jpeg');check('SP-025六MB图片前端拒绝且未发送上传',len(requests_seen)==before and '5MB' in page.locator('.upload-failures').inner_text())
   context.route('**/api/v1/upload',lambda route:route.abort('internetdisconnected'))
   choose('dc-network.jpg',jpg,'image/jpeg');check('SP-025断网明确提示并保留成功图片',page.locator('.upload-failures').is_visible() and page.locator('.image-item').count()==1)
   page.get_by_placeholder('一句话描述问题').fill('断网后标题仍可编辑');check('SP-025失败不阻塞其它字段',page.get_by_placeholder('一句话描述问题').input_value()=='断网后标题仍可编辑')
   context.unroute('**/api/v1/upload');choose('dc-retry.jpg',jpg,'image/jpeg');check('SP-025网络恢复可重试成功',page.locator('.image-item').count()==2)
   page.screenshot(path=str(OUT/'upload-image.png'))
   doc,dc=page_for('superadmin');doc.goto(WEB+'/dc-closeout.html');doc.locator('input[type=file]').wait_for(state='attached')
   doc.locator('input[type=file]').set_input_files({'name':'dc-valid.pdf','mimeType':'application/pdf','buffer':b'%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF'})
   doc.wait_for_function("document.querySelector('#file-result').textContent.includes('.pdf')")
   check('SP-025PDF真实上传成功',len(json.loads(doc.locator('#file-result').inner_text()))==1)
   doc.locator('input[type=file]').set_input_files({'name':'dc-invalid.exe','mimeType':'application/octet-stream','buffer':b'MZinvalid'})
   doc.locator('.upload-failures').wait_for();check('SP-025EXE拒绝且保留成功文档','dc-invalid.exe' in doc.locator('.upload-failures').inner_text() and len(json.loads(doc.locator('#file-result').inner_text()))==1)
   doc.get_by_placeholder('独立表单字段').fill('失败后仍可填写');check('SP-025文件失败不阻塞其它字段',doc.get_by_placeholder('独立表单字段').input_value()=='失败后仍可填写')
   doc.screenshot(path=str(OUT/'upload-document.png'))
  # UI channel configuration and actual UI publication, followed by SQL assertions.
  config,cc=page_for('superadmin');config.goto(WEB+'/admin/system?tab=configs');config.locator('.channel-row').first.wait_for()
  notice_row=config.locator('.channel-row').filter(has_text='NOTICE');check('C11-014公告配置行唯一',notice_row.count()==1)
  admin,ac=page_for('admin1')
  for enabled in [False,True]:
   for channel in ['EMAIL','SMS']:
    checkbox=notice_row.get_by_role('checkbox',name=channel,exact=True)
    if checkbox.is_checked()!=enabled:notice_row.locator('label').filter(has_text=channel).click()
   with config.expect_response(lambda res:res.request.method=='PUT' and '/notifications/channel-levels' in res.url) as saved:config.locator('.channel-panel').get_by_role('button',name='保存配置',exact=True).click()
   check('C11-014配置保存'+str(enabled),saved.value.json().get('code')==200)
   admin.goto(WEB+'/admin/notices');admin.get_by_role('button',name='发布公告',exact=True).click();admin.locator('.ProseMirror').wait_for()
   title='dc渠道'+('勾选' if enabled else '未勾选')+datetime.now().strftime('%H%M%S')
   admin.get_by_placeholder('请输入公告标题',exact=True).fill(title)
   admin.locator('.ProseMirror').fill('仅在独立环境验证渠道留痕。')
   with admin.expect_response(lambda res:res.request.method=='PATCH' and '/publish' in res.url) as published:admin.get_by_role('button',name='保存并发布',exact=True).click()
   check('C11-014界面发布成功'+str(enabled),published.value.json().get('code')==200)
   nid=int(sql("SELECT id FROM notice WHERE title='"+title+"' ORDER BY id DESC LIMIT 1"))
   total=int(sql("SELECT COUNT(*) FROM notification WHERE source_type='NOTICE' AND source_id="+str(nid)))
   channels=sql('SELECT channel,COUNT(*) FROM notification_channel_log l JOIN notification n ON l.notification_id=n.id WHERE n.source_type=\'NOTICE\' AND n.source_id='+str(nid)+' GROUP BY channel')
   check('C11-014站内必达'+str(enabled),total>0,{'notifications':total,'channels':channels})
   check('C11-014站外勾选两态'+str(enabled),('SMS' in channels and 'EMAIL' in channels) if enabled else ('SMS' not in channels and 'EMAIL' not in channels),channels)
  config.locator('.channel-panel').screenshot(path=str(OUT/'channel-config.png'))
  for c in contexts:c.close()
  browser.close()
finally:
 if harness.exists():harness.unlink()
save('browser.json',{'checks':checks,'passed':sum(x['passed'] for x in checks),'failed':[x for x in checks if not x['passed']],'browser':'Microsoft Edge headless, independent contexts','paymentRequestsBlocked':len(blocked),'paymentRequestsSent':0,'normalBrowserTouched':False})
print('browser',len(checks),'checks passed')
