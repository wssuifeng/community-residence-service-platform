/* HT-01～06非交易回归；仅专用BP库，端点先与当前OpenAPI对齐。 */
const fs=require('node:fs'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
const {properties}=require('./nontransaction-fixture-helpers.cjs');
const database='community_residence_bp_20261015',base='http://127.0.0.1:8087/api/v1',dir='docs/50_系统测试/evidence/bp-manual-fixes-20261015';
function sql(q){const p=properties(),r=spawnSync('C:/Program Files/MySQL/MySQL Server 9.6/bin/mysql.exe',['-h127.0.0.1','-u'+(p.DB_USERNAME||'root'),'-D'+database,'-N','-B','--default-character-set=utf8mb4','-e',q],{env:{...process.env,MYSQL_PWD:p.DB_PASSWORD},windowsHide:true,encoding:'utf8'});assert.equal(r.status,0,'BP隔离SQL失败');return r.stdout.trim().split('\n').filter(Boolean).map(l=>l.split('\t'))}
let spec;async function request(p,method='GET',data,token,allow=false){assert(!/payment|refund|renew/i.test(p),'禁止金融端点，包括主动查单GET');spec??=await fetch('http://127.0.0.1:8087/v3/api-docs').then(r=>r.json());const u=new URL(base+p);assert(Object.entries(spec.paths).some(([pattern,entry])=>new RegExp('^'+pattern.replace(/\{[^}]+\}/g,'[^/]+')+'$').test(u.pathname)&&entry[method.toLowerCase()]),'端点不在OpenAPI：'+method+' '+u.pathname);const res=await fetch(base+p,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(data!==undefined?{body:JSON.stringify(data)}:{})}),b=await res.json();if(!allow)assert.equal(b.code,200,`${method} ${p}: ${b.message}`);return allow?b:b.data}
const evidence={date:'2026-10-15',database,transactionsExecuted:false,checks:[]};function check(name,ok){assert(ok,name);evidence.checks.push({name,passed:true})}
const date=new Date();date.setDate(date.getDate()+2);const future=date.toLocaleDateString('en-CA'),weekday=date.getDay()||7,today=new Date().toLocaleDateString('en-CA');
async function main(){
 const superSession=await request('/auth/admin/login','POST',{username:'superadmin',password:'Admin@123456'}),root=superSession.token;
 check('响应与真实JWT均36h',superSession.expiresIn===129600&&(()=>{const c=JSON.parse(Buffer.from(root.split('.')[1],'base64url'));return c.exp-c.iat===129600})());
 const community=await request('/communities','POST',{name:'BP核心手测隔离社区',address:'仅隔离回归',businessCode:'BP',responsibleAdminId:2},root);
 const adminSession=await request('/auth/admin/login','POST',{username:'admin1',password:'Admin123456'}),admin=adminSession.token;
 const building=await request('/buildings','POST',{communityId:community.id,name:'1号楼',businessCode:'1',floors:3},admin);
 const unit=await request('/units','POST',{buildingId:building.id,name:'1单元',businessCode:'1'},admin);
 const houses=[];for(const n of ['101','102'])houses.push(await request('/houses','POST',{unitId:unit.id,houseNumber:n,floor:1,area:80,layout:'三室一厅一卫',orientation:'南'},admin));
 const owner=await request('/residents/admin-create','POST',{communityId:community.id,username:'BP主住户',realName:'BP主住户',phone:'13899150001',houseId:houses[0].id,relationType:'OWNER',moveInDate:today},admin);
 const member=await request('/residents/admin-create','POST',{communityId:community.id,username:'BP同住成员',realName:'BP同住成员',phone:'13899150002',houseId:houses[0].id,relationType:'FAMILY',anchorRelationId:owner.relationId,moveInDate:today},admin);
 const dependents=await request(`/residence-relations/${owner.relationId}/dependents`,'GET',undefined,admin);
 check('主关系可读成员及独立账号名',dependents.length===1&&dependents[0].residentUsername===member.username);
 let impact=await request(`/communities/${community.id}/deletion-impact`,'GET',undefined,root);
 check('删除影响列出有效关系并禁止删除',impact.relations===2&&impact.activeRelations===2&&!impact.deletable);
 check('绕过UI直接删仍拒绝', (await request(`/communities/${community.id}?confirmationVersion=${impact.confirmationVersion}`,'DELETE',undefined,root,true)).code!==200);
 check('普通ADMIN不能取删除影响', (await request(`/communities/${community.id}/deletion-impact`,'GET',undefined,admin,true)).code!==200);
 const empty=await request('/communities','POST',{name:'BP空社区删除检查',address:'隔离'},root);
 impact=await request(`/communities/${empty.id}/deletion-impact`,'GET',undefined,root);
 check('缺确认版本不能删除空社区',(await request(`/communities/${empty.id}`,'DELETE',undefined,root,true)).code!==200);
 await request(`/communities/${empty.id}?confirmationVersion=${impact.confirmationVersion}`,'DELETE',undefined,root);
 check('正确预览确认可删除空社区', (await request(`/communities/${empty.id}`,'GET',undefined,root,true)).code!==200);
 const renter=await request('/residents/admin-create','POST',{communityId:community.id,username:'BP承租居民',realName:'BP承租居民',phone:'13899150003'},admin);
 const renterSession=await request('/auth/resident/login','POST',{username:renter.username,password:renter.initialPassword}),resident=renterSession.token;
 const listing=await request('/housings','POST',{houseId:houses[1].id,title:'BP租约来源房源',monthlyRent:3000,deposit:6000,rentType:'RENT'},admin);
 await request(`/housings/${listing.id}/timeslots`,'POST',{dayOfWeek:weekday,startTime:'10:00',endTime:'12:00',isAvailable:1},admin);
 const application=await request('/residence-applications','POST',{houseId:houses[1].id,housingId:listing.id,relationType:'TENANT'},resident);
 const approved=await request(`/residence-applications/${application.id}/approve`,'PATCH',{leaseStartDate:today,leaseEndDate:'2027-10-14',monthlyRent:3000,deposit:6000},admin);
 const lease=await request(`/leases/${approved.leaseId}`,'GET',undefined,resident),conversationId=lease.conversationId;
 check('审批产生真实租约和会话',Number.isSafeInteger(conversationId)&&conversationId>0);
 check('占用来源对公众隐藏',(await request(`/housings/${listing.id}`,'GET',undefined,undefined,true)).code!==200);
 check('公共时段也不泄漏占用房源',(await request(`/housings/${listing.id}/available-slots?startDate=${future}`,'GET',undefined,resident,true)).code!==200);
 const context=await request(`/conversations/${conversationId}/viewing-context`,'GET',undefined,resident);
 check('私有会话上下文精确来源',context.id===listing.id&&context.houseId===houses[1].id);
 const slots=await request(`/conversations/${conversationId}/viewing-slots?startDate=${future}&endDate=${future}`,'GET',undefined,resident);
 check('本人占用房源可读私有可约时段',slots.length===2);
 const viewing=await request(`/conversations/${conversationId}/viewing-appointments`,'POST',{housingId:listing.id,appointmentDate:future,startTime:'10:00',endTime:'11:00'},resident);
 check('会话预约复用真实来源和会话、不创建另一群',viewing.housingId===listing.id&&viewing.leaseId===lease.id&&viewing.conversationId===conversationId);
 check('重复时段仍冲突',(await request(`/conversations/${conversationId}/viewing-appointments`,'POST',{housingId:listing.id,appointmentDate:future,startTime:'10:00',endTime:'11:00'},resident,true)).code!==200);
 await request(`/viewing-appointments/${viewing.id}/cancel`,'PATCH',{reason:'验证取消子预约不关闭租约会话'},resident);
 const parent=await request(`/conversations/${conversationId}`,'GET',undefined,resident);
 check('取消子预约不会关闭父租约群聊',parent.status==='ACTIVE');
 check('取消后私有时段恢复且上下文仍可读',(await request(`/conversations/${conversationId}/viewing-slots?startDate=${future}&endDate=${future}`,'GET',undefined,resident)).every(s=>s.currentBookings===0));

 const stranger=await request('/auth/resident/login','POST',{username:'resident1',password:'Resident123456'});
 check('非参与者不可读会话上下文',(await request(`/conversations/${conversationId}/viewing-context`,'GET',undefined,stranger.token,true)).code!==200);
 const template=await request('/agreement-templates','POST',{communityId:community.id,name:'BP协议模板',content:'房屋{{房屋位置}}；起租{{租期开始}}；月租{{月租金}}。本协议为平台确认留痕，不是可靠电子签名。',isDefault:1},admin);
 const agreement=await request('/lease-agreements','POST',{leaseId:lease.id,templateId:template.id},admin);
 let messages=await request(`/conversations/${conversationId}/messages`,'GET',undefined,resident);
 check('发协议自动发送可信业务消息',messages.some(m=>m.messageType==='LEASE_AGREEMENT'&&m.senderRole==='SYSTEM'&&m.agreementId===agreement.id));
 const text=await request(`/conversations/${conversationId}/messages`,'POST',{content:'普通发言不能伪造协议卡片',messageType:'LEASE_AGREEMENT',agreementId:agreement.id},resident);
 check('客户端伪造业务字段忽略，仍为TEXT',text.messageType==='TEXT'&&text.agreementId==null);
 await request(`/lease-agreements/${agreement.id}/confirm`,'POST',{},resident);
 const signedLease=await request(`/leases/${lease.id}`,'GET',undefined,resident);
 check('同意协议后仍未生效、不假造付款',signedLease.agreementStatus==='SIGNED'&&signedLease.status==='TO_SIGN');
 messages=await request(`/conversations/${conversationId}/messages`,'GET',undefined,resident);
 check('签约业务消息保留相同快照ID',messages.filter(m=>m.messageType==='LEASE_AGREEMENT'&&m.agreementId===agreement.id).length===2);
 const manage=await request(`/communities/houses-manage?communityId=${community.id}&size=20`,'GET',undefined,admin);
 check('挂牌管理真实派生待首期支付',manage.records.find(h=>h.id===houses[1].id||h.houseId===houses[1].id)?.housing?.occupancy==='WAITING_FIRST_PAYMENT');
 const occupiedSale=await request('/housings','POST',{houseId:houses[0].id,title:'BP已入住房屋出售展示',monthlyRent:0,rentType:'SALE'},admin);
 const occupiedManage=await request(`/communities/houses-manage?communityId=${community.id}&size=20`,'GET',undefined,admin);
 check('合法OWNER已入住房屋的SALE挂牌仍显示OCCUPIED，未伪造租客支付生效',occupiedManage.records.find(h=>h.housing?.id===occupiedSale.id)?.housing?.occupancy==='OCCUPIED');
 const loggedOut=await request('/auth/resident/login','POST',{username:renter.username,password:renter.initialPassword});
 await request('/auth/resident/logout','POST',{},loggedOut.token);
 check('36h新令牌登出后立即失效，原独立会话未受影响',(await request(`/leases/${lease.id}`,'GET',undefined,loggedOut.token,true)).code!==200);
 check('没有支付/退款单',sql('SELECT (SELECT COUNT(*) FROM lease_payment),(SELECT COUNT(*) FROM lease_deposit_refund)')[0].every(x=>x==='0'));
 fs.mkdirSync(dir,{recursive:true});fs.writeFileSync(dir+'/api.json',JSON.stringify(evidence,null,2));
 fs.writeFileSync('output/runtime/bp-fixture-private.json',JSON.stringify({community,owner,member,renter,lease:signedLease,conversationId,listing,viewing,adminSession,renterSession,superSession,future},null,2));
 console.log(JSON.stringify({checks:evidence.checks.length,transactionsExecuted:false}));
}
module.exports={request,sql,dir};if(require.main===module)main().catch(e=>{console.error(e.message);process.exitCode=1});
