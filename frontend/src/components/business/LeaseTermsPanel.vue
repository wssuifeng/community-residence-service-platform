<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/utils/request'
import { contractEnd } from '@/utils/leaseTerms'
import type { ILeaseRecord } from '@/types/modules/lease'
interface Term { paidMonths:number; remainingMonths:number; nextDueDate:string|null; overdue:boolean; paidPeriods:{paymentNo:string;startDate:string;endDate:string}[]; id:number; startDate:string; endDate:string; contractMonths:number; firstPaymentMonths:number; paymentCycleMonths:number; monthlyRent:number; status:string; termType:string; contentSnapshot:string }
const props=defineProps<{lease:ILeaseRecord; admin?:boolean}>()
const emit=defineEmits<{ changed: [] }>()
const terms=ref<Term[]>([]),busy=ref(false),error=ref('')
const form=reactive({months:6,firstPaymentMonths:1,paymentCycleMonths:1,monthlyRent:props.lease.monthlyRent})
const labels:Record<string,string>={OFFERED:'管理方已发出，待居民同意',SIGNED:'居民已同意，待新增段首笔租金',ACTIVE:'已生效',CANCELLED:'已取消'}
let loadEpoch = 0
async function load() {
 const epoch = ++loadEpoch, leaseId = props.lease.id
 busy.value = true; error.value = ''; terms.value = []
 try {
  const rows = await http.get<Term[]>(`/leases/${leaseId}/terms`)
  if (epoch === loadEpoch && leaseId === props.lease.id) terms.value = rows
 } catch (e) {
  if (epoch === loadEpoch) error.value = e instanceof Error ? e.message : '合同段读取失败'
 } finally { if (epoch === loadEpoch) busy.value = false }
}
watch(()=>props.lease.id,()=>{form.monthlyRent=props.lease.monthlyRent;void load()},{immediate:true})
async function propose(){busy.value=true;try{await http.post(`/leases/${props.lease.id}/renew`,{...form,newEndDate:contractEnd(nextDay(props.lease.endDate),form.months),deposit:props.lease.deposit??0});await load();emit('changed');ElMessage.success('续租协议已发出，尚未延长原合同')}catch(e){ElMessage.error(e instanceof Error?e.message:'发出失败')}finally{busy.value=false}}
function nextDay(iso:string){const date=new Date(`${iso}T00:00:00`);date.setDate(date.getDate()+1);const p=(n:number)=>String(n).padStart(2,'0');return `${date.getFullYear()}-${p(date.getMonth()+1)}-${p(date.getDate())}`}
async function act(term:Term,action:'confirm'|'cancel'){try{await ElMessageBox.confirm(action==='confirm'?'请核对该续租协议快照。同意后仍须新增段首笔租金支付成功才延长原合同。':'仅取消未生效的新增协议，不改变原合同。','确认操作',{type:'warning'});busy.value=true;await http.post(`/leases/${props.lease.id}/terms/${term.id}/${action}`);await load();emit('changed')}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'操作失败')}finally{busy.value=false}}
</script>
<template>
<section v-loading="busy" class="lease-terms">
 <h3>合同期限与缴租安排</h3><p>当前合同：{{lease.startDate}} 至 {{lease.endDate}}；首付{{lease.firstPaymentMonths??'待核对'}}个月，后续每{{lease.paymentCycleMonths??'待核对'}}个月缴租。普通交租不延长合同。</p>
 <el-alert v-if="error" :title="error" type="error" :closable="false"/>
 <el-empty v-if="!busy&&!error&&!terms.length" description="历史合同付款安排未补录，请由管理方核对，不推测已缴金额或日期" />
 <article v-for="term in terms" :key="term.id"><strong>{{term.termType==='RENEWAL'?'续租协议':'初始合同'}} · {{labels[term.status]??term.status}}</strong><p>{{term.startDate}} 至 {{term.endDate}} · 共{{term.contractMonths}}个月 · 月租{{term.monthlyRent}}元 · 首付{{term.firstPaymentMonths}}个月</p><p>已缴{{term.paidMonths}}个月 / 待缴{{term.remainingMonths}}个月<span v-if="term.nextDueDate">；下一应付日 {{term.nextDueDate}}（{{term.overdue?'已逾期':'按期缴租'}}）</span></p><ul><li v-for="paid in term.paidPeriods" :key="paid.paymentNo">{{paid.paymentNo}}：{{paid.startDate}} 至 {{paid.endDate}}</li></ul><details><summary>查看该版本协议快照</summary><pre>{{term.contentSnapshot}}</pre></details><el-button v-if="!admin&&term.status==='OFFERED'&&term.termType==='RENEWAL'" :disabled="busy" @click="act(term,'confirm')">同意续租协议</el-button><el-button v-if="term.termType==='RENEWAL'&&['OFFERED','SIGNED'].includes(term.status)" :disabled="busy" @click="act(term,'cancel')">取消本次续租</el-button></article>
 <el-form v-if="admin&&lease.status==='ACTIVE'" label-width="110px" class="renew-form"><h4>拟定并发出续租协议（不直接改原止期）</h4><el-form-item label="新增合同月数"><el-input-number v-model="form.months" :min="1" :max="1200"/></el-form-item><el-form-item label="新增段月租"><el-input-number v-model="form.monthlyRent" :min="0" :precision="2"/></el-form-item><el-form-item label="新增段首付"><el-input-number v-model="form.firstPaymentMonths" :min="1" :max="form.months"/></el-form-item><el-form-item label="后续缴租周期"><el-input-number v-model="form.paymentCycleMonths" :min="1" :max="form.months"/></el-form-item><p>沿用原已缴押金；续租首付不等于一次付清新增期限。</p><el-button type="primary" :disabled="busy" @click="propose">管理方同意并发出续租协议</el-button></el-form>
</section>
</template>
<style scoped>.lease-terms{padding:16px;border:1px solid var(--el-border-color);border-radius:8px;margin:12px 0;min-width:0}.lease-terms article{padding:12px 0;border-bottom:1px solid var(--el-border-color-lighter)}pre{white-space:pre-wrap;overflow-wrap:anywhere}.renew-form{margin-top:18px}</style>
