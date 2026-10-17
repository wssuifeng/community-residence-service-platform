import { defineComponent, reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useConversationInbox } from '../useConversationInbox'
const api = vi.hoisted(() => ({ list: vi.fn() }))
vi.mock('@/api/conversationInbox', () => ({ listConversationInbox: api.list }))
vi.mock('../useLiveRefresh', () => ({ useLiveRefresh: () => ({ refreshWhenVisible: vi.fn() }) }))
const user = reactive({ user: { id: 1, role: 'ADMIN' } })
vi.mock('@/store/user', () => ({ useUserStore: () => user }))
const row = (id: number, source = 'CONVERSATION') => ({ id, source, type: 'DIRECT', title: `会话${id}`, unreadCount: 2 })
const result = (records = [row(1)], total = records.length, page = 1) => ({ records, total, page, size: 30, pages: Math.ceil(total / 30) })
let wrapper: ReturnType<typeof mount>, state: ReturnType<typeof useConversationInbox>
function setup() { wrapper = mount(defineComponent({ setup() { state = useConversationInbox(); return {} }, template: '<div />' })) }
beforeEach(() => { vi.useFakeTimers(); api.list.mockResolvedValue(result()) })
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); vi.clearAllMocks() })
describe('统一会话服务端筛选与请求隔离', () => {
 it('分类、搜索和未读先传后端，不筛已加载的第一页', async () => {
  setup(); state.type.value = 'FEEDBACK'; state.keyword.value = '房屋'; state.unreadOnly.value = true
  await vi.advanceTimersByTimeAsync(260); await flushPromises()
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 30, type: 'FEEDBACK', keyword: '房屋', unreadOnly: true })
 })
 it('来源内同号不是同一会话，下一页去重不丢反馈', async () => {
  setup(); api.list.mockResolvedValueOnce(result([row(1), row(1, 'FEEDBACK')], 31))
  await state.load(); api.list.mockResolvedValueOnce(result([row(1), row(2)], 31, 2)); await state.load(true)
  expect(state.items.value.map(item => `${item.source}:${item.id}`)).toEqual(['CONVERSATION:1','FEEDBACK:1','CONVERSATION:2'])
 })
 it('旧搜索响应不能覆盖新搜索', async () => {
  setup(); let finish: (value: ReturnType<typeof result>) => void = () => undefined
  api.list.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const pending = state.load(); state.keyword.value = '新搜索'; await vi.advanceTimersByTimeAsync(260)
  api.list.mockResolvedValueOnce(result([row(3)])); await state.load(); finish(result([row(99)])); await pending
  expect(state.items.value.map(item => item.id)).toEqual([3])
 })
 it('刷新保留已加载页范围，失败保留上次结果而非假清空', async () => {
  setup(); api.list.mockResolvedValue(result([row(1)], 60)); await state.load(); await state.load(true)
  api.list.mockRejectedValueOnce(new Error('离线')); await state.load()
  expect(state.error.value).toBe('离线'); expect(state.items.value).toHaveLength(1)
  api.list.mockResolvedValue(result([row(2)], 1)); await state.load(); expect(state.error.value).toBe('')
 })
})
