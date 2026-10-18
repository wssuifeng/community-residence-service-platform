import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LivePanel from '@/common/web/components/LivePanel.vue'
import { useLiveRefresh } from '@/common/web/composables/useLiveRefresh'
import { invalidateData } from '@/common/web/utils/dataFreshness'
describe('可见业务列表补读',()=>{
 beforeEach(()=>vi.useFakeTimers());afterEach(()=>vi.useRealTimers())
 function setup(refresh:()=>Promise<unknown>|void) {
  const active=ref(false)
  const child=defineComponent({setup(){useLiveRefresh(refresh, ['workorder']);return()=>h('p','列表')}})
  const w=mount(defineComponent({setup(){return()=>h(LivePanel,{active:active.value},()=>h(child))}}))
  return {w,active}
 }
 async function tick(){await flushPromises();await vi.advanceTimersByTimeAsync(200);await flushPromises()}
 it('隐藏不刷新，切入补读，离开再进入仍补读',async()=>{
  const reload=vi.fn();const {w,active}=setup(reload)
  invalidateData('workorder');await tick();expect(reload).not.toHaveBeenCalled()
  active.value=true;await tick();expect(reload).toHaveBeenCalledTimes(1)
  active.value=false;await tick();invalidateData('workorder');await tick();expect(reload).toHaveBeenCalledTimes(1)
  active.value=true;await tick();expect(reload).toHaveBeenCalledTimes(2);w.unmount()
 })
 it('连续通知合并、同一列表请求串行，卸载后不刷新',async()=>{
  let finish!:()=>void;const reload=vi.fn().mockImplementationOnce(()=>new Promise<void>(r=>{finish=r})).mockResolvedValue(undefined)
  const {w,active}=setup(reload);active.value=true;await tick()
  invalidateData('workorder');invalidateData('workorder');await tick();expect(reload).toHaveBeenCalledTimes(1)
  finish();await tick();expect(reload).toHaveBeenCalledTimes(2)
  w.unmount();invalidateData('workorder');await tick();expect(reload).toHaveBeenCalledTimes(2)
 })
 it('无关业务与消息中心已读不触发当前工单刷新',async()=>{
  const reload=vi.fn();const {w,active}=setup(reload);active.value=true;await tick();reload.mockClear()
  invalidateData('notice');invalidateData('messaging');await tick();expect(reload).not.toHaveBeenCalled()
  invalidateData('workorder');await tick();expect(reload).toHaveBeenCalledOnce();w.unmount()
 })
 it('窗口回到前台主动补读，轮询兜底无需整页刷新',async()=>{
  const reload=vi.fn();const {w,active}=setup(reload);active.value=true;await tick();reload.mockClear()
  window.dispatchEvent(new Event('focus'));await tick();expect(reload).toHaveBeenCalledOnce()
  await vi.advanceTimersByTimeAsync(30000);expect(reload).toHaveBeenCalledTimes(2);w.unmount()
 })
})
