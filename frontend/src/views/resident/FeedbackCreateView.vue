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
    <nav class="breadcrumb">
      <router-link to="/resident/feedbacks">我的反馈</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">提交反馈</span>
    </nav>

    <!-- 居中限宽表单大容器 -->
    <div class="form-card">
      <header class="form-head">
        <h1>提交反馈</h1>
        <p>您的建议对我们很重要，我们会认真对待每一条反馈</p>
      </header>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="feedback-form"
      >
        <el-form-item label="反馈类型" prop="category">
          <div class="category-options">
            <button
              v-for="option in categoryOptions"
              :key="option.value"
              type="button"
              class="category-option"
              :class="{ 'is-active': form.category === option.value }"
              @click="form.category = option.value"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <template v-if="option.value === 'SUGGESTION'">
                  <path d="M9 18h6M10 21h4" />
                  <path d="M12 3a6 6 0 0 0-4 10.5c.8.7 1 1.5 1 2.5h6c0-1 .2-1.8 1-2.5A6 6 0 0 0 12 3z" />
                </template>
                <template v-else-if="option.value === 'COMPLAINT'">
                  <path d="M12 9v4M12 17h.01" />
                  <path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
                </template>
                <template v-else>
                  <circle cx="12" cy="12" r="9" />
                  <path d="M9.1 9a3 3 0 0 1 5.8 1c0 2-3 2.6-3 4" />
                  <path d="M12 17h.01" />
                </template>
              </svg>
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
          <!-- 虚线上传区：内部为现有 FileUploader（逻辑与数量限制不变） -->
          <div class="upload-zone">
            <div class="upload-zone-head">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="17 8 12 3 7 8" />
                <line x1="12" y1="3" x2="12" y2="15" />
              </svg>
              <span>点击上传，最多 5 个（pdf / doc / docx，单个不超过 10MB）</span>
            </div>
            <FileUploader v-model="attachmentUrls" :limit="5" />
          </div>
        </el-form-item>

        <el-form-item class="anonymous-item">
          <el-switch v-model="form.isAnonymous" />
          <span class="anonymous-text">
            <strong>匿名提交</strong>
            <span>开启后，管理员处理您的反馈时不可见您的姓名</span>
          </span>
        </el-form-item>

        <div class="form-actions">
          <el-button text size="large" @click="router.push('/resident/feedbacks')">取消</el-button>
          <el-button
            type="primary"
            size="large"
            class="submit-btn"
            :loading="submitting"
            :disabled="!canSubmit"
            @click="handleSubmit"
          >
            {{ communityLoading ? '确认社区信息…' : '提交反馈' }}
          </el-button>
        </div>
      </el-form>
    </div>
  </section>
</template>

<style scoped>
.feedback-create {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
}

.breadcrumb-sep {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
}

/* 居中限宽表单大容器 */
.form-card {
  max-width: 780px;
  width: 100%;
  margin: 0 auto;
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xl) var(--spacing-xxl);
  box-shadow: var(--shadow-sm);
}

.form-head {
  text-align: center;
  margin-bottom: var(--spacing-lg);
}

.form-head h1 {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.form-head p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

/* 反馈类型：可选胶囊卡片（图标 + 名称，选中蓝色描边浅底） */
.category-options {
  display: flex;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.category-option {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: #fff;
  color: var(--color-text-primary);
  font-size: var(--font-size-sm);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.category-option svg {
  width: 18px;
  height: 18px;
  color: var(--color-primary);
}

.category-option.is-active {
  border-color: var(--color-primary);
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
  font-weight: var(--font-weight-medium);
}

/* 虚线上传区 */
.upload-zone {
  width: 100%;
  padding: var(--spacing-md);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg);
}

.upload-zone-head {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) 0 var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.upload-zone-head svg {
  width: 20px;
  height: 20px;
  color: var(--color-primary);
}

/* 匿名开关 + 说明文字 */
.anonymous-item :deep(.el-form-item__content) {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.anonymous-text {
  display: flex;
  flex-direction: column;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.anonymous-text span {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.form-actions {
  display: flex;
  justify-content: center;
  gap: var(--spacing-md);
  margin-top: var(--spacing-md);
}

.submit-btn {
  min-width: 280px;
}
</style>
