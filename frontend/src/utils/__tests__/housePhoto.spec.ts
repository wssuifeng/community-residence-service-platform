import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { HOUSE_PHOTO_EMPTY } from '../housePhoto'
describe('房屋图片的唯一数据来源与明确空态', () => {
 it('无图是明确空态，不以默认室内照片伪装已配置图片', () => {
  const svg = readFileSync(resolve(process.cwd(), 'public'+HOUSE_PHOTO_EMPTY), 'utf8')
  expect(svg).toContain('尚未上传房屋图片'); expect(svg).toContain('此处为空态，不代表房屋实拍')
 })
 it('所有房源入口不再轮换示例照片，旧管理员编辑器不提供URL输入', () => {
  const paths = ['admin/housing/HousingListView.vue','admin/housing/HousingDetailView.vue','resident/HousingListView.vue','resident/HousingDetailView.vue','resident/ResidenceApplyView.vue','guest/HomeView.vue','guest/HousingListView.vue','guest/HousingDetailView.vue']
  for (const path of paths) {
   const source = readFileSync(resolve(process.cwd(), 'src/views', path), 'utf8')
   expect(source).toContain('HOUSE_PHOTO_EMPTY'); expect(source).not.toContain('/images/housing-sample-')
   expect(source).not.toContain('图片地址，每行一个'); expect(source).not.toContain('imagesText')
  }
 })
})
