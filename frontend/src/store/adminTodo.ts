import { invalidateData } from '@/common/web/utils/dataFreshness'
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { http } from '@/common/web/utils/request'
import { loadSession } from '@/common/auth/utils/auth'
export const useAdminTodoStore=defineStore('adminTodo',()=>{
 const counts=ref<Record<string,number>>({});let inflight:Promise<void>|null=null
 async function refresh(){if(inflight)return inflight;const session=loadSession();if(!session||!['ADMIN','SUPER_ADMIN'].includes(session.user.role)){counts.value={};return}
  const owner=`${session.user.role}-${session.user.id}`
  inflight=(async()=>{try{const result=await http.get<Record<string,number>>('/statistics/todos');const current=loadSession();if(current&&`${current.user.role}-${current.user.id}`===owner){if(JSON.stringify(counts.value)!==JSON.stringify(result))invalidateData();counts.value=result}}catch{counts.value={}}})().finally(()=>{inflight=null});return inflight
 }
 return {counts,refresh}
})
