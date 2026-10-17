import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ChatMessageList from '../ChatMessageList.vue'

const cleanup: Array<() => void> = []
afterEach(() => {
  cleanup.splice(0).forEach(unmount => unmount())
  vi.useRealTimers()
})

describe('会话时间展示', () => {
  it.each([
    ['2026-10-17T11:05:00', '11:05'],
    ['2026-10-16T11:05:00', '10-16 11:05'],
    ['2025-10-17T11:05:00', '2025-10-17 11:05'],
    ['unknown-time', 'unknown-time']
  ])('精简时间 %s，完整值仍可查看', (createdAt, expected) => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-17T12:00:00'))
    const wrapper = mount(ChatMessageList, { props: { messages: [
      { id: 1, content: '收到', senderLabel: '居民', mine: false, createdAt }
    ] } })
    cleanup.push(() => wrapper.unmount())
    const time = wrapper.get('time')
    expect(time.text()).toBe(expected)
    expect(time.attributes('datetime')).toBe(createdAt)
    expect(time.attributes('title')).toBe(createdAt === 'unknown-time' ? createdAt : createdAt.slice(0, 16).replace('T', ' '))
  })
})
