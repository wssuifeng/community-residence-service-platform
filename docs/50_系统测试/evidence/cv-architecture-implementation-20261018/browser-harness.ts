import {createApp,defineComponent,h,ref,nextTick} from 'vue'
import {createPinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '/src/common/styles/variables.css'
import '/src/common/styles/index.css'
import client from '/src/common/web/utils/request'
import {saveSession} from '/src/common/auth/utils/auth'
import Dashboard from '/src/staff/workbench/views/DashboardView.vue'
import Leases from '/src/resident/lease/views/LeaseListView.vue'
import Structure from '/src/admin/community/components/StructureTreePane.vue'
import {useChatChannel} from '/src/common/conversation/composables/useChatChannel'
const params=new URLSearchParams(location.search),mode=params.get('mode')??'staff',fail=ref(params.has('fail'))
const calls=[],faults=[],started=performance.now(),page=records=>({records,total:records.length,page:1,size:100,pages:1})
const community={id:1,name:'架构验证社区——较长名称与地址的合成数据',status:'ACTIVE',address:'独立合成地址',businessCode:'5'}
const house=i=>({id:i,unitId:10,communityId:1,houseNumber:String(100+i),floor:Math.ceil(i/6),status:i%4===0?'OCCUPIED':'VACANT',area:86,layout:'三室一厅一卫',orientation:'南',businessCode:`5-1-1-${100+i}`})
const order=i=>({id:i,title:'较长的工单标题：公共设施检查、楼道照明与安全隐患处理 '+i,orderNo:'WO2026101800'+i,priority:i<3?'URGENT':'NORMAL',status:'ASSIGNED',residentName:'合成居民'+i,createdAt:'2026-10-18T12:00:00'})
const lease=i=>({id:i,tenantId:1,communityId:1,communityName:community.name,houseId:i,houseLocation:'一号楼一单元'+(100+i),status:'ACTIVE',agreementStatus:'SIGNED',startDate:'2026-10-18',endDate:'2027-10-17',monthlyRent:2000,deposit:4000,depositStatus:'PAID',createdAt:'2026-10-18T12:00:00'})
client.defaults.adapter=async config=>{
 const url=config.url;calls.push({url,at:performance.now()-started});let data
 if(url==='/staff/workbench/summary'){if(fail.value)throw new Error('合成工作台摘要失败');data={todoCount:6,doingCount:3,completedCount:12,urgentCount:2,todoOrders:Array.from({length:6},(_,i)=>order(i+1))}}
 else if(url==='/statistics/staff/trend')data={days:7,startDate:'2026-10-12',endDate:'2026-10-18',assignedTrend:{'2026-10-17':3,'2026-10-18':4},submittedTrend:{'2026-10-17':2,'2026-10-18':3},todayAssigned:4,todaySubmitted:3,totalSubmitted:5}
 else if(url==='/notifications')data=page([])
 else if(url==='/leases/my-summaries')data=page(Array.from({length:10},(_,i)=>({lease:lease(i+1),summary:{displayStatus:'在租',firstPaymentSucceeded:true,firstPaymentPaidAmount:6000,firstPaymentPaidAt:'2026-10-18T12:01:00',paidRentMonths:1,unpaidRentMonths:11,actions:{firstPayment:{enabled:false,reason:'首期已结清'},rentPayment:{enabled:true,reason:'合同内正常缴租'},extension:{enabled:true,reason:'租期延长申请'},moveOut:{enabled:true,reason:'申请退租'}}}})))
 else if(/^\/leases\/\d+\/(payments|deposit-refunds|changes)$/.test(url))data=[]
 else if(url==='/communities')data=page([community])
 else if(url==='/communities/1')data=community
 else if(url==='/communities/1/buildings')data=page([{id:1,communityId:1,name:'一号楼',floors:20,businessCode:'5-1'}])
 else if(url==='/buildings/1/units')data=page([{id:10,buildingId:1,name:'一单元',businessCode:'5-1-1'}])
 else if(url==='/units/10/houses'){const all=Array.from({length:120},(_,i)=>house(i+1));data={...page(all),records:all.slice((config.params.page-1)*100,config.params.page*100),page:config.params.page,pages:2}}
 else if(url==='/statistics/dashboard')data={buildingCount:1,houseCount:120,occupiedHouseCount:30,houseStatusDistribution:{VACANT:90,OCCUPIED:30}}
 else if(/^\/houses\/\d+\/(residents|status-history)$/.test(url))data=page([])
 else {faults.push(url);throw new Error('未配置的合成读取 '+url)}
 return {data:{code:200,data},status:200,statusText:'OK',headers:{},config}
}
const role=mode==='staff'?'STAFF':mode==='lease'||mode==='cache'?'RESIDENT':'SUPER_ADMIN'
saveSession({token:'synthetic-only',expiresAt:Date.now()+3600000,user:{id:1,role,username:'合成账号',realName:'合成人员'}})
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]})
await router.push(mode==='staff'?'/staff':mode==='lease'?'/resident/housing-lease?tab=lease':'/admin/community');await router.isReady()
let channel,chatId=ref(1),windowReads=0
const msg=id=>({id,content:'合成消息 '+id,createdAt:'2026-10-18T12:00:00'})
const Cache=defineComponent({setup(){channel=useChatChannel({id:()=>chatId.value,closed:()=>false,load:async()=>[],initialWindow:async()=>{windowReads++;return {records:Array.from({length:40},(_,i)=>msg(161+i)),hasOlder:true,startAtUnread:false}},window:async(_id,before,after)=>{windowReads++;return before?Array.from({length:Math.max(0,Math.min(40,before-1))},(_,i)=>msg(Math.max(1,before-40)+i)):[]},send:async()=>msg(201),image:async()=>msg(202),topic:id=>'synthetic:'+id,eventType:'SYNTHETIC'})
return()=>h('section',[h('h1','共享会话引擎容量检查（合成界面）'),h('textarea',{value:channel.text.value,onInput:e=>channel.text.value=e.target.value}),h('div',channel.messages.value.map(m=>h('p',{class:'synthetic-message',key:m.id},m.content)))])}})
createApp({render:()=>h('main',{style:'padding:24px;max-width:1600px;margin:auto'},[h(mode==='staff'?Dashboard:mode==='lease'?Leases:mode==='structure'?Structure:Cache)])}).use(createPinia()).use(router).use(ElementPlus).mount('#app')
window.__cv={calls,faults,fail,chatId,get channel(){return channel},get windowReads(){return windowReads},async settle(){await nextTick();await new Promise(r=>setTimeout(r,180))},measure(){return {mode,width:innerWidth,documentWidth:document.documentElement.scrollWidth,dom:document.querySelectorAll('*').length,houseCards:document.querySelectorAll('.grid-block').length,leaseCards:document.querySelectorAll('.lease-row').length,todoRows:document.querySelectorAll('.todo-row').length,calls:[...calls],faults:[...faults],elapsedMs:performance.now()-started}}}
