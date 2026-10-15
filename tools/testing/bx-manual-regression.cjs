const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto'),{spawnSync}=require('node:child_process');
const root=process.cwd(),db='community_residence_bx_20261015',base='http://127.0.0.1:8087',front='http://127.0.0.1:5181',run=String(Date.now()),checks=[];
assert(db.startsWith('community_residence_bx_')); const env={...process.env};
for(const line of fs.readFileSync('.env','utf8').split(/\r?\n/)){const m=line.match(/^\s*(DB_PASSWORD|DB_USERNAME)\s*=\s*(.*?)\s*$/);if(m)env[m[1]]=m[2].replace(/^(["'])(.*)\1$/,'$2')}
function sql(q){const p=spawnSync('C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe',['-h127.0.0.1','-u'+(env.DB_USERNAME||'root'),'-D'+db,'-N','-B','--default-character-set=utf8mb4','-e',q],{env:{...env,MYSQL_PWD:env.DB_PASSWORD},windowsHide:true,encoding:'utf8'});assert.equal(p.status,0,'Isolated SQL failed; credentials withheld');return p.stdout.trim()}
function check(name,passed){assert(passed,name);checks.push({name,passed:true})}
let spec;
function contract(p,method){const pathname=new URL(base+p).pathname;const entry=Object.entries(spec.paths).find(([pattern])=>new RegExp('^'+pattern.replace(/\{[^}]+\}/g,'[^/]+')+'$').test(pathname));assert(entry?.[1]?.[method.toLowerCase()],method+' '+pathname+' not in running OpenAPI')}
async function request(p,method='GET',body,token,allowError=false){
 assert(!/first-payments|rent-payments|renew-payments|deposit-refunds|\/payments(?:\/|\?|$)/.test(p),'Trading endpoints forbidden');contract(p,method);
 const headers=token?{Authorization:'Bearer '+token}:{};const data=body===undefined?undefined:body instanceof FormData?body:JSON.stringify(body);if(data&&!(body instanceof FormData))headers['Content-Type']='application/json';
 const response=await fetch(base+p,{method,headers,body:data});const value=await response.json();
 if(!allowError)assert(response.ok&&value.code===200,method+' '+p+' failed '+response.status+'/'+value.code+' '+value.message);
 return allowError?{status:response.status,...value}:value.data;
}
async function login(user,password,kind='admin'){return request('/api/v1/auth/'+kind+'/login','POST',{username:user,password})}
const id=x=>typeof x==='number'?x:x.id;
const out=path.join(root,'docs/50_系统测试/evidence/bx-manual-regression-20261015');fs.mkdirSync(out,{recursive:true});
function assertIsolatedBackend(commandLine){assert(typeof commandLine==='string'&&commandLine.includes('--server.port=8087')&&commandLine.includes('--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/'+db+'?'),'8087 must explicitly use the BX isolated database; refuse all API writes')}
function verifyIsolatedListener(){const p=spawnSync('powershell',['-NoProfile','-Command',"$listener=Get-NetTCPConnection -LocalPort 8087 -State Listen -ErrorAction Stop | Select-Object -First 1; (Get-CimInstance Win32_Process -Filter ('ProcessId='+$listener.OwningProcess)).CommandLine"],{windowsHide:true,encoding:'utf8'});assert.equal(p.status,0,'Isolated backend listener unavailable');assertIsolatedBackend(p.stdout)}
(async()=>{
 verifyIsolatedListener();
 spec=await fetch(base+'/v3/api-docs').then(r=>r.json());const superLogin=await login('superadmin','Admin@123456'),superToken=superLogin.token;
 const c1=id(await request('/api/v1/communities','POST',{name:'BX图片与删除-'+run,address:'隔离测试地址',responsibleAdminId:2},superToken));
 const c2=id(await request('/api/v1/communities','POST',{name:'BX无权限-'+run,address:'隔离测试地址'},superToken));
 const adminLogin=await login('admin1','Admin123456'),admin=adminLogin.token,residentLogin=await login('resident1','Resident123456','resident'),resident=residentLogin.token;
 async function houseIn(communityId,token){const building=id(await request('/api/v1/buildings','POST',{communityId,name:'BX一号楼',floors:2},token));const unit=id(await request('/api/v1/units','POST',{buildingId:building,name:'BX一单元'},token));return id(await request('/api/v1/houses','POST',{unitId:unit,houseNumber:'101',floor:1,area:80,layout:'2室1厅1卫',orientation:'南',status:'VACANT'},token))}
 const house=await houseIn(c1,admin),foreignHouse=await houseIn(c2,superToken);
 check('ADMIN房屋列表相册批量查询不再500',(await request('/api/v1/communities/houses-manage?page=1&size=20', 'GET',undefined,admin)).records.length>0);
 const forbidden=await request('/api/v1/houses/'+foreignHouse+'/images','GET',undefined,admin,true);check('ADMIN跨社区图片读取仍拒绝',forbidden.code!==200&&forbidden.status!==500);
 const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j2ioAAAAASUVORK5CYII=','base64');
 const upload=new FormData();upload.append('file',new Blob([png],{type:'image/png'}),'bx-room.png');upload.append('kind','PHOTO');
 const image=await request('/api/v1/houses/'+house+'/images','POST',upload,admin);check('ADMIN真实图片上传关联成功',!!image.id);
 const album=await request('/api/v1/houses/'+house+'/images','GET',undefined,admin);check('ADMIN相册刷新回读',album.some(x=>x.id===image.id));
 const payload={houseId:house,title:'BX挂牌-'+run,description:'隔离测试房源',monthlyRent:1000,deposit:2000,rentType:'RENT'};
 const listing=await request('/api/v1/housings','POST',payload,admin);check('ADMIN可正常挂牌',!!listing.id);
 await request('/api/v1/housings/'+listing.id,'PUT',{...payload,title:'BX挂牌已更新-'+run},admin);
 const updated=await request('/api/v1/housings/'+listing.id,'GET',undefined,admin);check('挂牌编辑是更新且回显真实图片',updated.title==='BX挂牌已更新-'+run&&JSON.stringify(updated).includes(image.storageKey));
 check('挂牌后房屋管理页不再500',(await request('/api/v1/communities/houses-manage?page=1&size=20','GET',undefined,admin)).records.length>0);
 const publicListing=await request('/api/v1/housings/'+listing.id);check('公开房源同源读取房屋相册',JSON.stringify(publicListing).includes(image.storageKey));
 const duplicate=await request('/api/v1/housings','POST',payload,admin,true);check('同屋重复挂牌为业务拒绝而非500',duplicate.code!==200&&duplicate.status!==500);
 const seedLease=sql('SELECT id FROM lease_record WHERE community_id=1 LIMIT 1');if(seedLease)check('ADMIN合同段子表无错误社区列',Array.isArray(await request('/api/v1/leases/'+seedLease+'/terms','GET',undefined,admin)));
 const configPath='/api/v1/payment/channel-config/alipay',original=await request(configPath,'GET',undefined,superToken);
 const pair=crypto.generateKeyPairSync('rsa',{modulusLength:2048,publicKeyEncoding:{type:'spki',format:'pem'},privateKeyEncoding:{type:'pkcs8',format:'pem'}}),platform=crypto.generateKeyPairSync('rsa',{modulusLength:2048,publicKeyEncoding:{type:'spki',format:'pem'},privateKeyEncoding:{type:'pkcs8',format:'pem'}});
 const configuration={appId:'9021000000000000',environment:'SANDBOX',applicationPublicKey:pair.publicKey,merchantPrivateKey:pair.privateKey,alipayPublicKey:platform.publicKey,clearPrivateKey:false,enabled:false,expectedRevision:original.revision};
 let saved=await request(configPath,'PUT',configuration,superToken);check('保存配置不再等同启用且私钥不回显',saved.configured&&!saved.enabled&&saved.privateKeyConfigured&&!JSON.stringify(saved).includes(pair.privateKey));
 let channel=(await request('/api/v1/payment/channels','GET',undefined,resident)).find(x=>x.channel==='ALIPAY');check('居民渠道状态区分已配置和停用',channel.configured&&!channel.enabled);
 check('非超管不得读取密钥配置',(await request(configPath,'GET',undefined,admin,true)).code!==200);
 configuration.merchantPrivateKey='';configuration.expectedRevision=saved.revision;configuration.enabled=true;
 saved=await request(configPath,'PUT',configuration,superToken);channel=(await request('/api/v1/payment/channels','GET',undefined,resident)).find(x=>x.channel==='ALIPAY');check('超管启用后渠道可选且仅离线构建SDK',saved.enabled&&channel.enabled&&channel.configured);
 configuration.expectedRevision=saved.revision;configuration.enabled=false;saved=await request(configPath,'PUT',configuration,superToken);check('超管可停用并保留密钥',!saved.enabled&&saved.configured);
 const stale=await request(configPath,'PUT',configuration,superToken,true);check('配置版本冲突仍拒绝',stale.code!==200);
 const conversation=Number(sql("INSERT INTO conversation(type,title,community_id,related_id,status,last_message_at) VALUES('DIRECT','BX未读回归',1,"+run.slice(-9)+",'ACTIVE',NOW()); SELECT LAST_INSERT_ID()"));
 sql("INSERT INTO conversation_participant(conversation_id,user_id,owner_type,last_read_message_id) VALUES("+conversation+",1,'RESIDENT',0),("+conversation+",1,'USER',0),("+conversation+",2,'USER',0)");
 sql("INSERT INTO conversation_message(conversation_id,sender_id,sender_role,content) VALUES("+conversation+",2,'ADMIN','BX管理消息'),("+conversation+",1,'RESIDENT','BX居民消息'),("+conversation+",1,'SUPER_ADMIN','BX超管消息')");
 const messageIds=sql('SELECT id FROM conversation_message WHERE conversation_id='+conversation+' ORDER BY id').split(/\s+/).map(Number),last=messageIds.at(-1);
 async function unread(token){return (await request('/api/v1/conversations?page=1&size=50','GET',undefined,token)).records.find(x=>x.id===conversation)?.unreadCount}
 check('居民排除本人但保留同号超管消息',await unread(resident)===2);check('超管排除本人但保留同号居民消息',await unread(superToken)===2);check('管理员不把自己消息算未读',await unread(admin)===2);
 await request('/api/v1/conversations/'+conversation+'/read','POST',{lastMessageId:last},admin);check('已读回写后列表为0',await unread(admin)===0);
 const badCursor=await request('/api/v1/conversations/'+conversation+'/read','POST',{lastMessageId:999999999},admin,true);check('不可用未来消息ID吞掉未读',badCursor.code!==200);
 for(let n=0;n<6;n++){sql("UPDATE conversation_participant SET last_read_message_id=0 WHERE conversation_id="+conversation+" AND user_id=2 AND owner_type='USER'");await Promise.all([request('/api/v1/conversations/'+conversation+'/read','POST',{lastMessageId:messageIds[0]},admin),request('/api/v1/conversations/'+conversation+'/read','POST',{lastMessageId:last},admin)]);assert.equal(Number(sql("SELECT last_read_message_id FROM conversation_participant WHERE conversation_id="+conversation+" AND user_id=2 AND owner_type='USER'")),last)}check('六轮并发旧新已读游标不回退',true);
 const proxyProbe=await fetch(front+'/api/v1/communities/'+c1,{headers:{Authorization:'Bearer '+superToken}}).then(r=>r.json());assert.equal(proxyProbe.data?.name,'BX图片与删除-'+run,'Frontend proxy must point to the isolated backend; refuse browser writes');
 const {chromium}=require('C:/Users/17841/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});const pages=[],blockedTrading=[];
 async function pageFor(session,width=1280,height=800){const context=await browser.newContext({viewport:{width,height}});await context.addInitScript(session=>sessionStorage.setItem('crsp_session',JSON.stringify({token:session.token,user:session.user,expiresAt:Date.now()+session.expiresIn*1000})),session);await context.route('**/*',route=>{const u=new URL(route.request().url());if(!['127.0.0.1','localhost'].includes(u.hostname))return route.abort();if(/first-payments|rent-payments|renew-payments|deposit-refunds|\/payments(?:\/|\?|$)/.test(u.pathname)){blockedTrading.push(u.pathname);return route.abort()}return route.continue()});const page=await context.newPage();pages.push(page);return page}
 try{
  const residentPage=await pageFor(residentLogin);await residentPage.goto(front+'/resident/conversations/'+conversation);await residentPage.getByText('BX超管消息',{exact:false}).waitFor();await residentPage.waitForFunction(()=>document.visibilityState==='visible');
  for(let i=0;i<40&&await unread(resident)!==0;i++)await new Promise(r=>setTimeout(r,100));check('居民打开真实群聊自动保存已读',await unread(resident)===0);
  await residentPage.reload();await residentPage.getByText('BX超管消息',{exact:false}).waitFor();check('刷新后已读不回涨',await unread(resident)===0);
  await request('/api/v1/conversations/'+conversation+'/messages','POST',{content:'BX居民自发不增未读'},resident);check('本人发消息不增加本人未读',await unread(resident)===0);check('对方新增消息才增加未读',await unread(admin)===1);
  const imageForm=new FormData();imageForm.append('file',new Blob([png],{type:'image/png'}),'bx-chat.png');await request('/api/v1/conversations/'+conversation+'/messages/image','POST',imageForm,resident);
  const withImages=await request('/api/v1/conversations/'+conversation+'/messages','GET',undefined,admin);check('ADMIN图片消息元数据不再误注社区列',withImages.some(m=>m.imageUrl||m.image?.url||m.images?.length||m.messageType==='IMAGE'));
  const adminPage=await pageFor(adminLogin);await adminPage.goto(front+'/admin/conversations?id='+conversation);await adminPage.getByText('BX居民自发不增未读',{exact:false}).waitFor();for(let i=0;i<40&&await unread(admin)!==0;i++)await new Promise(r=>setTimeout(r,100));check('管理员会话中心读消息后服务端0',await unread(admin)===0);await adminPage.reload();await adminPage.getByText('BX居民自发不增未读',{exact:false}).waitFor();check('管理员刷新不复现红标',await unread(admin)===0);await adminPage.screenshot({path:path.join(out,'conversation-read-1280.png'),fullPage:true});
  const superPage=await pageFor(superLogin);await superPage.goto(front+'/admin/system?tab=configs');await superPage.getByTestId('alipay-enabled').waitFor();check('页面不再写交易暂停冻结',!(await superPage.locator('body').innerText()).includes('交易部分暂缓'));
  await superPage.getByTestId('alipay-enabled').click();await superPage.getByTestId('save-alipay-config').click();for(let i=0;i<30;i++){saved=await request(configPath,'GET',undefined,superToken);if(saved.enabled)break;await new Promise(r=>setTimeout(r,100))}check('超管真实UI可启用已保存沙箱配置',saved.enabled&&saved.configured);await superPage.screenshot({path:path.join(out,'channel-config-1280.png'),fullPage:true});
  await request('/api/v1/residents/admin-create','POST',{communityId:c1,houseId:house,relationType:'OWNER',moveInDate:new Date().toISOString().slice(0,10),realName:'BX删除测试居民',username:'BXowner_'+run,phone:'1990000'+run.slice(-4)},admin);
  await superPage.goto(front+'/admin/community');await superPage.getByPlaceholder('搜索社区名称 / 地址').fill('BX图片与删除-'+run);await superPage.getByPlaceholder('搜索社区名称 / 地址').press('Enter');await superPage.locator('.community-item').filter({hasText:'BX图片与删除-'+run}).click();
  await superPage.getByRole('button',{name:'删除',exact:true}).click();let dialog=superPage.locator('.community-deletion-dialog');await dialog.locator('.impact-grid').waitFor();check('删除预览为六格真实影响数据',await dialog.locator('.impact-grid > div').count()===6);await dialog.screenshot({path:path.join(out,'deletion-preview-1280.png')});
  await dialog.getByRole('button',{name:'取消删除',exact:true}).click();await dialog.waitFor({state:'hidden'});check('取消预览不删除社区',!!await request('/api/v1/communities/'+c1,'GET',undefined,superToken));
  await superPage.setViewportSize({width:768,height:720});await superPage.getByRole('button',{name:'删除',exact:true}).click();await dialog.locator('.impact-grid').waitFor();await dialog.screenshot({path:path.join(out,'deletion-preview-768.png')});
  const buttonFit=await dialog.locator('.el-message-box__btns').evaluate(el=>{const r=el.getBoundingClientRect();return [...el.querySelectorAll('button')].every(b=>{const q=b.getBoundingClientRect();return q.left>=r.left&&q.right<=r.right+1})});check('窄屏删除操作按钮均可达',buttonFit);
  await dialog.getByRole('button',{name:'继续核对名称',exact:true}).click();dialog=superPage.locator('.community-deletion-dialog').filter({has:superPage.getByPlaceholder('输入完整社区名称')});await dialog.getByPlaceholder('输入完整社区名称').fill('错误社区名称');await dialog.getByRole('button',{name:'永久删除',exact:true}).click();await dialog.getByText('社区名称不一致，未执行删除',{exact:true}).waitFor();check('错误名称不执行删除',!!await request('/api/v1/communities/'+c1,'GET',undefined,superToken));await dialog.screenshot({path:path.join(out,'deletion-final-768.png')});await dialog.getByPlaceholder('输入完整社区名称').fill('BX图片与删除-'+run);await dialog.getByRole('button',{name:'永久删除',exact:true}).click();await superPage.getByText('社区已删除',{exact:true}).waitFor();check('正确名称删除隔离社区及其有效关系',sql('SELECT (SELECT COUNT(*) FROM community WHERE id='+c1+')+(SELECT COUNT(*) FROM residence_relation WHERE community_id='+c1+')+(SELECT COUNT(*) FROM house WHERE id='+house+')')==='0');check('删除社区后独立居民账号保留',sql("SELECT COUNT(*) FROM resident WHERE username='BXowner_"+run+"'")==='1');
  check('浏览器未触发交易请求',blockedTrading.length===0);
 }finally{await browser.close()}
 check('隔离库未产生支付退款',sql('SELECT (SELECT COUNT(*) FROM lease_payment)+(SELECT COUNT(*) FROM lease_deposit_refund)')==='0');
 const summary={checkedAt:new Date().toISOString(),database:db,checks,passed:checks.length,failed:0,channelsCalled:false,userBrowserOperated:false,normalDataWritten:false,notes:['只用合成密钥启用隔离沙箱，不创建支付/退款、不查单','可见页面已读、刷新、自发、对方新消息、身份碰撞与原子游标实际DB验证','删除验证含取消、错误名称、正确名称删除及账号保留，仅删除本轮隔离合成社区，不删除正常社区']};fs.writeFileSync(path.join(out,'verification.json'),JSON.stringify(summary,null,2)+'\n');console.log(JSON.stringify({passed:checks.length,failed:0,evidence:path.relative(root,out)}));
})().catch(error=>{fs.writeFileSync(path.join(out,'failure.json'),JSON.stringify({checkedAt:new Date().toISOString(),checks,error:String(error).slice(0,1000)},null,2));console.error(String(error).slice(0,1000));process.exitCode=1});
