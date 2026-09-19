<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 通用横拉列表容器（DEF-057）：默认插槽内容水平排列，两端毛玻璃圆钮箭头按步长平滑滚动。
 * 内容未溢出时箭头隐藏且列表整体居中；溢出后滚动到两端时对应箭头置灰。
 * 卡片交互（选中/禁用态）由使用方渲染，容器不感知业务。
 */

const props = withDefaults(
  defineProps<{
    /** 单次点击滚动的像素步长；缺省按容器可视宽度 80% 取值 */
    step?: number
  }>(),
  { step: 0 }
)

const scroller = ref<HTMLElement | null>(null)
const track = ref<HTMLElement | null>(null)
const hasOverflow = ref(false)
const atStart = ref(true)
const atEnd = ref(false)

/* 溢出与边界态同步：滚动、容器与内容尺寸变化（异步加载卡片）都需重算 */
function syncState(): void {
  const el = scroller.value
  if (!el) return
  hasOverflow.value = el.scrollWidth > el.clientWidth + 1
  atStart.value = el.scrollLeft <= 1
  atEnd.value = el.scrollLeft + el.clientWidth >= el.scrollWidth - 1
}

function scrollByStep(direction: -1 | 1): void {
  const el = scroller.value
  if (!el) return
  const distance = props.step > 0 ? props.step : Math.max(el.clientWidth * 0.8, 200)
  el.scrollBy({ left: direction * distance, behavior: 'smooth' })
}

let observer: ResizeObserver | null = null

onMounted(() => {
  void nextTick(syncState)
  if (typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver(() => syncState())
  if (scroller.value) observer.observe(scroller.value)
  if (track.value) observer.observe(track.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})
</script>

<template>
  <div class="h-scroller">
    <div
      ref="scroller"
      class="h-scroller-viewport"
      :class="{ 'is-centered': !hasOverflow }"
      @scroll.passive="syncState"
    >
      <div ref="track" class="h-scroller-track">
        <slot />
      </div>
    </div>

    <button
      v-if="hasOverflow"
      type="button"
      class="h-scroller-arrow is-left"
      :disabled="atStart"
      aria-label="向左滚动"
      @click="scrollByStep(-1)"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="15 18 9 12 15 6" />
      </svg>
    </button>
    <button
      v-if="hasOverflow"
      type="button"
      class="h-scroller-arrow is-right"
      :disabled="atEnd"
      aria-label="向右滚动"
      @click="scrollByStep(1)"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="9 18 15 12 9 6" />
      </svg>
    </button>
  </div>
</template>

<style scoped>
.h-scroller {
  position: relative;
}

/* 滚动视口：箭头是唯一滚动指示，滚动条常隐（触控板/滚轮仍可横向滚动） */
.h-scroller-viewport {
  overflow-x: auto;
  scroll-behavior: smooth;
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.h-scroller-viewport::-webkit-scrollbar {
  display: none;
}

.h-scroller-track {
  display: flex;
  gap: var(--spacing-sm);
  width: fit-content;
  min-width: 100%;
}

/* 内容未溢出时整条居中；溢出后 margin 自动归零，避免负向溢出不可达 */
.h-scroller-viewport.is-centered .h-scroller-track {
  margin: 0 auto;
}

/* 左右箭头：毛玻璃圆钮，与图片区/轮播箭头同款交互语言（平时半透明，悬浮显形，hover 主色） */
.h-scroller-arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: none;
  border-radius: var(--radius-circle);
  background: rgba(255, 255, 255, 0.55);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  color: var(--color-text-primary);
  box-shadow: var(--shadow-md);
  cursor: pointer;
  opacity: 0.45;
  transition: opacity 0.2s ease, background 0.15s ease, color 0.15s ease;
}

.h-scroller:hover .h-scroller-arrow:not(:disabled),
.h-scroller-arrow:focus-visible:not(:disabled) {
  opacity: 1;
}

.h-scroller-arrow:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.85);
  color: var(--color-primary);
}

.h-scroller-arrow.is-left {
  left: var(--spacing-sm);
}

.h-scroller-arrow.is-right {
  right: var(--spacing-sm);
}

.h-scroller-arrow:disabled {
  opacity: 0.15;
  cursor: default;
}

.h-scroller-arrow svg {
  width: 18px;
  height: 18px;
}
</style>
