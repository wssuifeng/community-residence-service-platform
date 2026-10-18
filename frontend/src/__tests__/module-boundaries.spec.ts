import { readFileSync, readdirSync } from 'node:fs'
import { resolve, relative } from 'node:path'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import admin from '@/admin/routes'
import resident from '@/resident/routes'
import staff from '@/staff/routes'
import guest from '@/common/public/routes'
import auth from '@/common/auth/routes'
import { menuItems } from '@/admin/layout/navigation'
import { navItems as residentNav } from '@/resident/layout/navigation'
import { navItems as staffNav } from '@/staff/layout/navigation'
const root = resolve(process.cwd(), 'src')
function files(dir: string): string[] { return readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? files(resolve(dir,e.name)) : [resolve(dir,e.name)]) }
describe('端与公共依赖方向', () => {
 it('公共生产代码不引用端，端之间不互相引用', () => {
  const violations: string[] = []
  for (const file of files(root)) {
   const path = relative(root,file).replaceAll('\\','/')
   if (!/\.(ts|vue)$/.test(path) || /(__tests__|\.spec\.|^test\/)/.test(path)) continue
   const owner = path.split('/')[0]
   if (!['common','admin','resident','staff'].includes(owner)) continue
   const source = readFileSync(file,'utf8')
   for (const hit of source.matchAll(/(?:from\s*|import\s*\()(['"])@\/(admin|resident|staff)\/[^'"]+\1/g)) {
    if (owner !== hit[2]) violations.push(`${path} -> ${hit[2]}`)
   }
  }
  expect(violations).toEqual([])
 })
 it('根目录只保留应用装配与全局测试', () => {
  const allowed = ['App.vue','__tests__','admin','common','env.d.ts','main.ts','resident','router.ts','staff','test']
  expect(readdirSync(root).filter(name => !allowed.includes(name))).toEqual([])
 })
 it('三端导航路径及标题与真实路由一致，消息中心与会话独立', () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [...admin,...resident,...staff,...guest,...auth] })
  for (const item of [...menuItems,...residentNav,...staffNav]) {
   const path = 'path' in item ? item.path : item.to
   expect(router.resolve(path).matched.length).toBeGreaterThan(0)
   expect(router.resolve(path).meta.title).toBe(item.pageTitle)
  }
  expect(menuItems.find(i=>i.path==='/admin/system')?.roles).toEqual(['SUPER_ADMIN'])
  expect(menuItems.filter(i=>['/admin/conversations','/admin/notifications'].includes(i.path))).toHaveLength(2)
 })
})
