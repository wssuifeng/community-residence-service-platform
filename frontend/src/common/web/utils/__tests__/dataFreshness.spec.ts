import { describe, expect, it } from 'vitest'
import { dataInvalidation, invalidateMutation, invalidateNotification, mutationScopes, scopesOverlap } from '../dataFreshness'
describe('业务及对象失效范围', () => {
 it('对象不同不失效，列表通配只覆盖同域', () => {
  expect(scopesOverlap({domain:'workorder',id:1},{domain:'workorder',id:2})).toBe(false)
  expect(scopesOverlap('workorder',{domain:'workorder',id:2})).toBe(true)
  expect(scopesOverlap('notice','workorder')).toBe(false)
 })
 it('会话已读不触发工单、房源或系统通知重读', () => {
  expect(mutationScopes('/conversations/8/read')).toEqual([{domain:'conversation',id:'8'}])
  expect(mutationScopes('/notifications/8/read')).toEqual([{domain:'messaging',id:'8'}])
 })
 it('业务写入关联必要域，上传及认证不广播', () => {
  expect(mutationScopes('/api/v1/work-orders/9/assign')).toContainEqual({domain:'workorder',id:'9'})
  expect(mutationScopes('/upload')).toEqual([]); expect(mutationScopes('/auth/login')).toEqual([])
  expect(mutationScopes('/notices/1/views')).toEqual([])
 })
 it('通知按真实来源映射，支付单ID不冒充租约ID', () => {
  invalidateNotification({sourceType:'LEASE_PAYMENT',sourceId:9})
  expect(dataInvalidation.value.scopes).toContainEqual({domain:'lease',id:undefined})
  invalidateNotification({sourceType:'WORK_ORDER',sourceId:4})
  expect(dataInvalidation.value.scopes).toContainEqual({domain:'workorder',id:4})
 })
 it('没有范围不广播，连续不同业务事件保留各自归属', () => {
  const before=dataInvalidation.value.revision; invalidateMutation('/upload');expect(dataInvalidation.value.revision).toBe(before)
  invalidateMutation('/feedbacks/2/messages');expect(dataInvalidation.value.scopes).toContainEqual({domain:'feedback',id:'2'})
 })
})
