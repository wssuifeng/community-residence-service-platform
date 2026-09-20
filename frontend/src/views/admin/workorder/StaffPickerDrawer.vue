<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { assignWorkOrder, listAssignableStaff } from '@/api/workorder'
import type { IStaffOption, IWorkOrder } from '@/types/modules/workorder'
import { staffRecommendLevelLabels } from '@/types/modules/workorder'
import { isReassignOrder } from './dispatch'

/**
 * 派单候选抽屉（列表看板与工单详情共用，避免两处各写一套候选呈现）。
 * 候选由后端按推荐档位排序：常驻本社区且擅长该类别 → 常驻本社区 → 擅长该类别 → 其他，
 * 同档按在手工单数升序；抽屉只做分组展示与选择，档位口径不在前端重算。
 */
const props = defineProps<{
  modelValue: boolean
  /** 目标工单（社区与类别决定候选推荐档位） */
  order: IWorkOrder | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 派单成功（调用方负责刷新列表与统计） */
  assigned: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const staffList = ref<IStaffOption[]>([])
const loading = ref(false)
const selectedId = ref<number | null>(null)
const remark = ref('')
const submitting = ref(false)
const loadError = ref('')

/** 按推荐档位分组（档位升序即推荐度降序），组内保持后端返回的在手工单数升序 */
const groups = computed(() => {
  const buckets = new Map<number, IStaffOption[]>()
  for (const item of staffList.value) {
    const level = item.recommendLevel ?? 4
    const bucket = buckets.get(level)
    if (bucket) bucket.push(item)
    else buckets.set(level, [item])
  }
  return [...buckets.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([level, list]) => ({
      level,
      label: staffRecommendLevelLabels[level] ?? '其他人员',
      list
    }))
})

const selectedStaff = computed(() => staffList.value.find((item) => item.id === selectedId.value) ?? null)
/** 已派单状态下再派一次即改派（仅影响文案，动作与派单同一端点） */
const reassign = computed(() => (props.order ? isReassignOrder(props.order) : false))
const title = computed(() => `${reassign.value ? '改派' : '派单'} · ${props.order?.orderNo ?? ''}`)

async function loadCandidates(): Promise<void> {
  loading.value = true
  loadError.value = ''
  try {
    staffList.value = await listAssignableStaff({
      communityId: props.order?.communityId,
      categoryId: props.order?.categoryId
    })
  } catch (error) {
    staffList.value = []
    loadError.value = error instanceof Error ? error.message : '候选服务人员加载失败'
  } finally {
    loading.value = false
  }
}

/* 每次打开都重取候选：班次与在手工单数随时刻变化，缓存会给出过期的调度依据 */
watch(visible, (value) => {
  if (!value) return
  selectedId.value = null
  remark.value = ''
  void loadCandidates()
})

async function handleSubmit(): Promise<void> {
  if (!props.order) return
  if (!selectedId.value) {
    ElMessage.warning('请先选择服务人员')
    return
  }
  submitting.value = true
  try {
    await assignWorkOrder(props.order.id, {
      assigneeId: selectedId.value,
      remark: remark.value.trim() || undefined
    })
    ElMessage.success(`${reassign.value ? '已改派给' : '已派单给'} ${selectedStaff.value?.realName ?? '服务人员'}`)
    visible.value = false
    emit('assigned')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '派单失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-drawer v-model="visible" :title="title" size="520px" append-to-body>
    <div class="picker">
      <p class="picker-intro">
        候选按调度推荐档位排列：<strong>常驻本社区且擅长该类别</strong>最优先，同档按当前在手工单数从少到多。
        今日班次为「休息 / 未排班」的人员仍可派单，但接单响应可能延迟。
      </p>

      <div v-loading="loading" class="picker-body">
        <el-alert v-if="loadError" type="error" :closable="false" :title="loadError" show-icon class="picker-alert" />

        <el-empty
          v-else-if="!loading && staffList.length === 0"
          description="暂无可派单的服务人员"
          :image-size="70"
        >
          <router-link class="picker-link" to="/admin/staff-capabilities">前往「服务人员」页绑定人员</router-link>
        </el-empty>

        <template v-else>
          <section v-for="group in groups" :key="group.level" class="group">
            <header class="group-head" :class="{ 'is-top': group.level === 1 }">
              <span class="group-dot" aria-hidden="true" />
              <span class="group-label">{{ group.label }}</span>
              <span class="group-count">{{ group.list.length }} 人</span>
              <span v-if="group.level === 1" class="group-badge">推荐</span>
            </header>

            <ul class="candidate-list">
              <li
                v-for="staff in group.list"
                :key="staff.id"
                class="candidate"
                :class="{ 'is-selected': selectedId === staff.id, 'is-top': group.level === 1 }"
                @click="selectedId = staff.id"
              >
                <span class="candidate-radio" aria-hidden="true" />
                <div class="candidate-main">
                  <p class="candidate-name">
                    {{ staff.realName }}
                    <span v-if="staff.matchedCommunity" class="match-chip">常驻本社区</span>
                    <span v-if="staff.matchedCategory" class="match-chip">擅长该类别</span>
                  </p>
                  <p class="candidate-meta">
                    <span>常驻社区：{{ staff.communityNames || '未绑定' }}</span>
                  </p>
                </div>
                <div class="candidate-side">
                  <span class="shift-chip" :class="{ 'is-off': !staff.todayShiftLabel || staff.todayShiftLabel === '休息' }">
                    {{ staff.todayShiftLabel || '未排班' }}
                  </span>
                  <span class="load-chip" :class="{ 'is-busy': (staff.activeOrderCount ?? 0) >= 5 }">
                    在手工单 {{ staff.activeOrderCount ?? 0 }}
                  </span>
                </div>
              </li>
            </ul>
          </section>
        </template>
      </div>

      <div class="picker-remark">
        <span class="remark-label">派单备注</span>
        <el-input
          v-model="remark"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          placeholder="派单要求（可选，如上门时间或注意事项）"
        />
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!selectedId" @click="handleSubmit">
        {{ reassign ? '确认改派' : '确认派单' }}
      </el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  height: 100%;
}

.picker-intro {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-primary-bg);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.picker-intro strong {
  color: var(--color-primary);
}

.picker-body {
  flex: 1;
  min-height: 120px;
  overflow-y: auto;
}

.picker-alert {
  margin-bottom: var(--spacing-md);
}

.picker-link {
  color: var(--color-primary);
  font-size: var(--font-size-sm);
}

.group + .group {
  margin-top: var(--spacing-md);
}

/* 档位分组头：档位 1 主色强调并带「推荐」角标 */
.group-head {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.group-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-circle);
  background-color: var(--color-border);
}

.group-head.is-top {
  color: var(--color-primary);
}

.group-head.is-top .group-dot {
  background-color: var(--color-primary);
}

.group-count {
  margin-left: auto;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.group-badge {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary);
  color: #fff;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-bold);
}

.candidate-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

/* 候选项：整行可选，选中主色描边 + 浅底 */
.candidate {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--admin-card-bg);
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease;
}

