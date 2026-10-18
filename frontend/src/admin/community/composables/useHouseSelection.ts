import { watch, type Ref, type WatchSource } from 'vue'
import { useGridSelection } from '@/common/web/composables/useGridSelection'
import type { HouseEntry } from '@/admin/community/types/structure'
/** 选区只包含当前可见范围且仍存在的房屋；范围变化即清空。 */
export function useHouseSelection(gridBodyRef: Ref<HTMLElement | null>, houseEntries: Readonly<Ref<HouseEntry[]>>, filters: WatchSource[]) {
const {
  selectedIds: selectedHouseIds,
  selectedCount: selectedHouseCount,
  marquee: gridMarquee,
  isSelected: isHouseGridSelected,
  clearSelection: clearHouseSelection,
  toggleSelect: toggleHouseSelection,
  onGridPointerDown: onHouseGridPointerDown,
  onGridPointerMove: onHouseGridPointerMove,
  onGridPointerUp: onHouseGridPointerUp,
  onGridPointerCancel: onHouseGridPointerCancel
} = useGridSelection(gridBodyRef)

/* 筛选变化后清空选区：避免批量删除作用于当前不可见的房屋 */
watch(filters, () => {
  clearHouseSelection()
})

/* 结构局部刷新后剪除已不存在的房屋 id（悬浮钮单删选中项时保持操作条计数真实） */
watch(houseEntries, (list) => {
  if (selectedHouseIds.value.size === 0) return
  const alive = new Set(list.map((entry) => entry.house.id))
  let changed = false
  const next = new Set<number>()
  selectedHouseIds.value.forEach((id) => {
    if (alive.has(id)) {
      next.add(id)
    } else {
      changed = true
    }
  })
  if (changed) selectedHouseIds.value = next
})


return {selectedHouseIds, selectedHouseCount, gridMarquee, isHouseGridSelected, clearHouseSelection, toggleHouseSelection, onHouseGridPointerDown, onHouseGridPointerMove, onHouseGridPointerUp, onHouseGridPointerCancel}
}
