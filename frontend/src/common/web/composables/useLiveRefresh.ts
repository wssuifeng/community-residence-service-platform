import { computed, inject, onActivated, onBeforeUnmount, onMounted, watch, type InjectionKey, type Ref, toValue, type MaybeRefOrGetter } from 'vue'
import { dataInvalidation, scopesOverlap, type DataScope } from '@/common/web/utils/dataFreshness'
export const livePanelActive: InjectionKey<Readonly<Ref<boolean>>> = Symbol('livePanelActive')
/** 可见列表在激活、业务通知及恢复窗口焦点时补读；刷新不重置筛选，也不碰编辑表单。 */
export function useLiveRefresh(refresh: () => Promise<unknown> | void, scopes: MaybeRefOrGetter<readonly DataScope[]>) {
 const active=inject(livePanelActive,computed(()=>true))
 let disposed=false, running=false, pending=false, activated=false
 let timer:ReturnType<typeof setTimeout>|undefined
 let poll:ReturnType<typeof setInterval>|undefined
 const visible=()=>active.value && document.visibilityState!=='hidden'
 async function drain() {
  timer=undefined
  if(disposed || !visible()) return
  if(running) { pending=true; return }
  running=true
  try { await refresh() } catch { /* 列表自己的错误提示/重试负责反馈，不触发未处理拒绝。 */ }
  finally { running=false; if(pending && !disposed) {pending=false;schedule()} }
 }
 function schedule() { if(!disposed && visible()) {clearTimeout(timer);timer=setTimeout(()=>void drain(),150)} }
 watch(active,(value,old)=>{if(value && !old)schedule()})
 watch(dataInvalidation, event => { if (event.scopes.some(changed => toValue(scopes).some(scope => scopesOverlap(scope, changed)))) schedule() }, { flush: 'sync' })
 onActivated(()=>{if(activated)schedule();activated=true})
 onMounted(()=>{
  window.addEventListener('focus',schedule);document.addEventListener('visibilitychange',schedule)
  poll=setInterval(schedule,30000)
 })
 onBeforeUnmount(()=>{disposed=true;clearTimeout(timer);clearInterval(poll);window.removeEventListener('focus',schedule);document.removeEventListener('visibilitychange',schedule)})
 return { refreshWhenVisible:schedule }
}
