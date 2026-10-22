import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CommunityDeletionImpactPanel from '@/common/community/components/CommunityDeletionImpactPanel.vue'
const impact = { communityId: 7, communityName: '阳光花园', houses: 15, relations: 10, activeRelations: 4, leases: 3, activeLeases: 1, applications: 4, pendingApplications: 0, payments: 0, refunds: 0, deletable: true, blockingReason: '', confirmationVersion: 'fixture' }
describe('小区删除风险清单', () => {
  it('六类计数分组，突出有效/进行中，保留账号与资金风险分开', () => {
    const wrapper = mount(CommunityDeletionImpactPanel, { props: { impacts: [impact] } })
    expect(wrapper.findAll('dl > div')).toHaveLength(6)
    expect(wrapper.findAll('.active-risk').map(item => item.text())).toEqual(['有效 4', '进行中 1'])
    expect(wrapper.text()).toContain('阳光花园'); expect(wrapper.text()).toContain('账号本身保留')
    expect(wrapper.text()).toContain('不会自动退还任何资金'); expect(wrapper.text()).toContain('此操作无法撤回')
  })
  it('批量逐小区展示，名称按文本渲染，不能注入HTML', () => {
    const wrapper = mount(CommunityDeletionImpactPanel, { props: { impacts: [impact, { ...impact, communityId: 8, communityName: '<img src=x>' }] } })
    expect(wrapper.findAll('.community-impact')).toHaveLength(2)
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x>')
  })
})
