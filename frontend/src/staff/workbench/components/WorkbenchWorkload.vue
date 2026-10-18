<script setup lang="ts">
import EChart, {type ChartOption} from '@/common/statistics/components/EChart.vue'
import type {IStaffWorkloadStats} from '@/common/statistics/types/statistics'
defineProps<{workload:IStaffWorkloadStats|null; loading:boolean; error:string; option:ChartOption}>()
const emit=defineEmits<{retry:[]}>()
</script>
<template>
    <section class="panel workload-panel" v-loading="loading" aria-label="本人工作量事件统计">
      <header class="panel-header"><h2 class="panel-title">近7日工作量事件</h2></header>
      <div v-if="error" class="workload-error" role="alert">
        <p>事件统计加载失败：{{ error }}</p>
        <button type="button" class="panel-more" @click="emit('retry')">重新加载事件统计</button>
      </div>
      <template v-else-if="workload">
        <p class="workload-summary">
          今日提交结果 <strong data-testid="today-submitted">{{ workload.todaySubmitted }}</strong> 次 ·
          近7日派入 <strong>{{ Object.values(workload.assignedTrend).reduce((sum, value) => sum + value, 0) }}</strong> 次 ·
          近7日提交结果 <strong>{{ workload.totalSubmitted }}</strong> 次
        </p>
        <EChart :option="option" :height="240" />
        <p class="workload-note">按实际派入和本人提交时间统计；重派、退回后再次提交分别计次，不是去重工单数。</p>
      </template>
    </section>

</template>
