<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { getConfigList, updateConfig } from '@/api/resident'
import type { ISysConfig } from '@/types/modules/resident'

/** 全局配置：配置项表格 + 编辑值对话框（仅超级管理员可修改，路由已限超管；后端为全量列表，无分页） */

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

const editRules: FormRules = {
  value: [{ required: true, message: '请输入配置值', trigger: 'blur' }]
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
          <el-input
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
</style>
