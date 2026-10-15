<script setup lang="ts">
import { computed, ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/utils/request'
import { contractEnd } from '@/utils/leaseTerms'
import type { ILeaseRecord } from '@/types/modules/lease'
interface Term { id:number;paidMonths:number;remainingMonths:number;nextDueDate:string|null;overdue:boolean;paidPeriods:{paymentNo:string;startDate:string;endDate:string}[];startDate:string;endDate:string;contractMonths:number;monthlyRent:number;status:string;termType:string;contentSnapshot:string }
interface Extension {id:number;leaseId:number;residentId:number;months:number;originalEndDate:string;proposedEndDate:string;monthlyRent:number;deposit:number;reason:string;status:string;reviewNote:string}
const props=defineProps<{lease:ILeaseRecord;admin?:boolean}>()
const emit=defineEmits<{changed:[]}>()
const terms=ref<Term[]>([]),extensions=ref<Extension[]>([]),busy=ref(false),error=ref('')
const form=reactive({months:3,reason:''})
const totalMonths=computed(()=>terms.value.filter(t=>t.status==='ACTIVE').reduce((sum,t)=>sum+t.contractMonths,0))
const proposedEnd=computed(()=>contractEnd(props.lease.startDate,totalMonths.value+form.months))
const currentEnd=computed(()=>terms.value.filter(t=>t.status==='ACTIVE').map(t=>t.endDate).sort().at(-1)??props.lease.endDate)
const pending=computed(()=>extensions.value.some(e=>e.status==='PENDING'))
const labels:Record<string,string>={PENDING:'待审核',APPROVED:'已通过',REJECTED:'已驳回',WITHDRAWN:'已撤回',OFFERED:'历史待确认',SIGNED:'已确认待支付（历史方案）',ACTIVE:'已生效',CANCELLED:'已取消'}
let epoch=0
async function load(){const run=++epoch,id=props.lease.id;busy.value=true;error.value='';try{const [t,a]=await Promise.all([http.get<Term[]>(`/leases/${id}/terms`),http.get<Extension[]>(`/leases/${id}/extensions`)]);if(run===epoch){terms.value=t;extensions.value=a}}catch(e){if(run===epoch)error.value=e instanceof Error?e.message:'合同读取失败'}finally{if(run===epoch)busy.value=false}}
watch(()=>props.lease.id,()=>{terms.value=[];extensions.value=[];void load()},{immediate:true})
async function apply(){if(busy.value)return;busy.value=true;try{await http.post(`/leases/${props.lease.id}/extensions`,{...form});await load();emit('changed');ElMessage.success('延长申请已提交，审批前原期限不变')}catch(e){ElMessage.error(e instanceof Error?e.message:'提交失败')}finally{busy.value=false}}
async function act(a:Extension,action:'approve'|'reject'|'withdraw'){
 if(busy.value)return
 let reason=''
 try{if(action==='reject'){reason=(await ElMessageBox.prompt('请说明驳回原因；不能在审核时更改居民申请的月数或价格。','驳回租期延长',{inputValidator:v=>!!v?.trim()||'请填写驳回原因'})).value}
 else await ElMessageBox.confirm(action==='approve'?`批准后合同由${a.originalEndDate}延长至${a.proposedEndDate}，沿用月租${a.monthlyRent}元、不加收押金。租金照常缴纳，已有欠租不会免除。`:'撤回后原合同期限不变。','确认租期操作',{type:'warning'})
 busy.value=true
 if(action==='withdraw')await http.post(`/leases/${props.lease.id}/extensions/${a.id}/withdraw`)
 else await http.post(`/leases/${props.lease.id}/extensions/${a.id}/review`,{approve:action==='approve',reason})
 await load();emit('changed');ElMessage.success('租期申请已处理')
 }catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'操作失败')}finally{busy.value=false}
}
async function cancelLegacy(t:Term){try{await ElMessageBox.confirm('只取消旧未生效方案；有有效支付单时将被拒绝，不改变原合同或已付款记录。','取消历史方案',{type:'warning'});busy.value=true;await http.post(`/leases/${props.lease.id}/terms/${t.id}/cancel`);await load();emit('changed')}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'取消失败')}finally{busy.value=false}}
</script>
<template><section v-loading="busy" class="lease-terms">
 <header><h3>合同与租期</h3><p>{{lease.startDate}} 至 {{currentEnd}} · 已确认{{totalMonths||lease.contractMonths}}个月</p><p>普通交租不延长合同；延长申请经管理方批准即生效，不另收押金。</p></header>
 <el-alert v-if="error" :title="error" type="error" :closable="false"/><el-button v-if="error" @click="load">重试读取</el-button>
 <el-empty v-if="!busy&&!error&&!terms.length" description="历史合同付款安排未补录，请由管理方核对，不推测日期或费用" />
 <article v-for="term in terms" :key="term.id"><strong>{{term.termType==='INITIAL'?'初始合同':term.termType==='EXTENSION'?'已批准延长期':'历史续租协议'}} · {{labels[term.status]??term.status}}</strong><p>{{term.startDate}} 至 {{term.endDate}} · {{term.contractMonths}}个月 · 月租{{term.monthlyRent}}元</p><p>已缴{{term.paidMonths}}个月 / 待缴{{term.remainingMonths}}个月<span v-if="term.nextDueDate">；最早未缴 {{term.nextDueDate}}（{{term.overdue?'已逾期':'可提前缴纳'}}）</span></p><ul><li v-for="paid in term.paidPeriods" :key="paid.paymentNo">{{paid.paymentNo}}：{{paid.startDate}} 至 {{paid.endDate}}</li></ul><details><summary>查看该版本快照</summary><pre>{{term.contentSnapshot}}</pre></details><el-button v-if="term.termType==='RENEWAL'&&['OFFERED','SIGNED'].includes(term.status)" :disabled="busy" @click="cancelLegacy(term)">取消历史未生效方案</el-button></article>
 <h4>租期延长申请</h4><article v-for="a in extensions" :key="a.id"><strong>{{labels[a.status]}} · 延长{{a.months}}个月</strong><p>{{a.originalEndDate}} → {{a.proposedEndDate}} · 月租{{a.monthlyRent}}元 · 无新增押金</p><p>申请人账号 #{{a.residentId}}；{{a.reason||'未附说明'}}</p><p v-if="a.reviewNote">审核说明：{{a.reviewNote}}</p><div v-if="a.status==='PENDING'"><template v-if="admin"><el-button :disabled="busy" type="primary" @click="act(a,'approve')">批准延长</el-button><el-button :disabled="busy" type="danger" plain @click="act(a,'reject')">驳回</el-button></template><el-button v-else :disabled="busy" @click="act(a,'withdraw')">撤回申请</el-button></div></article>
 <el-empty v-if="!extensions.length&&!busy&&!error" description="暂无延长申请" :image-size="50"/>
 <el-form v-if="!admin&&lease.status==='ACTIVE'&&!error" label-position="top" class="extension-form"><h4>申请延长租期</h4><el-form-item label="延长月数"><el-input-number v-model="form.months" :min="1" :max="1200" :precision="0" :disabled="busy||pending" /></el-form-item><p>{{currentEnd}} → {{proposedEnd}}；沿用月租{{lease.monthlyRent}}元，不增加押金。</p><el-form-item label="申请说明"><el-input v-model="form.reason" type="textarea" maxlength="500" :disabled="busy||pending" /></el-form-item><p v-if="pending">已有申请待审，可撤回后重提，不能重复申请。</p><el-button type="primary" :disabled="busy||pending||!totalMonths" @click="apply">同意此方案并提交延长申请</el-button></el-form>
</section></template>
<style scoped>.lease-terms{padding:18px;border:1px solid var(--el-border-color);border-radius:8px;min-width:0}.lease-terms header{border-bottom:2px solid var(--el-color-primary);padding-bottom:12px}.lease-terms article{padding:14px 0;border-bottom:1px solid var(--el-border-color-lighter)}pre{white-space:pre-wrap;overflow-wrap:anywhere}.extension-form{margin-top:18px;padding:16px;background:var(--el-fill-color-light)}.lease-terms p{line-height:1.7;overflow-wrap:anywhere}</style>
