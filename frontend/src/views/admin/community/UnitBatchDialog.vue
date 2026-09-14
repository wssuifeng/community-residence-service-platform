<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createUnit } from '@/api/community'
import type { IBuilding } from '@/types/modules/community'

/**
 * 批量建单元对话框（第二轮验收任务 A7 步骤 7.2；第三轮 C3 前缀自定义）：
 * 选中楼栋上下文内，按「名称前缀 + 起始序号 + 数量」生成连续单元名
 * （前缀默认取所在楼栋名、可编辑清空，如楼栋「1号楼」默认生成 1号楼1单元/1号楼2单元），
 * 前端循环调既有 createUnit——逐个失败不中断，结束汇报成功/失败数。
 * 与整栋创建/批量生成房屋同模式（大表单治理：紧凑对话框 + 名称预览）。
 */
const props = defineProps<{
  /** 目标楼栋（树选中上下文，打开前已确定） */
  building: IBuilding | null
}>()

const visible = defineModel<boolean>({ required: true })
/** saved 携带目标楼栋 ID，父层刷新树/统计并展开该楼栋 */
const emit = defineEmits<{ saved: [buildingId: number] }>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const progress = ref(0)

const form = reactive({
  prefix: '',
  startNo: 1,
  count: 2,
  description: ''
})

const rules: FormRules = {
  prefix: [{ required: true, message: '请输入单元名前缀', trigger: 'blur' }],
  count: [{ required: true, message: '请输入创建数量', trigger: 'blur' }]
}

/** 将生成的单元名（前缀 + 序号 + 单元），预览与循环创建共用同一来源 */
const generatedNames = computed<string[]>(() => {
  if (!form.prefix.trim()) return []
  const names: string[] = []
  for (let i = 0; i < form.count; i += 1) {
    names.push(`${form.prefix.trim()}${form.startNo + i}单元`)
  }
  return names
})

watch(visible, (value) => {
  if (!value) return
  /* 前缀默认取所在楼栋名（第三轮 C3），用户可直接修改或清空（清空则纯序号） */
  form.prefix = props.building?.name ?? ''
  form.startNo = 1
  form.count = 2
  form.description = ''
  formRef.value?.clearValidate()
})

/** 逐个创建不中断：任一失败记录首个错误信息继续剩余项 */
async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || !props.building) return
  submitting.value = true
  progress.value = 0
  let success = 0
  let fail = 0
  let firstError = ''
  try {
    for (const name of generatedNames.value) {
      try {
        await createUnit({
          buildingId: props.building.id,
          name,
          description: form.description || undefined
        })
        success += 1
      } catch (error) {
        fail += 1
        if (!firstError) {
          firstError = error instanceof Error ? error.message : '创建失败'
        }
      }
      progress.value += 1
    }
    if (fail === 0) {
      ElMessage.success(`成功创建 ${success} 个单元`)
    } else {
      ElMessage.warning(`成功 ${success} 个，失败 ${fail} 个${firstError ? `：${firstError}` : ''}`)
    }
    visible.value = false
    emit('saved', props.building.id)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="批量建单元" width="520px" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="所属楼栋">
        <el-input :model-value="building?.name ?? '-'" disabled />
      </el-form-item>
      <div class="batch-section">生成规则</div>
      <el-form-item label="名称前缀" prop="prefix">
        <el-input
          v-model="form.prefix"
          placeholder="默认取楼栋名，可修改（生成 楼栋名91单元…）"
          maxlength="20"
        />
      </el-form-item>
      <el-form-item label="起始序号" prop="startNo">
        <el-input-number v-model="form.startNo" :min="1" :max="999" />
      </el-form-item>
      <el-form-item label="创建数量" prop="count">
        <el-input-number v-model="form.count" :min="2" :max="10" />
        <span class="batch-hint">2 ~ 10 个</span>
      </el-form-item>
      <el-form-item label="描述">
        <el-input
          v-model="form.description"
          placeholder="选填，全部单元统一"
          maxlength="100"
        />
      </el-form-item>
    </el-form>

    <div class="batch-preview">
      <p class="batch-preview-title">
        将创建 {{ generatedNames.length }} 个单元：
        <span v-if="generatedNames.length === 0" class="batch-preview-empty">请先填写名称前缀</span>
      </p>
      <div v-if="generatedNames.length > 0" class="batch-preview-chips">
        <span v-for="name in generatedNames" :key="name" class="batch-chip">{{ name }}</span>
      </div>
    </div>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        {{ submitting ? `创建中（${progress}/${generatedNames.length}）` : '确定创建' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.batch-section {
  margin: 0 0 var(--spacing-md);
  padding-left: 2px;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.batch-hint {
  margin-left: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.batch-preview {
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.batch-preview-title {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  line-height: 1.6;
  color: var(--color-text-secondary);
}

.batch-preview-empty {
  color: var(--color-text-disabled);
}

.batch-preview-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-xs);
}

.batch-chip {
  padding: 2px 10px;
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  font-size: var(--font-size-xs);
  color: var(--color-primary);
}
</style>
