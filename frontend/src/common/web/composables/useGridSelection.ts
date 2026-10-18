import { computed, onMounted, onUnmounted, ref, type Ref } from 'vue'

/** 框选拖拽阈值（px）：位移低于该值视为普通点击而非框选，避免手抖误选 */
const MARQUEE_THRESHOLD_PX = 4

/** 橡皮筋渲染盒：坐标相对网格容器左上角（容器内绝对定位直接消费） */
export interface GridMarquee {
  active: boolean
  left: number
  top: number
  width: number
  height: number
}

const MARQUEE_INACTIVE: GridMarquee = { active: false, left: 0, top: 0, width: 0, height: 0 }

/**
 * 网格多选（R4-D3）：Ctrl/Cmd+左键点选切换 + 空白区橡皮筋框选（实时预览）+ Esc 清空。
 * 单元视图网格与楼栋视图多单元区块网格共用同一渲染容器（gridGroups 两种数据形态），
 * 本组合框以容器元素为作用域，其他网格容器可独立再消费同一套逻辑。
 * 约束：框选仅响应鼠标；卡片/按钮上的按下不启动框选（各自交互不受干扰）。
 */
export function useGridSelection(containerRef: Ref<HTMLElement | null>) {
  const selectedIds = ref<Set<number>>(new Set())
  /** 框选拖拽中的实时预览命中集（松手后并入正式选区） */
  const previewIds = ref<Set<number>>(new Set())
  const marquee = ref<GridMarquee>({ ...MARQUEE_INACTIVE })

  const selectedCount = computed(() => selectedIds.value.size)

  /** 选中视觉判定：正式选中或框选预览命中（两者同视觉，松手即转正） */
  function isSelected(id: number): boolean {
    return selectedIds.value.has(id) || previewIds.value.has(id)
  }

  function clearSelection(): void {
    selectedIds.value = new Set()
    previewIds.value = new Set()
  }

  /** Ctrl/Cmd+点选：切换该卡片选中态（增量式，天然支持反选微调） */
  function toggleSelect(id: number): void {
    const next = new Set(selectedIds.value)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    selectedIds.value = next
  }

  /* ---------- 橡皮筋框选（容器空白区按下左键拖拽） ---------- */

  interface DragState {
    pointerId: number
    startX: number
    startY: number
    moved: boolean
    /** 按下时按住 Ctrl/Cmd：框选结果并入既有选区而非替换 */
    additive: boolean
    baseIds: Set<number>
  }
  let drag: DragState | null = null

  function onGridPointerDown(event: PointerEvent): void {
    if (event.pointerType !== 'mouse' || event.button !== 0) return
    const target = event.target as HTMLElement | null
    /* 卡片（含其悬浮操作钮）与其他按钮上的按下走各自交互，不启动框选 */
    if (target?.closest('.grid-block, button')) return
    const container = containerRef.value
    if (!container) return
    drag = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      moved: false,
      additive: event.ctrlKey || event.metaKey,
      baseIds: new Set(selectedIds.value)
    }
    /* 阻止原生文本拖选；pointer capture 保证拖出容器后仍持续收到 move/up */
    event.preventDefault()
    container.setPointerCapture(event.pointerId)
  }

  function onGridPointerMove(event: PointerEvent): void {
    if (!drag || event.pointerId !== drag.pointerId) return
    const dx = event.clientX - drag.startX
    const dy = event.clientY - drag.startY
    if (!drag.moved && Math.hypot(dx, dy) < MARQUEE_THRESHOLD_PX) return
    drag.moved = true
    const container = containerRef.value
    if (!container) return
    const bounds = container.getBoundingClientRect()
    const clientLeft = Math.min(drag.startX, event.clientX)
    const clientTop = Math.min(drag.startY, event.clientY)
    const clientRight = Math.max(drag.startX, event.clientX)
    const clientBottom = Math.max(drag.startY, event.clientY)
    marquee.value = {
      active: true,
      left: clientLeft - bounds.left,
      top: clientTop - bounds.top,
      width: clientRight - clientLeft,
      height: clientBottom - clientTop
    }
    /* 实时预览：视口坐标与卡片包围盒求交（与容器内部布局、滚动无关） */
    const hit = new Set<number>()
    container.querySelectorAll<HTMLElement>('.grid-block[data-house-id]').forEach((el) => {
      const rect = el.getBoundingClientRect()
      if (
        rect.left < clientRight &&
        rect.right > clientLeft &&
        rect.top < clientBottom &&
        rect.bottom > clientTop
      ) {
        const id = Number(el.dataset.houseId)
        if (Number.isFinite(id)) hit.add(id)
      }
    })
    previewIds.value = hit
  }

  function finishDrag(): void {
    drag = null
    marquee.value = { ...MARQUEE_INACTIVE }
    previewIds.value = new Set()
  }

  function onGridPointerUp(event: PointerEvent): void {
    if (!drag || event.pointerId !== drag.pointerId) return
    const state = drag
    if (!state.moved) {
      /* 未达阈值的空白处普通点击：清空选区（快速取消的顺手机会）；Ctrl 按住时保留 */
      if (!state.additive) clearSelection()
      finishDrag()
      return
    }
    const next = state.additive ? new Set(state.baseIds) : new Set<number>()
    previewIds.value.forEach((id) => next.add(id))
    selectedIds.value = next
    finishDrag()
  }

  function onGridPointerCancel(): void {
    finishDrag()
  }

  /* Esc 全局兜底清空（对话框以 Esc 关闭时顺带清空选区，无副作用） */
  function onWindowKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') clearSelection()
  }
  onMounted(() => window.addEventListener('keydown', onWindowKeydown))
  onUnmounted(() => window.removeEventListener('keydown', onWindowKeydown))

  return {
    selectedIds,
    selectedCount,
    marquee,
    isSelected,
    clearSelection,
    toggleSelect,
    onGridPointerDown,
    onGridPointerMove,
    onGridPointerUp,
    onGridPointerCancel
  }
}
