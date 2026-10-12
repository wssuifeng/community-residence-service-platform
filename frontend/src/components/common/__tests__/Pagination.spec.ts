import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElPagination } from 'element-plus'
import Pagination from '../Pagination.vue'

// 换行修复不能丢失分页控件或重新引入旧页码双通道回传。
describe('分页条响应式修复的交互契约', () => {
  it('页码更新只回传一次', () => {
    const wrapper = mount(Pagination, { props: { page: 1, size: 10, total: 247 }, global: { plugins: [ElementPlus] } })
    wrapper.getComponent(ElPagination).vm.$emit('update:current-page', 3)
    expect(wrapper.emitted('update:page')).toEqual([[3]])
    wrapper.unmount()
  })
  it('保留每页条数选项并回传更新', () => {
    const wrapper = mount(Pagination, { props: { page: 1, size: 10, total: 247 }, global: { plugins: [ElementPlus] } })
    const pager = wrapper.getComponent(ElPagination)
    expect(pager.props('pageSizes')).toEqual([10, 20, 50])
    expect(pager.props('layout')).toBe('total, sizes, prev, pager, next, jumper')
    pager.vm.$emit('update:page-size', 20)
    expect(wrapper.emitted('update:size')).toEqual([[20]])
    wrapper.unmount()
  })
  it('现有精简layout和页码数量覆盖保持兼容', () => {
    const wrapper = mount(Pagination, { props: { page: 1, size: 10, total: 247, layout: 'prev, pager, next', maxButtons: 5 }, global: { plugins: [ElementPlus] } })
    const pager = wrapper.getComponent(ElPagination)
    expect(pager.props('layout')).toBe('prev, pager, next')
    expect(pager.props('pagerCount')).toBe(5)
    wrapper.unmount()
  })
})