.candidate:hover {
  border-color: var(--color-primary-light);
}

.candidate.is-selected {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.candidate.is-top {
  border-left: 3px solid var(--color-primary);
}

.candidate-radio {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  border: 1.5px solid var(--color-border);
  border-radius: var(--radius-circle);
}

.candidate.is-selected .candidate-radio {
  border-color: var(--color-primary);
  border-width: 4px;
}

.candidate-main {
  flex: 1;
  min-width: 0;
}

.candidate-name {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.match-chip {
  padding: 0 var(--spacing-xs);
  border-radius: var(--radius-sm);
  background-color: var(--color-success-soft);
  color: #047857;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
}

.candidate-meta {
  margin: 2px 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.candidate-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
  font-size: var(--font-size-xs);
}

.shift-chip {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-sm);
  background-color: var(--color-success-soft);
  color: #047857;
  white-space: nowrap;
}

.shift-chip.is-off {
  background-color: var(--color-warning-soft);
  color: #b45309;
}

.load-chip {
  color: var(--color-text-disabled);
  white-space: nowrap;
}

.load-chip.is-busy {
  color: var(--color-warning);
  font-weight: var(--font-weight-medium);
}

.picker-remark {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  padding-top: var(--spacing-sm);
  border-top: 1px solid var(--color-border);
}

.remark-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
