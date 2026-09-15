<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { getConfigList, updateConfig } from '@/api/resident'
import { getChannelLevels, updateChannelLevels } from '@/api/notification'
import type { ISysConfig } from '@/types/modules/resident'
import type { IChannelLevels } from '@/types/modules/notification'

/**
 * 全局配置 Tab（自 resident/GlobalConfigView.vue 原样迁入系统管理页，任务 12 只换壳）：
 * 配置项表格 + 编辑值对话框（仅超级管理员可修改，ADMIN 视角由容器渲染空态；后端为全量列表，无分页）
 */

/** 日志保留期键（R58/N6：阈值天数为正整数，到期自动清理并通知超管） */
const LOG_RETENTION_KEY = 'log.retention_days'

/** 通知渠道分级键（R51）：由专用勾选卡片管理，键值表隐藏原始 JSON 行避免双入口编辑冲突 */
const CHANNEL_LEVELS_KEY = 'notify.channel.levels'

/**
 * 通知等级全集（DEF-037 归纳）：后端 notificationService.create 调用处的
 * type 实参为 WORK_ORDER / NOTICE / FEEDBACK / RESERVATION / RESIDENCE /
 * LEASE / SYSTEM 七级（业务侧推送与定时任务），GET 返回键与之取并集渲染，
 * 不写死未知等级
 */
const NOTIFICATION_LEVELS = [
  'WORK_ORDER',
  'NOTICE',
  'FEEDBACK',
  'RESERVATION',
  'RESIDENCE',
  'LEASE',
  'SYSTEM'
] as const

/** 等级中文标签（缺失时回退等级原名） */
const LEVEL_LABELS: Record<string, string> = {
  WORK_ORDER: '工单',
  NOTICE: '公告',
  FEEDBACK: '反馈',
  RESERVATION: '预约',
  RESIDENCE: '居住',
  LEASE: '租住',
  SYSTEM: '系统'
}

/** 可配置站外模拟渠道（R51；站内 WEBSOCKET 必达不可配置） */
const CONFIGURABLE_CHANNELS = ['EMAIL', 'SMS'] as const

const configs = ref<ISysConfig[]>([])
const loading = ref(false)

/** 键值表隐藏渠道分级原始 JSON 行（由专用勾选卡片管理，单一编辑入口） */
const visibleConfigs = computed(() =>
  configs.value.filter((row) => row.configKey !== CHANNEL_LEVELS_KEY)
)

/* ---- 通知渠道分级卡片（R51 DEF-037） ---- */

/** 勾选态：等级 → Set<渠道>（本地编辑副本，保存成功后同步为已提交值） */
const channelChecked = ref<Record<string, Set<string>>>({})

/** 保存前最后一次成功加载/提交的等级→渠道快照（PUT 失败回显原值用） */
let channelSnapshot: IChannelLevels = {}

/** 渠道卡片行：等级全集 ∪ GET 返回键（GET 可能含历史/未知等级，不丢弃） */
const channelRows = computed(() => {
  const keys = new Set<string>(NOTIFICATION_LEVELS)
  for (const key of Object.keys(channelSnapshot)) keys.add(key)
  return [...keys]
})

const channelLoading = ref(false)
const channelSaving = ref(false)

/** 从等级→渠道列表映射构建本地勾选副本 */
function applyChannelLevels(levels: IChannelLevels): void {
  const next: Record<string, Set<string>> = {}
  for (const [level, channels] of Object.entries(levels)) {
    next[level] = new Set(channels ?? [])
  }
  channelChecked.value = next
  channelSnapshot = levels
}

async function loadChannelLevels(): Promise<void> {
  channelLoading.value = true
  try {
    applyChannelLevels(await getChannelLevels())
  } catch {
    /* 读失败保持空勾选态，卡片行仍按全集渲染（保存时全量提交覆盖） */
  } finally {
    channelLoading.value = false
  }
}

function isChannelChecked(level: string, channel: string): boolean {
  return channelChecked.value[level]?.has(channel) ?? false
}

function toggleChannel(level: string, channel: string, checked: boolean): void {
  const set = channelChecked.value[level] ?? new Set<string>()
  if (checked) set.add(channel)
  else set.delete(channel)
  channelChecked.value = { ...channelChecked.value, [level]: set }
}

async function saveChannelLevels(): Promise<void> {
  channelSaving.value = true
  try {
    /* PUT 全量提交：全集等级行每级都提交（未勾选提交空数组，语义=仅站内） */
    const levels: IChannelLevels = {}
    for (const level of channelRows.value) {
      levels[level] = [...(channelChecked.value[level] ?? [])]
    }
    await updateChannelLevels(levels)
    applyChannelLevels(levels)
    ElMessage.success('通知渠道分级配置已保存')
  } catch (error) {
    /* 提交失败回显原值，避免界面停留在未落库的编辑态 */
    applyChannelLevels(channelSnapshot)
    ElMessage.error(error instanceof Error ? error.message : '保存失败，已恢复原配置')
  } finally {
    channelSaving.value = false
  }
}

/** 编辑对话框 */
const editVisible = ref(false)
const editLoading = ref(false)
const editFormRef = ref<FormInstance>()
const editForm = reactive({
  key: '',
  value: '',
  description: ''
})

