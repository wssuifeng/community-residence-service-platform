<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { getConfigList, updateConfig } from '@/api/resident'
import type { ISysConfig } from '@/types/modules/resident'

/** 全局配置：配置项表格 + 编辑值对话框（仅超级管理员可修改，路由已限超管；后端为全量列表，无分页） */

/** 日志保留期键（R58/N6：阈值天数为正整数，到期自动清理并通知超管） */
const LOG_RETENTION_KEY = 'log.retention_days'

const configs = ref<ISysConfig[]>([])
const loading = ref(false)

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

onMounted(load)
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

    <el-table v-loading="loading" :data="configs" border>
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
    </el-table>

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
