import { defineComponent } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ViewingsWorkbenchView from '@/staff/housing/views/ViewingsWorkbenchView.vue'

const mocks = vi.hoisted(() => ({
  listViewingAppointments: vi.fn(),
  completeViewingAppointment: vi.fn(),
  violateViewingAppointment: vi.fn(),
  getConversation: vi.fn()
}))

vi.mock('@/common/housing/api/housing', () => ({
  listViewingAppointments: mocks.listViewingAppointments,
  completeViewingAppointment: mocks.completeViewingAppointment,
  violateViewingAppointment: mocks.violateViewingAppointment
}))

vi.mock('@/common/conversation/api/conversation', () => ({
  getConversation: mocks.getConversation
}))

vi.mock('@/common/conversation/components/ConversationChat.vue', () => ({
  default: defineComponent({
    name: 'ConversationChat',
    props: { conversationId: { type: Number, required: true }, closed: Boolean },
    template: '<div data-testid="conversation-chat">群聊 {{ conversationId }}</div>'
  })
}))

vi.mock('@/common/conversation/components/AppointmentChat.vue', () => ({
  default: defineComponent({
    name: 'AppointmentChat',
    props: { appointmentId: { type: Number, required: true } },
    template: '<div data-testid="appointment-chat">旧带看单聊 {{ appointmentId }}</div>'
  })
}))

const appointment = {
  id: 99,
  userId: 1,
  housingId: 1,
  housingTitle: '测试房源',
  communityId: 2,
  appointmentDate: '2026-10-11',
  startTime: '11:00:00',
  endTime: '12:00:00',
  visitorName: '张伟',
  contactPhone: '13905710101',
  status: 'RESERVED',
  remark: null,
  createdAt: '2026-10-08T23:00:00',
  appointmentNumber: '',
  visitorCount: 1,
  assigneeId: 3,
  assigneeName: '刘伟民',
  conversationId: 88
}

beforeEach(() => {
  mocks.listViewingAppointments.mockResolvedValue({ records: [appointment], total: 1 })
  mocks.getConversation.mockResolvedValue({ id: 88, status: 'ACTIVE' })
})

afterEach(() => {
  vi.clearAllMocks()
  document.body.innerHTML = ''
})

describe('服务人员我的带看 · R63 看房群聊', () => {
  it('分配带看人后使用预约关联的多方会话，而不是 R59 旧单聊', async () => {
    const wrapper = mount(ViewingsWorkbenchView, {
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await flushPromises()

    const row = wrapper.find('.el-table__body-wrapper .el-table__row')
    expect(row.exists()).toBe(true)
    const clickRow = row.element as HTMLTableRowElement
    clickRow.click()
    await flushPromises()
    await flushPromises()

    expect(wrapper.find('[data-testid="conversation-chat"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="conversation-chat"]').text()).toContain('88')
    expect(wrapper.find('[data-testid="appointment-chat"]').exists()).toBe(false)
    expect(mocks.getConversation).toHaveBeenCalledWith(88)
    expect(wrapper.text()).toContain('登记完成')
    expect(wrapper.text()).not.toContain('标记违约')
    expect(wrapper.text()).not.toContain('冻结账号')
    wrapper.unmount()
  })
})