/** 日志保留期走数字输入（el-input-number 绑定数字，编辑表单值为字符串） */
const isRetentionDays = computed(() => editForm.key === LOG_RETENTION_KEY)
const retentionDays = computed({
  get: () => Number(editForm.value) || undefined,
  set: (val: number | undefined) => {
    editForm.value = val === undefined ? '' : String(val)
  }
})

const editRules: FormRules = {
  value: [
    { required: true, message: '请输入配置值', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (!isRetentionDays.value) return callback()
        const num = Number(value)
        if (!Number.isInteger(num) || num <= 0) {
          return callback(new Error('日志保留期必须为正整数（天）'))
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

async function load(): Promise<void> {
  loading.value = true
  try {
    configs.value = await getConfigList()
  } catch {
    configs.value = []
  } finally {
    loading.value = false
  }
}

function openEdit(row: ISysConfig): void {
  editFormRef.value?.resetFields()
  Object.assign(editForm, {
    key: row.configKey,
    value: row.configValue,
    description: row.description ?? ''
  })
  editVisible.value = true
}

async function handleEdit(): Promise<void> {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  editLoading.value = true
  try {
    await updateConfig(editForm.key, { value: editForm.value })
    ElMessage.success('配置已保存')
    editVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    editLoading.value = false
  }
}

onMounted(() => {
  load()
  loadChannelLevels()
})
</script>

<template>
  <section class="global-config">
    <el-alert
      class="config-notice"
      title="全局配置仅超级管理员可修改（本页面仅超级管理员角色可访问）"
      type="info"
      :closable="false"
      show-icon
    />

    <!-- 通知渠道分级专用卡片（R51 DEF-037：勾选轻交互，非表单） -->
    <div class="table-panel channel-panel">
      <div class="channel-header">
        <div>
          <h3 class="channel-title">通知渠道分级</h3>
          <p class="channel-subtitle">
            按通知等级勾选站外模拟渠道（R51）；站内通知必达不可配置
          </p>
        </div>
        <el-button
          type="primary"
          :loading="channelSaving"
          :disabled="channelLoading"
          @click="saveChannelLevels"
        >
          保存配置
        </el-button>
      </div>
      <div v-loading="channelLoading" class="channel-body">
        <div v-for="level in channelRows" :key="level" class="channel-row">
          <div class="channel-level">
            <span class="channel-level-name">{{ LEVEL_LABELS[level] ?? level }}</span>
            <span class="channel-level-code">{{ level }}</span>
          </div>
          <div class="channel-options">
            <el-checkbox
              v-for="channel in CONFIGURABLE_CHANNELS"
              :key="channel"
              :model-value="isChannelChecked(level, channel)"
              @update:model-value="(checked: boolean | string | number) => toggleChannel(level, channel, Boolean(checked))"
            >
              {{ channel }}
            </el-checkbox>
            <span class="channel-fixed" title="WEBSOCKET 站内必达，不可配置">WEBSOCKET · 站内必达</span>
          </div>
        </div>
      </div>
    </div>

    <div class="table-panel">
      <el-table v-loading="loading" :data="visibleConfigs">
        <el-table-column prop="configKey" label="配置项" min-width="220" show-overflow-tooltip />
        <el-table-column prop="configValue" label="配置值" min-width="160" show-overflow-tooltip />
        <el-table-column label="说明" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-permission="['SUPER_ADMIN']" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无配置项" :image-size="80" />
        </template>
      </el-table>
    </div>

    <!-- 编辑配置值 -->
    <el-dialog v-model="editVisible" title="编辑配置" width="480px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px">
        <el-form-item label="配置项">
          <span class="config-key">{{ editForm.key }}</span>
        </el-form-item>
        <el-form-item v-if="editForm.description" label="说明">
          <span class="config-desc">{{ editForm.description }}</span>
        </el-form-item>
        <el-form-item label="配置值" prop="value">
          <template v-if="isRetentionDays">
            <el-input-number
              v-model="retentionDays"
              :min="1"
              :max="36500"
              :step="30"
              step-strictly
            />
            <div class="config-hint">日志保留期（天），默认 730；到期自动清理并通知超级管理员</div>
          </template>
          <el-input
            v-else
            v-model="editForm.value"
            type="textarea"
            :rows="3"
            placeholder="请输入配置值"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editLoading" @click="handleEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.config-notice {
  margin-bottom: var(--spacing-md);
}

/* 通知渠道分级卡片（置顶，与键值表同款白卡容器） */
.channel-panel {
  margin-bottom: var(--spacing-md);
}

.channel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-xs) 0 var(--spacing-sm);
}

.channel-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.channel-subtitle {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.channel-body {
  min-height: 120px;
  padding-bottom: var(--spacing-sm);
}

.channel-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-xs);
  border-bottom: 1px solid var(--color-border);
}

.channel-row:last-child {
  border-bottom: none;
}

.channel-level {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  min-width: 160px;
}

.channel-level-name {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.channel-level-code {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.channel-options {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
}

/* WEBSOCKET 固定开启只读徽章 */
.channel-fixed {
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  background-color: var(--color-bg-subtle);
  cursor: default;
  user-select: none;
}

/* 表格白卡容器（含内边距，与居民列表 table-panel 同款） */
.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) var(--spacing-xs);
}

.config-key {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  word-break: break-all;
}

.config-desc {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.config-hint {
  width: 100%;
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}
</style>
