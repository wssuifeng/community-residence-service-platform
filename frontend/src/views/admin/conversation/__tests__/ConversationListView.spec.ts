import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import ConversationListView from '../ConversationListView.vue'
import { getFakeBackend, installFakeBackend } from '@/test/fakeBackend'

/**
 * ③ 管理端会话中心的操作界面可达性：
 * - 无会话时给出**来源说明**（会话由入住申请审核通过 / 工单受理自动建立），而不是一句
 *   「暂无会话」让人无处下手；
 * - 会话类型徽标覆盖 R74 的 `WORK_ORDER_GROUP`（工单临时群聊）；
 * - DEF-076 接入：管理端可**代居民发起直通会话**（选社区 + 选居民，幂等提示）。
 */
vi.mock('@/api/conversation', async () => (await import('@/test/fakeBackend')).fakeApi('conversation'))
vi.mock('@/api/community', async () => (await import('@/test/fakeBackend')).fakeApi('community'))
vi.mock('@/api/resident', async () => (await import('@/test/fakeBackend')).fakeApi('resident'))

const backend = () => getFakeBackend()

function buildRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/conversations', component: ConversationListView },
      { path: '/admin/conversations/:id', component: ConversationListView }
    ]
  })
}

async function mountCenter(router: Router): Promise<VueWrapper> {
  await router.push('/admin/conversations')
  await router.isReady()
  const wrapper = mount(ConversationListView, {
    global: { plugins: [createPinia(), router, ElementPlus] },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

/** 打开弹窗并选定社区/居民（el-select 以 v-model 事件驱动，jsdom 下最稳妥） */
async function pickAndSubmit(wrapper: VueWrapper, communityId: number, residentId: number) {
  const open = wrapper.findAll('button').find((item) => item.text().trim() === '发起直通会话')
  expect(open, '未提供「发起直通会话」入口').toBeTruthy()
  await open?.trigger('click')
  await flushPromises()

  const selects = wrapper.findAllComponents({ name: 'ElSelect' })
  expect(selects.length).toBeGreaterThanOrEqual(2)
  selects[0].vm.$emit('update:modelValue', communityId)
  selects[1].vm.$emit('update:modelValue', residentId)
  await flushPromises()

  const submit = Array.from(document.querySelectorAll('.el-dialog__footer button')).find(
    (item) => item.textContent?.trim() === '发起会话'
  ) as HTMLButtonElement | undefined
  expect(submit, '弹窗缺少「发起会话」按钮').toBeTruthy()
  submit?.click()
  await flushPromises()
}

/** 打开弹窗后切到指定类型筛选（模拟用户先过滤列表再发起直通） */
async function switchTypeFilter(
  wrapper: VueWrapper,
  value: '' | 'LEASE_GROUP' | 'VIEWING_GROUP' | 'DIRECT'
): Promise<void> {
  const radio = wrapper.findComponent({ name: 'ElRadioGroup' })
  radio.vm.$emit('update:modelValue', value)
  await flushPromises()
}

beforeEach(() => {
  installFakeBackend({ role: 'ADMIN', id: 1 })
})

afterEach(() => {
  document.body.innerHTML = ''
  sessionStorage.clear()
})

describe('管理端会话中心 · ③ 会话操作界面', () => {
  it('无会话时给出会话来源说明与后端能力边界，而非仅「暂无会话」', async () => {
    const router = buildRouter()
    const wrapper = await mountCenter(router)

    const text = wrapper.text()
    expect(text).toContain('暂无会话')
    expect(text).toContain('入住申请审核通过')
    expect(text).toContain('工单受理')
    expect(text).toContain('居民可从「消息中心」发起与管理员的直通会话')
  })

  it('工单临时群聊（WORK_ORDER_GROUP）在列表中按「工单群聊」呈现', async () => {
    /* 管理端受理工单后自动建群：以参与者身份（受理管理员）可见该会话 */
    backend().state.conversations.push({
      id: 1,
      type: 'WORK_ORDER_GROUP',
      relatedId: 1,
      title: '工单沟通·WO202609220003',
      status: 'ACTIVE',
      participantIds: [1, 1],
      participantRefs: [
        { id: 1, space: 'RESIDENT', name: '演示居民', role: 'RESIDENT' },
        { id: 1, space: 'USER', name: '社区管理员', role: 'ADMIN' }
      ],
      createdAt: '2026-09-22T09:00:00'
    })

    const router = buildRouter()
    const wrapper = await mountCenter(router)

    const text = wrapper.text()
    expect(text).toContain('工单群聊')
    expect(text).toContain('工单沟通·WO202609220003')
  })
})

describe('管理端会话中心 · DEF-076 代居民发起直通会话', () => {
  it('管理端可代居民发起直通会话，并在会话中心打开该会话', async () => {
    backend().state.residents.push({ id: 2, name: '李四', phone: '13800000002' } as never)

    const router = buildRouter()
    const wrapper = await mountCenter(router)
    await pickAndSubmit(wrapper, 1, 2)

    const created = backend().state.conversations.find((item) => item.type === 'DIRECT')
    expect(created, '未创建直通会话').toBeTruthy()
    expect(created?.communityId).toBe(1)
    expect(
      created?.participantRefs?.some((ref) => ref.space === 'RESIDENT' && ref.id === 2)
    ).toBe(true)
    /* 会话已被选中并在列表中可见 */
    expect(wrapper.text()).toContain(created?.title ?? '')
    expect(document.body.textContent).toContain('直通会话已建立')
  })

  it('重复发起同一居民（幂等）时提示「已存在与该居民的会话，已为你打开」', async () => {
    backend().state.residents.push({ id: 2, name: '李四', phone: '13800000002' } as never)

    const router = buildRouter()
    const wrapper = await mountCenter(router)
    await pickAndSubmit(wrapper, 1, 2)
    /* 第一次：新建 */
    expect(backend().state.conversations.filter((item) => item.type === 'DIRECT')).toHaveLength(1)

    /* 第二次：列表已含该会话 → 命中既有 */
    await pickAndSubmit(wrapper, 1, 2)
    expect(backend().state.conversations.filter((item) => item.type === 'DIRECT')).toHaveLength(1)
    expect(document.body.textContent).toContain('已存在与该居民的会话，已为你打开')
  })

  it('未选居民时本地拦下并提示「请指定直通会话的居民」（与后端 400 同文案）', async () => {
    const router = buildRouter()
    const wrapper = await mountCenter(router)

    const open = wrapper.findAll('button').find((item) => item.text().trim() === '发起直通会话')
    await open?.trigger('click')
    await flushPromises()
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    selects[0].vm.$emit('update:modelValue', 1)
    await flushPromises()

    const submit = Array.from(document.querySelectorAll('.el-dialog__footer button')).find(
      (item) => item.textContent?.trim() === '发起会话'
    ) as HTMLButtonElement | undefined
    submit?.click()
    await flushPromises()

    expect(document.body.textContent).toContain('请指定直通会话的居民')
    expect(backend().state.conversations.filter((item) => item.type === 'DIRECT')).toHaveLength(0)
  })
})

/**
 * OCR DEF-122 修复轮（管理端会话中心）：
 * - 类型筛选写回：单选组 emit 值必须落回 typeFilter（原实现忽略 emit 参数，筛选永不生效）；
 * - 筛选复位：非 DIRECT 筛选下发起直通成功后复位「全部」，新建会话可见且被选中
 *   （否则被过滤出去 → 右侧空白还弹「已建立」误报）；
 * - 居民选择器按已选社区本地过滤（真实后端契约无 communityId 字段时不过滤，
 *   硬校验仍在后端 openDirect），切换社区后已选居民被滤出即清空；
 * - 弹窗选项加载失败不再静默空数组：console.error 留痕 + 轻提示。
 */
describe('管理端会话中心 · DEF-122 修复轮', () => {
  /** 假后端会话模块类型（listConversations 契约：PageParams，未实现 type 参数） */
  type FakeConversationModule = ReturnType<typeof getFakeBackend>['conversation']

  /** 真实后端按 type 过滤会话列表，假后端未实现该参数——用例内临时补齐同语义（结束后还原） */
  let originalListConversations: FakeConversationModule['listConversations'] | null = null

  function patchListConversationsWithTypeFilter(): void {
    const impl = backend().conversation
    originalListConversations = impl.listConversations
    impl.listConversations = async (params) => {
      const result = await originalListConversations!(params)
      /* 入参按真实后端 IConversationQuery 契约叠加 type（load() 实际在传） */
      const type = (params as { page?: number; size?: number } & { type?: string }).type
      const records = type
        ? result.records.filter((item) => item.type === type)
        : result.records
      return { ...result, records, total: records.length }
    }
  }

  afterEach(() => {
    if (originalListConversations) {
      backend().conversation.listConversations = originalListConversations
      originalListConversations = null
    }
  })

  /** 管理端（ADMIN id=1，绑定社区 1）可见的种子会话 */
  function seedConversation(seed: {
    id: number
    type: 'LEASE_GROUP' | 'VIEWING_GROUP' | 'WORK_ORDER_GROUP'
    title: string
  }): void {
    backend().state.conversations.push({
      id: seed.id,
      type: seed.type,
      relatedId: 1,
      title: seed.title,
      status: 'ACTIVE',
      participantIds: [1, 1],
      participantRefs: [
        { id: 1, space: 'RESIDENT', name: '演示居民', role: 'RESIDENT' },
        { id: 1, space: 'USER', name: '社区管理员', role: 'ADMIN' }
      ],
      createdAt: '2026-09-22T09:00:00'
    } as never)
  }

  it('类型筛选按单选值生效（emit 值写回 typeFilter）：LEASE_GROUP 下仅显示租约群聊', async () => {
    seedConversation({ id: 10, type: 'LEASE_GROUP', title: '租约群聊·101' })
    seedConversation({ id: 11, type: 'WORK_ORDER_GROUP', title: '工单群聊·WO1' })
    patchListConversationsWithTypeFilter()

    const router = buildRouter()
    const wrapper = await mountCenter(router)
    expect(wrapper.text()).toContain('租约群聊·101')
    expect(wrapper.text()).toContain('工单群聊·WO1')

    const radio = wrapper.findComponent({ name: 'ElRadioGroup' })
    radio.vm.$emit('update:modelValue', 'LEASE_GROUP')
    await flushPromises()
    expect(radio.props('modelValue')).toBe('LEASE_GROUP')
    expect(wrapper.text()).toContain('租约群聊·101')
    expect(wrapper.text()).not.toContain('工单群聊·WO1')

    /* 切回「全部」两类都恢复 */
    radio.vm.$emit('update:modelValue', '')
    await flushPromises()
    expect(wrapper.text()).toContain('工单群聊·WO1')
  })

  it('LEASE_GROUP 筛选下发起直通：复位筛选为「全部」，新会话可见且被选中（右侧非空白）', async () => {
    seedConversation({ id: 10, type: 'LEASE_GROUP', title: '租约群聊·101' })
    backend().state.residents.push({
      id: 2,
      name: '李四',
      realName: '李四',
      phone: '13800000002',
      communityId: 1,
      status: 'ACTIVE'
    } as never)
    patchListConversationsWithTypeFilter()

    const router = buildRouter()
    const wrapper = await mountCenter(router)
    await switchTypeFilter(wrapper, 'LEASE_GROUP')
    /* 列表项只剩租约群聊（DIRECT 会话尚未创建，列表不出现「居民直通」类会话） */
    const listItems = wrapper.findAll('.conv-item')
    expect(listItems.length).toBe(1)
    expect(listItems[0].text()).toContain('租约群聊·101')

    await pickAndSubmit(wrapper, 1, 2)
    const created = backend().state.conversations.find((item) => item.type === 'DIRECT')
    expect(created, '未创建直通会话').toBeTruthy()

    /* 修复点：筛选复位「全部」→ 新建 DIRECT 会话进入列表并被选中 */
    expect(wrapper.text()).toContain(created?.title ?? '')
    const head = wrapper.find('.panel-head')
    expect(head.exists()).toBe(true)
    expect(head.text()).toContain(created?.title ?? '')
    expect(document.body.textContent).toContain('直通会话已建立')
  })

  it('居民选择器按已选社区过滤：他社区居民被滤出，切换社区后已选居民清空', async () => {
    backend().state.communities.push({ id: 2, name: '第二社区' } as never)
    backend().state.residents.push({
      id: 3,
      name: '王五',
      realName: '王五',
      phone: '13800000003',
      communityId: 2,
      status: 'ACTIVE'
    } as never)

    const router = buildRouter()
    const wrapper = await mountCenter(router)
    const open = wrapper.findAll('button').find((item) => item.text().trim() === '发起直通会话')
    await open?.trigger('click')
    await flushPromises()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    /* 选社区 1：社区 1 的演示居民可选，社区 2 的王五被滤出 */
    selects[0].vm.$emit('update:modelValue', 1)
    await flushPromises()
    const optionTexts = selects[1]
      .findAllComponents({ name: 'ElOption' })
      .map((option) => option.text())
    expect(optionTexts.some((text) => text.includes('13800000001'))).toBe(true)
    expect(optionTexts.some((text) => text.includes('13800000003'))).toBe(false)

    /* 已选居民 1 后切到社区 2：居民 1 被滤出 → 选择清空，提交被本地拦下（与后端 400 同文案） */
    selects[1].vm.$emit('update:modelValue', 1)
    await flushPromises()
    selects[0].vm.$emit('update:modelValue', 2)
    await flushPromises()
    const submit = Array.from(document.querySelectorAll('.el-dialog__footer button')).find(
      (item) => item.textContent?.trim() === '发起会话'
    ) as HTMLButtonElement | undefined
    submit?.click()
    await flushPromises()
    expect(document.body.textContent).toContain('请指定直通会话的居民')
    expect(backend().state.conversations.filter((item) => item.type === 'DIRECT')).toHaveLength(0)
  })

  it('弹窗选项加载失败不再静默：console.error 留痕并给出轻提示', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const impl = backend().community
    const original = impl.getCommunityList
    impl.getCommunityList = async () => {
      throw new Error('服务暂不可用')
    }
    try {
      const router = buildRouter()
      const wrapper = await mountCenter(router)
      const open = wrapper.findAll('button').find((item) => item.text().trim() === '发起直通会话')
      await open?.trigger('click')
      await flushPromises()

      expect(errorSpy).toHaveBeenCalled()
      expect(document.body.textContent).toContain('社区/居民选项加载失败')
    } finally {
      impl.getCommunityList = original
      errorSpy.mockRestore()
    }
  })
})
