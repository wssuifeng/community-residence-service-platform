/* 真实生产SFC＋生产SDK表单的本地截获对照；所有业务API/网关请求本地桩返回，不接管用户浏览器。 */
const fs=require('node:fs'),assert=require('node:assert/strict');
const {chromium}=require('C:/Users/17841/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fixture='frontend/cb-offline-payment-probe.html',dir='docs/50_系统测试/evidence/cb-payment-production-differential-20261016';
const cases=JSON.parse(fs.readFileSync('output/runtime/cb-production-forms/cases-private.json','utf8'));
const origin='http://127.0.0.1:5181';
(async()=>{
 assert(!fs.existsSync(fixture),'Refuse to replace an existing probe page');
 fs.mkdirSync(dir,{recursive:true});
 fs.writeFileSync(fixture,`<html><head><meta charset="UTF-8"></head><body><div id="app"></div><script type="module">
 import {createApp,h,ref,onMounted} from 'vue';
 import ElementPlus from 'element-plus';import 'element-plus/dist/index.css';
 import First from '/src/views/resident/LeaseFirstPaymentDialog.vue';
 import Rent from '/src/views/resident/LeaseRenewDialog.vue';
 const purpose=new URLSearchParams(location.search).get('purpose');
 const lease={id:10,tenantId:1,tenantName:'本地差异夹具',communityId:1,houseId:1,houseLocation:'本地对照',startDate:'2026-10-15',endDate:'2027-10-14',monthlyRent:3600,deposit:10600,status:purpose==='FIRST'?'TO_SIGN':'ACTIVE',agreementStatus:'SIGNED'};
 createApp({setup(){const visible=ref(false);onMounted(()=>visible.value=true);return()=>h(purpose==='FIRST'?First:Rent,{lease,modelValue:visible.value,purpose:purpose==='FIRST'?undefined:'RENT','onUpdate:modelValue':v=>visible.value=v})}}).use(ElementPlus).mount('#app');
 </script></body></html>`);
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const results=[],errors=[];let externalRequests=0,normalApiForwarded=0;
 try {for(const c of cases){
  const context=await browser.newContext();let captured,resolve;const capture=new Promise(r=>resolve=r);
  const form=fs.readFileSync('output/runtime/cb-production-forms/case-'+c.index+'.html','utf8');
  const order={paymentNo:c.outTradeNo,leaseId:10,channel:'ALIPAY',paymentType:c.purpose,months:c.months,amount:c.amount,status:'PENDING',periodStart:'2026-11-15',periodEnd:'2026-12-14',createdAt:'2026-10-15T20:37:20',alipayForm:form};
  await context.route('**/*',async route=>{
   const req=route.request(),u=new URL(req.url());
   if(u.hostname==='127.0.0.1'&&u.port==='9'&&u.pathname==='/gateway.do'){
    captured={query:new URLSearchParams(u.search),post:new URLSearchParams(req.postData()||''),method:req.method()};
    await route.fulfill({status:200,contentType:'text/html',body:'<p>Local SDK form intercepted; not Alipay</p>'});resolve();return;
   }
   if(u.origin!==origin){externalRequests++;return route.abort();}
   if(u.pathname.startsWith('/api/')){
    let data;
    if(u.pathname.endsWith('/payment/channels'))data=[{channel:'ALIPAY',configured:true,enabled:true},{channel:'WECHAT',configured:false,enabled:false}];
    else if(u.pathname.endsWith('/rent-quote'))data={...order,status:undefined,quoteVersion:'local-only',remainingMonths:11,alipayForm:null};
    else if(/\/leases\/10\/payments$/.test(u.pathname))data=[];
    else if(/\/(first|rent)-payments$/.test(u.pathname)&&req.method()==='POST')data=order;
    else if(u.pathname.includes('/payment/orders/'))data={...order,alipayForm:null};
    else throw new Error('Unexpected API blocked: '+u.pathname);
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,message:'local fixture',data})});return;
   }
   return route.continue();
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto(origin+'/cb-offline-payment-probe.html?purpose='+c.purpose);await page.waitForLoadState('networkidle');
  const submit=page.getByRole('button',{name:'去支付',exact:true});
  if(await submit.count()===0){const buttons=await page.getByRole('button').allTextContents();throw new Error('Submit selector absent '+c.purpose+': '+buttons.join('|'));}
  await submit.click();await Promise.race([capture,new Promise((_,reject)=>setTimeout(()=>reject(new Error('No local payment form captured '+c.purpose)),10000))]);
  assert.equal(captured.method,'POST');const biz=JSON.parse(captured.post.get('biz_content')||'null');
  assert.equal(captured.query.get('method'),'alipay.trade.page.pay');assert.equal(captured.query.get('charset'),'UTF-8');assert.equal(captured.query.get('sign_type'),'RSA2');assert.equal(captured.query.get('app_id'),'9021000000000000');assert.match(captured.query.get('timestamp'),/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);assert(captured.query.get('sign'));
  assert.equal(biz.out_trade_no,c.outTradeNo);assert.equal(biz.subject,c.subject);assert.equal(Number(biz.total_amount),Number(c.amount));assert.equal(biz.product_code,'FAST_INSTANT_TRADE_PAY');assert.equal(biz.time_expire,c.timeExpire);
  results.push({case:c.index,purpose:c.purpose,amount:biz.total_amount,subject:biz.subject,timeExpire:biz.time_expire,productionComponent:c.purpose==='FIRST'?'LeaseFirstPaymentDialog':'LeaseRenewDialog',productionServiceSubject:true,productionChannelClient:true,method:'POST',allRequiredFieldsPreserved:true,timestampPreserved:true,apiCallsLocallyMocked:true});
  await context.close();
 }}finally{await browser.close();fs.unlinkSync(fixture);}
 assert.equal(externalRequests,0);assert.equal(errors.length,0);
 fs.writeFileSync(dir+'/production-component-transport.json',JSON.stringify({checkedAt:new Date().toISOString(),sdk:'4.40.1006.ALL',cases:results,passed:results.length,scriptErrors:errors,externalRequests,normalApiForwarded,realCredentialsLoaded:false,userBrowserOperated:false,remoteInvalidParameterReproduced:false,conclusion:'实际4笔订单业务形状经生产标题/生产SDK/生产首期或租金组件提交后必需字段完整；不是远端失败请求捕获，未证明收银台错误修复'},null,2));
 console.log(JSON.stringify({productionFormAndComponentCases:results.length,externalRequests,normalApiForwarded,remoteInvalidParameterReproduced:false}));
})().catch(e=>{console.error(e.message);process.exitCode=1});
