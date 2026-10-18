import { defineComponent, reactive, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { useChatChannel } from '@/common/conversation/composables/useChatChannel'
vi.mock('@/common/messaging/utils/websocket', () => ({ subscribe: vi.fn(() => vi.fn()), isWsConnected: () => false }))
const user = reactive({ user: { id: 80, role: 'RESIDENT' } })
vi.mock('@/common/auth/store/user', () => ({ useUserStore: () => user }))
let wrapper: ReturnType<typeof mount>
const row = (id: number) => ({ id, content: `消息${id}`, createdAt: '2026-10-17T10:00:00' })
beforeEach(() => { vi.useFakeTimers(); user.user.id++ })
afterEach(() => { wrapper?.unmount(); vi.useRealTimers(); vi.clearAllMocks() })
function setup(load: (id: number, before?: number, after?: number) => Promise<ReturnType<typeof row>[]>, initialWindow?: (id: number) => Promise<import('@/common/conversation/api/conversationInbox').InboxMessageWindow<ReturnType<typeof row>>>) {
 const id = ref(1), closed = ref(false), send = vi.fn(async () => row(99))
 let state: ReturnType<typeof useChatChannel<ReturnType<typeof row>>>
 wrapper = mount(defineComponent({ setup() { state = useChatChannel({ id: () => id.value, closed: () => closed.value, load, window: load, initialWindow, send, image: send, topic: n => `/test/${n}`, eventType: 'MESSAGE' }); return {} }, template: '<div />' }))
 return { id, closed, send, get state() { return state! } }
}
it('切换时旧窗口响应不能覆盖新会话', async () => {
 let finish: (rows: ReturnType<typeof row>[]) => void = () => undefined
 const load = vi.fn().mockImplementationOnce(() => new Promise(resolve => { finish = resolve })).mockResolvedValue([row(2)])
 const c = setup(load); c.id.value = 2; await flushPromises(); finish([row(1)]); await flushPromises()
 expect(c.state.messages.value.map(m => m.id)).toEqual([2])
})
it('草稿按账号与来源分开，切换再回来不丢', async () => {
 const c = setup(vi.fn().mockResolvedValue([])); await flushPromises(); c.state.text.value = '尚未发送'
 c.id.value = 2; await flushPromises(); expect(c.state.text.value).toBe('')
 c.id.value = 1; await flushPromises(); expect(c.state.text.value).toBe('尚未发送')
 user.user.id++; await flushPromises(); expect(c.state.text.value).toBe('')
})
it('历史按最早ID加载，增量补读不用每次全量读取', async () => {
 const load = vi.fn().mockResolvedValueOnce(Array.from({ length: 40 }, (_, n) => row(n + 41))).mockResolvedValueOnce([row(40)]).mockResolvedValueOnce([row(81)])
 const c = setup(load); await flushPromises(); await c.state.loadOlder(); expect(load).toHaveBeenLastCalledWith(1, 41)
 await c.state.reload(); expect(load).toHaveBeenLastCalledWith(1, undefined, 80)
 expect(c.state.messages.value[0].id).toBe(40); expect(c.state.messages.value.at(-1)?.id).toBe(81)
})
it('关闭态保留历史，不调用发送或启动轮询', async () => {
 const load = vi.fn().mockResolvedValue([row(1)]); const c = setup(load); c.closed.value = true; await flushPromises()
 c.state.text.value = '不可发送'; await c.state.sendText(); expect(c.send).not.toHaveBeenCalled()
 const count = load.mock.calls.length; await vi.advanceTimersByTimeAsync(16000); expect(load).toHaveBeenCalledTimes(count)
})
it('读取失败提供重试，不冒充空会话成功', async () => {
 const load = vi.fn().mockRejectedValueOnce(new Error('加载失败')).mockResolvedValue([row(1)]); const c = setup(load); await flushPromises()
 expect(c.state.error.value).toBe('加载失败'); await c.state.reload(); expect(c.state.error.value).toBe(''); expect(c.state.messages.value).toHaveLength(1)
})

it('首次从未读窗口开始，给消息区明确定位而不是跳到最后', async () => {
 const load = vi.fn().mockResolvedValue([]), initial = vi.fn().mockResolvedValue({ records: [row(51),row(52)], startAtUnread: true, firstUnreadMessageId: 51, hasOlder: true, hasNewer: true })
 const c = setup(load, initial); await flushPromises()
 expect(initial).toHaveBeenCalledWith(1); expect(load).not.toHaveBeenCalled(); expect(c.state.initialAnchor.value).toBe(51); expect(c.state.hasOlder.value).toBe(true)
})
