import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import EvaluationManageView from '@/admin/evaluation/views/EvaluationManageView.vue'
import { getEvaluationStatistics, listEvaluationFollowups, listEvaluations, listUnsatisfiedEvaluations } from '@/common/evaluation/api/evaluation'
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }))
vi.mock('@/common/evaluation/api/evaluation', () => ({ getEvaluationStatistics: vi.fn(), listEvaluationFollowups: vi.fn(), listEvaluations: vi.fn(), listUnsatisfiedEvaluations: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }))
const empty = { records: [], total: 0, page: 1, size: 10, pages: 0 }
const entry = { id: 1, workOrderId: 2, workOrderNo: 'WO2', residentName: '真实居民', rating: 2, isSatisfied: 0, content: '评价内容', createdAt: '2026-10-01' }
async function open() {
 const w=mount(EvaluationManageView,{global:{stubs:{AdminPageHeader:true,AdminStatCard:{template:'<div><slot name="extra" /></div>'},StatusTag:true,Pagination:true,FollowupDrawer:true,ElButton:{template:'<button><slot /></button>'},ElRate:true,ElLink:true,ElEmpty:{props:['description'],template:'<p data-empty>{{ description }}</p>'}}}})
 await flushPromises();return w
}
describe('评价加载状态不伪装业务空态',()=>{
 beforeEach(()=>{vi.clearAllMocks();vi.mocked(getEvaluationStatistics).mockResolvedValue({total:0,averageRating:0,satisfiedRate:0,ratingDistribution:{}});vi.mocked(listEvaluations).mockResolvedValue(empty);vi.mocked(listUnsatisfiedEvaluations).mockResolvedValue(empty);vi.mocked(listEvaluationFollowups).mockResolvedValue([])})
 it('列表失败可重试且没有空态',async()=>{vi.mocked(listEvaluations).mockRejectedValueOnce(new Error('网络失败'));const w=await open();expect(w.get('.load-error').text()).toContain('评价列表加载失败');expect(w.find('[data-empty]').exists()).toBe(false);expect(w.findComponent({name:'Pagination'}).exists()).toBe(false);vi.mocked(listEvaluations).mockResolvedValueOnce({...empty,records:[entry]} as any);await w.get('.load-error button').trigger('click');await flushPromises();expect(w.find('.load-error').exists()).toBe(false);expect(w.text()).toContain('真实居民');w.unmount()})
 it('成功零条才显示空态',async()=>{const w=await open();expect(w.get('[data-empty]').text()).toBe('暂无评价');w.unmount()})
 it('统计失败独立提示',async()=>{vi.mocked(getEvaluationStatistics).mockRejectedValueOnce(new Error('统计失败'));const w=await open();expect(w.get('.rating-count').text()).toBe('评价统计加载失败');w.unmount()})
 it('跟进失败不归零或误分待跟进',async()=>{vi.mocked(listEvaluations).mockResolvedValue({...empty,records:[entry]} as any);vi.mocked(listUnsatisfiedEvaluations).mockResolvedValue({...empty,records:[entry],pages:1} as any);vi.mocked(listEvaluationFollowups).mockRejectedValue(new Error('跟进失败'));const w=await open();expect(w.text()).toContain('跟进状态加载失败');expect(w.text()).not.toContain('发起跟进');await w.findAll('[role="tab"]')[1]!.trigger('click');await flushPromises();expect(w.find('[data-empty]').exists()).toBe(false);expect(w.find('.load-error').exists()).toBe(true);w.unmount()})
})
