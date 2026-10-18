import { defineComponent } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useHousingReservation, type HousingReservationContext } from '@/common/housing/composables/useHousingReservation'
const api = vi.hoisted(() => ({ detail: vi.fn(), slots: vi.fn(), create: vi.fn(), context: vi.fn(), groupSlots: vi.fn(), groupCreate: vi.fn(), profile: vi.fn() }))
vi.mock('@/common/housing/api/housing', () => ({ getHousingDetail: api.detail, listViewingAvailableSlots: api.slots, createViewingAppointment: api.create }))
vi.mock('@/common/conversation/api/conversation', () => ({ getConversationViewingContext: api.context, listConversationViewingSlots: api.groupSlots, createConversationViewing: api.groupCreate }))
vi.mock('@/common/resident/api/resident', () => ({ getMyProfile: api.profile }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() } }))
function setup(context: HousingReservationContext) {
 let state!: ReturnType<typeof useHousingReservation>
 const created = vi.fn(), invalid = vi.fn()
 const w = mount(defineComponent({ setup() { state = useHousingReservation(context, created, invalid); return () => null } }))
 return { w, state, created, invalid }
}
beforeEach(() => {
 vi.resetAllMocks()
 api.detail.mockResolvedValue({ id: 8 }); api.context.mockResolvedValue({ id: 8 })
 api.slots.mockResolvedValue([]); api.groupSlots.mockResolvedValue([])
 api.profile.mockResolvedValue({ phone: '13800000000' })
 api.create.mockResolvedValue({ id: 11 }); api.groupCreate.mockResolvedValue({ id: 12 })
})
function select(state: ReturnType<typeof useHousingReservation>) {
 state.selectedDate.value = '2026-12-01'
 state.selectedSlots.value = [{ timeslotId: 2, appointmentDate: '2026-12-01', startTime: '09:00', endTime: '09:30', status: 'AVAILABLE', maxBookings: 2, currentBookings: 0 } as never]
}
describe('共享看房预约', () => {
 it('房源入口使用公开房源查询，提交成功交由入口导航', async () => {
  const { w, state, created } = setup({ housingId: 8 }); await flushPromises()
  expect(api.detail).toHaveBeenCalledWith(8); expect(api.context).not.toHaveBeenCalled()
  select(state); await state.handleSubmit()
  expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ housingId: 8, startTime: '09:00', endTime: '09:30' }))
  expect(created).toHaveBeenCalledWith(11); w.unmount()
 })
 it('会话入口沿专属上下文和权限接口读取已占用房源', async () => {
  const { w, state, created } = setup({ conversationId: 7 }); await flushPromises()
  expect(api.context).toHaveBeenCalledWith(7); expect(api.detail).not.toHaveBeenCalled()
  expect(api.groupSlots).toHaveBeenCalledWith(7, expect.any(Object))
  select(state); await state.handleSubmit(); expect(api.groupCreate).toHaveBeenCalledWith(7, expect.objectContaining({ housingId: 8 }))
  expect(api.create).not.toHaveBeenCalled(); expect(created).toHaveBeenCalledWith(12); w.unmount()
 })
 it('提交失败保留选区可重试，关闭重开生成独立表单', async () => {
  api.groupCreate.mockRejectedValueOnce(new Error('失败'))
  const a = setup({ conversationId: 7 }); await flushPromises(); select(a.state)
  await a.state.handleSubmit(); expect(a.created).not.toHaveBeenCalled(); expect(a.state.submitting.value).toBe(false)
  await a.state.handleSubmit(); expect(a.created).toHaveBeenCalledOnce(); a.w.unmount()
  const b = setup({ conversationId: 9 }); await flushPromises()
  expect(b.state.selectedDate.value).toBeNull(); expect(b.state.selectedSlots.value).toEqual([])
  expect(api.context).toHaveBeenLastCalledWith(9); b.w.unmount()
 })
 it('非法入口不请求接口并由入口决定返回位置', async () => {
  const { w, invalid } = setup({ housingId: NaN }); await flushPromises()
  expect(invalid).toHaveBeenCalledOnce(); expect(api.detail).not.toHaveBeenCalled(); w.unmount()
 })
})
