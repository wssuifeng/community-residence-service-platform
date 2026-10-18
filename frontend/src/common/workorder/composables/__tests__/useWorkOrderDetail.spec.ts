import { defineComponent, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useWorkOrderDetail } from '../useWorkOrderDetail'
const api = vi.hoisted(() => ({ detail: vi.fn(), timeline: vi.fn(), files: vi.fn() }))
vi.mock('@/common/workorder/api/workorder', () => ({ getWorkOrder: api.detail, getWorkOrderTimeline: api.timeline, listWorkOrderAttachments: api.files }))
function setup() {
 const id = ref(1); let state!: ReturnType<typeof useWorkOrderDetail>
 const w = mount(defineComponent({ setup() { state = useWorkOrderDetail(id); return () => null } }), { global: { plugins: [createPinia()] } })
 return { id, state, w }
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
beforeEach(() => { vi.resetAllMocks(); api.detail.mockResolvedValue({ id: 1, status: 'PENDING' }); api.timeline.mockResolvedValue([]); api.files.mockResolvedValue([]) })
describe('工单主辅加载', () => {
 it('辅助失败保留主详情，单独重试只读取对应辅助信息', async () => {
  api.timeline.mockRejectedValueOnce(new Error('时间线不可用'))
  const { state, w } = setup(); await flushPromises()
  expect(state.order.value?.id).toBe(1); expect(state.loadError.value).toBe(''); expect(state.timelineError.value).toBe('时间线不可用')
  await state.fetchTimeline(); expect(state.timelineError.value).toBe(''); expect(api.detail).toHaveBeenCalledOnce(); expect(api.files).toHaveBeenCalledOnce(); w.unmount()
 })
 it('慢辅助请求不延长主详情加载状态', async () => {
  const slow = deferred<never[]>(); api.files.mockReturnValue(slow.promise)
  const { state, w } = setup(); await flushPromises()
  expect(state.loading.value).toBe(false); expect(state.attachmentsLoading.value).toBe(true); expect(state.order.value).not.toBeNull()
  slow.resolve([]); await flushPromises(); w.unmount()
 })
 it('快速切换编号时旧主响应不覆盖新工单', async () => {
  const slow = deferred<{ id: number }>(); api.detail.mockReturnValueOnce(slow.promise).mockResolvedValueOnce({ id: 2 })
  const { id, state, w } = setup(); id.value = 2; await flushPromises()
  slow.resolve({ id: 1 }); await flushPromises(); expect(state.order.value?.id).toBe(2); expect(api.timeline).toHaveBeenCalledWith(2); w.unmount()
 })
 it('旧辅助响应晚到不覆盖新工单，卸载后不回填', async () => {
  const slow = deferred<never[]>(); api.timeline.mockReturnValueOnce(slow.promise)
  const { id, state, w } = setup(); await flushPromises(); id.value = 2; api.detail.mockResolvedValue({ id: 2 }); await flushPromises()
  slow.resolve([{ id: 99 } as never]); await flushPromises(); expect(state.timeline.value).toEqual([])
  const late = deferred<never[]>(); api.files.mockReturnValueOnce(late.promise); const pending = state.fetchAttachments(); w.unmount(); late.resolve([{ id: 4 } as never]); await pending; expect(state.attachments.value).toEqual([])
 })
 it('主详情失败可重试，失败时不查询辅助数据', async () => {
  api.detail.mockRejectedValueOnce(new Error('无权限'))
  const { state, w } = setup(); await flushPromises(); expect(state.order.value).toBeNull(); expect(state.loadError.value).toBe('无权限'); expect(api.timeline).not.toHaveBeenCalled()
  await state.fetchDetail(); await flushPromises(); expect(state.order.value?.id).toBe(1); expect(state.loadError.value).toBe(''); w.unmount()
 })
})
