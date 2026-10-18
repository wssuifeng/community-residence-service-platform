import { describe, expect, it } from 'vitest'
import { readFileSync, writeFileSync } from 'node:fs'
import type { RouteRecordRaw } from 'vue-router'
import admin from '@/router/admin'
import resident from '@/router/resident'
import staff from '@/router/staff'
import guest from '@/router/guest'

const fixture = `${process.cwd()}/src/__tests__/fixtures/portal-routes.json`
function contracts(routes: RouteRecordRaw[]): unknown[] {
  return routes.map(route => ({
    path: route.path, name: route.name, meta: route.meta, alias: route.alias,
    redirect: typeof route.redirect === 'function'
      ? [{}, { tab: 'payments', stage: 'history', view: 'offline', leaseId: '9' }].map(query =>
          (route.redirect as Function)({ path: route.path, params: { id: '7' }, query, hash: '#test' }))
      : route.redirect,
    children: route.children ? contracts(route.children) : undefined
  }))
}
describe('三端与公开路由兼容', () => {
  it('保留路径、角色、名称、重定向及深链参数', () => {
    const actual = JSON.parse(JSON.stringify(contracts([...admin, ...resident, ...staff, ...guest])))
    if (process.env.RECORD_PORTAL_ROUTES === 'true') writeFileSync(fixture, JSON.stringify(actual, null, 2) + '\n')
    expect(actual).toEqual(JSON.parse(readFileSync(fixture, 'utf-8')))
  })
})
