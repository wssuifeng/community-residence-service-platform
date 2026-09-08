<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createFeedback,
  uploadFeedbackAttachment
} from '@/api/feedback'
import type { FeedbackCategory } from '@/types/modules/feedback'
import { feedbackCategoryLabels } from '@/types/modules/feedback'
import { getMyProfile, getResidentResidenceList } from '@/api/resident'
import { getHouse, getUnit, getBuilding } from '@/api/community'
import { useUserStore } from '@/store/user'
import FileUploader from '@/components/common/FileUploader.vue'

/**
 * 提交反馈：类别/标题/内容/附件；
 * communityId 由当前居住关系链（房屋→单元→楼栋→社区）推导，
 * 附件在创建反馈后按 URL 取回文件补传（uploadFeedbackAttachment 需 feedbackId）
 */
const router = useRouter()
const userStore = useUserStore()

const categoryOptions = Object.entries(feedbackCategoryLabels).map(([value, label]) => ({
  value: value as FeedbackCategory,
  label
}))

const form = ref({
  category: 'SUGGESTION' as FeedbackCategory,
  title: '',
  content: '',
  contactPhone: '',
  isAnonymous: false
})
const attachmentUrls = ref<string[]>([])

const submitting = ref(false)
const communityId = ref<number | null>(null)
const communityLoading = ref(true)

const rules = {
  category: [{ required: true, message: '请选择反馈类别', trigger: 'change' }],
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 100, message: '标题不超过 100 字', trigger: 'blur' }
  ],
  content: [
    { required: true, message: '请描述您的问题或建议', trigger: 'blur' },
    { max: 2000, message: '内容不超过 2000 字', trigger: 'blur' }
  ],
  contactPhone: [
    {
      pattern: /^1[3-9]\d{9}$/,
      message: '手机号格式不正确',
      trigger: 'blur'
    }
  ]
}

const formRef = ref()
const canSubmit = computed(() => !communityLoading.value && communityId.value !== null)

/** 由居住关系推导所属社区：居住关系 → 房屋 → 单元 → 楼栋 → communityId */
async function resolveCommunityId(): Promise<void> {
  communityLoading.value = true
  try {
    const userId = userStore.user?.id
    if (userId === undefined) throw new Error('登录信息缺失')
    const relations = await getResidentResidenceList(userId, { page: 1, size: 1 })
    const relation = relations.records[0]
    if (!relation) throw new Error('未找到居住关系')
    const house = await getHouse(relation.houseId)
    const unit = await getUnit(house.unitId)
    const building = await getBuilding(unit.buildingId)
    communityId.value = building.communityId
  } catch {
    communityId.value = null
  } finally {
    communityLoading.value = false
  }
}

/** URL 取回文件，补传为反馈附件（接口约束：附件上传需先有 feedbackId） */
async function uploadAttachments(feedbackId: number): Promise<void> {
  for (const url of attachmentUrls.value) {
    try {
      const response = await fetch(url)
      const blob = await response.blob()
      const fileName = url.split('/').pop() ?? 'attachment'
      await uploadFeedbackAttachment(feedbackId, new File([blob], fileName, { type: blob.type }))
    } catch {
      /* 单个附件失败不阻断提交，用户可在详情页重试 */
      ElMessage.warning(`附件 ${url.split('/').pop() ?? ''} 关联失败，可在详情页重新上传`)
    }
  }
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (communityId.value === null) {
    ElMessage.error('未能确定您所属的社区，请先完成入住或稍后再试')
    return
  }
  submitting.value = true
  try {
    const feedback = await createFeedback({
      communityId: communityId.value,
      category: form.value.category,
      title: form.value.title.trim(),
      content: form.value.content.trim(),
      contactPhone: form.value.contactPhone.trim() || undefined,
      isAnonymous: form.value.isAnonymous
    })
    await uploadAttachments(feedback.id)
    ElMessage.success('反馈已提交，我们会尽快处理')
    router.push(`/resident/feedbacks/${feedback.id}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '提交失败')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  resolveCommunityId()
  /* 联系电话预填个人资料手机号，方便修改 */
  try {
    const profile = await getMyProfile()
    if (!form.value.contactPhone) form.value.contactPhone = profile.phone
  } catch {
    /* 资料加载失败不阻断表单 */
  }
})
</script>

<template>
  <section class="feedback-create">
    <header class="page-head">
      <h1>提交反馈</h1>
      <p>您的建议对我们很重要</p>
    </header>

    <div class="form-card">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="feedback-form"
      >
        <el-form-item label="反馈类别" prop="category">
          <div class="category-options">
            <button
              v-for="option in categoryOptions"
              :key="option.value"
              type="button"
              class="category-option"
              :class="{ 'is-active': form.category === option.value }"
              @click="form.category = option.value"
            >
              {{ option.label }}
            </button>
          </div>
        </el-form-item>

        <el-form-item label="标题" prop="title">
          <el-input
            v-model="form.title"
            maxlength="100"
            show-word-limit
            placeholder="一句话概括您的问题或建议"
          />
        </el-form-item>

        <el-form-item label="详细内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="6"
            maxlength="2000"
            show-word-limit
            placeholder="请具体描述情况，如发生时间、地点、影响等，便于我们更快处理"
          />
        </el-form-item>

        <el-form-item label="联系电话（可选）" prop="contactPhone">
          <el-input
            v-model="form.contactPhone"
            placeholder="方便我们与您联系，不填则默认账号手机号"
            style="max-width: 320px"
          />
        </el-form-item>

        <el-form-item label="附件（可选）">
          <FileUploader v-model="attachmentUrls" :limit="5" />
          <span class="upload-tip">支持 pdf / doc / docx，单个不超过 10MB，最多 5 个</span>
        </el-form-item>

        <el-form-item>
          <el-checkbox v-model="form.isAnonymous">匿名提交（管理员不可见您的姓名）</el-checkbox>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            round
            :loading="submitting"
            :disabled="!canSubmit"
            @click="handleSubmit"
          >
            {{ communityLoading ? '确认社区信息…' : '提交反馈' }}
          </el-button>
          <el-button size="large" text @click="router.push('/resident/feedbacks')">取消</el-button>
        </el-form-item>
      </el-form>
    </div>
  </section>
</template>

<style scoped>
.feedback-create {
  max-width: 760px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.page-head h1 {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

.form-card {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xl) var(--spacing-lg);
  box-shadow: var(--shadow-sm);
}

.category-options {
  display: flex;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.category-option {
  padding: var(--spacing-sm) var(--spacing-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background-color: #fff;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: all 0.15s;
}

.category-option.is-active {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
  font-weight: var(--font-weight-medium);
}

.upload-tip {
  display: block;
  margin-top: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}
</style>
