<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ImageUploader from '@/components/common/ImageUploader.vue'
import { getMyProfile, getResidentResidenceList } from '@/api/resident'
import { getServiceCategoryTree, submitWorkOrder, uploadWorkOrderAttachment } from '@/api/workorder'
import type {
  IServiceCategoryTreeNode,
  WorkOrderPriority
} from '@/types/modules/workorder'
import { workOrderPriorityLabels } from '@/types/modules/workorder'
import type { ResidenceRelationStatus } from '@/types/modules/resident'

/** 提交工单（UI设计.md §3.1 / 4.1.2）：类别选择 + 描述 + 紧急程度 + 图片上传 */

const router = useRouter()

/* 平铺后的类别选项：顶级作为分组，子级作为选项（无子级时顶级自身为选项） */
interface CategoryOption {
  value: number
  label: string
}
interface CategoryGroup {
  label: string
  options: CategoryOption[]
}

const categoryGroups = ref<CategoryGroup[]>([])
const categoryLoading = ref(false)
const submitting = ref(false)
const images = ref<string[]>([])

const form = reactive({
  categoryId: null as number | null,
  title: '',
  content: '',
  priority: 'NORMAL' as WorkOrderPriority,
  contactPhone: ''
})

const priorityOptions = (Object.keys(workOrderPriorityLabels) as WorkOrderPriority[]).map((value) => ({
  value,
  label: workOrderPriorityLabels[value]
}))

/* 居民端社区归属从本人 ACTIVE 居住关系推导（登录响应 boundCommunities 对居民恒为空） */
const communityId = ref<number | null>(null)

async function loadCommunityId(): Promise<void> {
  try {
    const profile = await getMyProfile()
    const relations = await getResidentResidenceList(profile.id, { page: 1, size: 5 })
    const active = relations.records.find((item) => item.status === ('ACTIVE' as ResidenceRelationStatus))
    communityId.value = active?.communityId ?? null
  } catch {
    communityId.value = null
  }
}

async function loadCategories(): Promise<void> {
  await loadCommunityId()
  if (!communityId.value) {
    ElMessage.error('未获取到所属社区信息，请重新登录后再试')
    return
  }
  categoryLoading.value = true
  try {
    const tree = await getServiceCategoryTree(communityId.value as number)
    categoryGroups.value = tree.map((node: IServiceCategoryTreeNode) => {
      if (node.children.length > 0) {
        return {
          label: node.name,
          options: node.children.map((child) => ({ value: child.id, label: child.name }))
        }
      }
      return { label: node.name, options: [{ value: node.id, label: node.name }] }
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '服务类别加载失败')
  } finally {
    categoryLoading.value = false
  }
}

/* 预填个人资料中的联系电话，减少输入 */
async function loadProfile(): Promise<void> {
  try {
    const profile = await getMyProfile()
    if (profile.phone) form.contactPhone = profile.phone
  } catch {
    /* 资料加载失败不阻塞提单，联系电话可手填 */
  }
}

/* ImageUploader 产出的是通用上传 URL；工单附件须挂到具体工单，创建成功后回捞并转存 */
async function attachImages(orderId: number, urls: string[]): Promise<void> {
  let failed = 0
  for (const url of urls) {
    try {
      const blob = await (await fetch(url)).blob()
      const fileName = url.split('/').pop() ?? 'attachment.png'
      await uploadWorkOrderAttachment(orderId, new File([blob], fileName, { type: blob.type || 'image/png' }))
    } catch {
      failed += 1
    }
  }
  if (failed > 0) ElMessage.warning(`${failed} 张图片关联工单失败，可联系管家补充`)
}

async function handleSubmit(): Promise<void> {
  if (!form.categoryId) {
    ElMessage.warning('请选择服务类别')
    return
  }
  if (!form.title.trim()) {
    ElMessage.warning('请填写工单标题')
    return
  }
  if (!form.content.trim()) {
    ElMessage.warning('请描述您遇到的问题')
    return
  }
  if (!form.contactPhone.trim()) {
    ElMessage.warning('请填写联系电话')
    return
  }

  submitting.value = true
  try {
    const order = await submitWorkOrder({
      categoryId: form.categoryId as number,
      title: form.title.trim(),
      content: form.content.trim(),
      contactPhone: form.contactPhone.trim(),
      priority: form.priority
    })
    await attachImages(order.id, images.value)
    ElMessage.success('工单提交成功，请耐心等待受理')
    router.push(`/resident/work-orders/${order.id}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '工单提交失败')
  } finally {
    submitting.value = false
  }
}

/** Date → 本地时区 ISO 8601（无 Z 后缀，后端按本地时间解析） */

onMounted(() => {
  loadCategories()
  loadProfile()
})
</script>

<template>
  <section class="work-order-create">
    <header class="page-header">
      <div>
        <h1 class="page-title">提交工单</h1>
        <p class="page-subtitle">描述越清楚，处理越高效；可上传现场照片帮助服务人员定位问题</p>
      </div>
      <el-button text @click="router.back()">返回</el-button>
    </header>

    <el-form class="create-form" label-position="top" @submit.prevent>
      <el-form-item label="服务类别" required>
        <el-select
          v-model="form.categoryId"
          :loading="categoryLoading"
          placeholder="请选择服务类别"
          size="large"
          class="category-select"
        >
          <el-option-group v-for="group in categoryGroups" :key="group.label" :label="group.label">
            <el-option v-for="option in group.options" :key="option.value" :label="option.label" :value="option.value" />
          </el-option-group>
        </el-select>
      </el-form-item>

      <el-form-item label="工单标题" required>
        <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="一句话概括问题，如：厨房水管漏水" size="large" />
      </el-form-item>

      <el-form-item label="问题描述" required>
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="5"
          maxlength="1000"
          show-word-limit
          placeholder="请描述问题发生的位置、现象、持续时间等"
        />
      </el-form-item>

      <div class="form-row">
        <el-form-item label="紧急程度" required>
          <el-radio-group v-model="form.priority" size="large">
            <el-radio-button v-for="item in priorityOptions" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="联系电话" required>
          <el-input v-model="form.contactPhone" maxlength="20" placeholder="方便服务人员联系您" size="large" />
        </el-form-item>
      </div>

      <el-form-item label="现场照片（可选，最多 6 张）">
        <ImageUploader v-model="images" :limit="6" />
      </el-form-item>

      <div class="form-actions">
        <el-button size="large" @click="router.push('/resident/work-orders')">取消</el-button>
        <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit">提交工单</el-button>
      </div>
    </el-form>
  </section>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-lg);
}

.page-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.page-subtitle {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.create-form {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  max-width: 720px;
}

.category-select,
.appointment-picker {
  width: 100%;
}

.form-row {
  display: flex;
  gap: var(--spacing-lg);
  flex-wrap: wrap;
}

.form-row .el-form-item {
  flex: 1;
  min-width: 240px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-md);
}
</style>
