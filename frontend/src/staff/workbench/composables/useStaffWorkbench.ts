import { onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/common/auth/store/user'
import { getStaffWorkbenchSummary, type StaffWorkbenchSummary } from '../api/workbench'
export function useStaffWorkbench() {
 const user = useUserStore()
 const summary = ref<StaffWorkbenchSummary>({todoCount:0,doingCount:0,completedCount:0,urgentCount:0,todoOrders:[]})
 const loading = ref(false), error = ref('')
 let version = 0
 const reset = () => { version++; summary.value={todoCount:0,doingCount:0,completedCount:0,urgentCount:0,todoOrders:[]}; error.value=''; loading.value=false }
 async function load() {
  const current=++version; loading.value=true; error.value=''
  try { const value=await getStaffWorkbenchSummary(); if(current===version) summary.value=value }
  catch(e) { if(current===version) error.value=e instanceof Error?e.message:'工作台数据加载失败' }
  finally { if(current===version) loading.value=false }
 }
 watch(()=>[user.user?.id,user.user?.role],reset)
 onBeforeUnmount(reset)
 return { summary, loading, error, load }
}
