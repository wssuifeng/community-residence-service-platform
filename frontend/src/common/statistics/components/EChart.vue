<script lang="ts">
import type { ComposeOption } from 'echarts/core'
import type { BarSeriesOption, LineSeriesOption, PieSeriesOption } from 'echarts/charts'
import type {
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption
} from 'echarts/components'

/** 图表配置（仅本项目用到的三种图型） */
export type ChartOption = ComposeOption<
  | LineSeriesOption
  | BarSeriesOption
  | PieSeriesOption
  | GridComponentOption
  | TooltipComponentOption
  | LegendComponentOption
>

/** 图表统一色板（主色系派生） */
export const CHART_PALETTE = ['#3b6dff', '#6b8fff', '#10b981', '#f59e0b', '#ef4444', '#6b7280']
</script>

<script setup lang="ts">
import { computed } from 'vue'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, LineChart, BarChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const props = withDefaults(
  defineProps<{
    option: ChartOption
    /** 图表高度（px），默认 320 */
    height?: number
    /** 空数据提示文案，提供则数据为空时显示占位 */
    emptyText?: string
    isEmpty?: boolean
  }>(),
  { height: 320 }
)

const mergedOption = computed<ChartOption>(() => ({
  color: CHART_PALETTE,
  tooltip: { trigger: 'axis' },
  ...props.option
}))
</script>

<template>
  <div class="echart-wrap" :style="{ height: `${height}px` }">
    <VChart v-if="!isEmpty" :option="mergedOption" autoresize class="echart-instance" />
    <div v-else class="echart-empty">{{ emptyText ?? '暂无数据' }}</div>
  </div>
</template>

<style scoped>
.echart-wrap {
  width: 100%;
  position: relative;
}

.echart-instance {
  width: 100%;
  height: 100%;
}

.echart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}
</style>
