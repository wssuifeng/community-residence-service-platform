import { defineComponent, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { useHouseSelection } from '../useHouseSelection'
import type { HouseEntry } from '@/admin/community/types/structure'
describe('房屋选区约束', () => {
 it('切范围清空选区，数据刷新删除的房屋不留在批量操作目标中', async () => {
  const entries=ref([{house:{id:1}},{house:{id:2}}] as HouseEntry[]),filter=ref(1)
  let selection!:ReturnType<typeof useHouseSelection>
  const w=mount(defineComponent({setup(){selection=useHouseSelection(ref(null),entries,[filter]);return()=>null}}))
  selection.toggleHouseSelection(1);selection.toggleHouseSelection(2)
  entries.value=[entries.value[1]];await w.vm.$nextTick();expect([...selection.selectedHouseIds.value]).toEqual([2])
  filter.value=2;await w.vm.$nextTick();expect(selection.selectedHouseCount.value).toBe(0);w.unmount()
 })
})
