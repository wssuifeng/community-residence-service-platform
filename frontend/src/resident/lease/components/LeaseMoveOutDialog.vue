<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createMoveOutApplication } from '@/common/lease/api/leaseMoveOut'
import type { ILeaseRecord } from '@/common/lease/types/lease'

/**
 * 申请退租弹窗（R70/V20）：居民对**本人已生效租约**发起自助退租，
 * 填计划搬出日期与原因后落地一条待审核申请，由小区管理方审核。
 *
 * 约束与口径：
 * - 仅 ACTIVE 租约可发起（调用方负责只在该状态露出入口，后端 LEASE_STATUS_INVALID 兜底）；
 * - 同一租约同时仅允许一条待审核申请，重复提交由后端拒绝（MOVE_OUT_APPLICATION_PENDING），
 *   故本弹窗不做本地去重，一律以后端错误提示为准；
 * - 通过后由后端联动租约→已搬出、居住关系终止、房屋置空置，前端只负责发起。
 */

const props = defineProps<{
  /** 目标租约（弹窗打开期间由调用方保证非空） */
  lease: ILeaseRecord | null
  modelValue: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 提交成功，调用方据此刷新租约列表（出现待审核退租申请） */
  success: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const formRef = ref<FormInstance>()
const submitting = ref(false)

const form = reactive<{ moveOutDate: string; reason: string }>({
  moveOutDate: '',
  reason: ''
})

/** 搬出日期下限：今天（不早于今天提交才有意义；实际搬迁日期由管理方审核确认） */
const todayISO = (() => {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
})()

const rules: FormRules = {
  moveOutDate: [
    { required: true, message: '请选择计划搬出日期', trigger: 'change' },
    {
      validator: (_rule, value: string, callback) => {
        if (value && value < todayISO) callback(new Error('搬出日期不能早于今天'))
        else callback()
      },
      trigger: 'change'
    }
  ]
}

async function handleSubmit(): Promise<void> {
  if (!props.lease) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await createMoveOutApplication(props.lease.id, {
      moveOutDate: form.moveOutDate,
      reason: form.reason.trim() || undefined
    })
    ElMessage.success('退租申请已提交，等待小区管理方审核')
    visible.value = false
    emit('success')
  } catch (error) {
    /* 已有待审核申请 / 租约状态不允许等业务错误直接展示后端 message */
    ElMessage.error(error instanceof Error ? error.message : '退租申请提交失败')
  } finally {
    submitting.value = false
  }
}

/* 每次打开重置表单，避免沿用上一次的日期与原因 */
watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      form.moveOutDate = ''
      form.reason = ''
      formRef.value?.clearValidate()
    }
  }
)
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`申请退租 · ${lease?.houseLocation ?? ''}`"
    width="460px"
    :close-on-click-modal="false"
  >
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      class="form-alert"
      title="提交后由小区管理方审核"
      description="审核通过后租约转为「已搬出」，居住关系终止、房屋置空置、房源按管理方选择回收或下架；审核驳回时租约保持已生效。同一租约同时只能有一条待审核申请。"
    />

    <dl v-if="lease" class="lease-brief">
      <div>
        <dt>房屋</dt>
        <dd>{{ lease.houseLocation || '—' }}</dd>
      </div>
      <div>
        <dt>当前租期</dt>
        <dd class="mono">{{ lease.startDate }} ~ {{ lease.endDate }}</dd>
      </div>
    </dl>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
      <el-form-item label="搬出日期" prop="moveOutDate">
        <el-date-picker
          v-model="form.moveOutDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="计划搬出的日期"
          style="width: 100%"
        />
      </el-form-item>
      <el-form-item label="退租原因">
        <el-input
          v-model="form.reason"
          type="textarea"
          :rows="3"
          maxlength="500"
          show-word-limit
          placeholder="选填，如工作变动、换租等，便于管理方审核"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button round @click="visible = false">取消</el-button>
      <el-button type="primary" round :loading="submitting" @click="handleSubmit">提交申请</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-alert {
  margin-bottom: var(--spacing-md);
}

.lease-brief {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-sm) var(--spacing-md);
  margin: 0 0 var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
}

.lease-brief dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: 2px;
}

.lease-brief dd {
  margin: 0;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.mono {
  font-family: var(--font-family-mono);
}
</style>
