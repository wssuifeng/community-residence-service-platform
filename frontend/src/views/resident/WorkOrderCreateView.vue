<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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

/** 平铺类别选项（胶囊卡片用）：叶子选项 + 所属分组名 */
const flatCategoryOptions = computed(() =>
  categoryGroups.value.flatMap((group) =>
    group.options.map((option) => ({ ...option, group: group.label }))
  )
)

/* 类别图标：后端类别无图标字段，按序号轮换线性图标 */
const categoryIcons = ['wrench', 'spray', 'leaf', 'gear']

const form = reactive({
  categoryId: null as number | null,
  title: '',
  content: '',
  address: '',
  priority: 'NORMAL' as WorkOrderPriority,
  contactPhone: ''
})

const priorityOptions = (Object.keys(workOrderPriorityLabels) as WorkOrderPriority[]).map((value) => ({
  value,
  label: workOrderPriorityLabels[value]
}))

/* 居民端社区归属从本人 ACTIVE 居住关系推导（登录响应 boundCommunities 对居民恒为空）；
   顺带取房屋位置预填服务地址 */
const communityId = ref<number | null>(null)

async function loadCommunityId(): Promise<void> {
  try {
    const profile = await getMyProfile()
    const relations = await getResidentResidenceList(profile.id, { page: 1, size: 5 })
    const active = relations.records.find((item) => item.status === ('ACTIVE' as ResidenceRelationStatus))
    communityId.value = active?.communityId ?? null
    if (active && !form.address) form.address = active.houseLocation
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
      address: form.address.trim() || undefined,
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
    <nav class="breadcrumb">
      <router-link to="/resident/work-orders">我的工单</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">提交工单</span>
    </nav>

    <!-- 居中限宽表单大容器 -->
    <div class="form-container">
      <header class="form-head">
        <h1 class="page-title">提交工单</h1>
        <p class="page-subtitle">描述你遇到的问题，我们会尽快安排处理</p>
      </header>

      <el-form class="create-form" label-position="top" @submit.prevent>
        <el-form-item label="服务类别" required>
          <div v-loading="categoryLoading" class="category-cards">
            <button
              v-for="(option, index) in flatCategoryOptions"
              :key="option.value"
              type="button"
              class="category-card"
              :class="{ active: form.categoryId === option.value }"
              @click="form.categoryId = option.value"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <template v-if="categoryIcons[index % categoryIcons.length] === 'wrench'">
                  <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
                </template>
                <template v-else-if="categoryIcons[index % categoryIcons.length] === 'spray'">
                  <path d="M4 20c0-6 4-10 10-10l6 6c0 2-1 4-3 4H4z" />
                  <path d="M14 10 20 4M16 8l2 2" />
                </template>
                <template v-else-if="categoryIcons[index % categoryIcons.length] === 'leaf'">
                  <path d="M11 20A7 7 0 0 1 4 13c0-5 4-9 16-10-1 12-5 16-9 17z" />
                  <path d="M4 20c4-4 8-8 12-11" />
                </template>
                <template v-else>
                  <circle cx="12" cy="12" r="3" />
                  <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1" />
                </template>
              </svg>
              <span>{{ option.label }}</span>
            </button>
          </div>
        </el-form-item>

        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="一句话描述问题" size="large" />
        </el-form-item>

        <el-form-item label="问题描述" required>
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            maxlength="1000"
            show-word-limit
            placeholder="请详细描述问题情况、位置等"
          />
        </el-form-item>

        <el-form-item label="服务地址">
          <el-input v-model="form.address" maxlength="100" placeholder="问题发生的地址" size="large" />
        </el-form-item>

        <el-form-item label="紧急程度" required>
          <div class="priority-cards">
            <button
              v-for="item in priorityOptions"
              :key="item.value"
              type="button"
              class="priority-card"
              :class="{ active: form.priority === item.value }"
              :data-priority="item.value"
              @click="form.priority = item.value"
            >
              <span class="priority-dot" aria-hidden="true"></span>
              {{ item.label }}
            </button>
          </div>
        </el-form-item>

        <el-form-item label="联系电话" required>
          <el-input v-model="form.contactPhone" maxlength="20" placeholder="方便服务人员联系您" size="large" />
        </el-form-item>

        <el-form-item label="附件照片">
          <!-- 虚线上传区：内部为现有 ImageUploader（逻辑与数量限制不变） -->
          <div class="upload-zone">
            <div class="upload-zone-head">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                <circle cx="12" cy="13" r="4" />
              </svg>
              <span>点击上传，最多 6 张</span>
            </div>
            <ImageUploader v-model="images" :limit="6" />
          </div>
        </el-form-item>

        <div class="form-actions">
          <el-button text size="large" @click="router.push('/resident/work-orders')">取消</el-button>
          <el-button type="primary" size="large" class="submit-btn" :loading="submitting" @click="handleSubmit">
            提交工单
          </el-button>
        </div>
      </el-form>
    </div>
  </section>
</template>

<style scoped>
.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-md);
  font-size: var(--font-size-sm);
}

.breadcrumb-sep {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
}

/* 居中限宽表单大容器 */
.form-container {
  max-width: 780px;
  margin: 0 auto;
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-xl) var(--spacing-xxl);
  box-shadow: var(--shadow-sm);
}

.form-head {
  text-align: center;
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

/* 服务类别：可选胶囊卡片（图标 + 名称，选中蓝色描边浅底） */
.category-cards {
  display: flex;
  gap: var(--spacing-md);
  flex-wrap: wrap;
  width: 100%;
  min-height: 56px;
}

.category-card {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md) var(--spacing-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fff;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.category-card svg {
  width: 20px;
  height: 20px;
  color: var(--color-primary);
}

.category-card.active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg);
  font-weight: var(--font-weight-medium);
}

/* 紧急程度：单选胶囊（紧急红色） */
.priority-cards {
  display: flex;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.priority-card {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fff;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}

.priority-dot {
  width: 12px;
  height: 12px;
  border-radius: var(--radius-circle);
  border: 2px solid var(--color-text-disabled);
}

.priority-card.active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg);
}

.priority-card.active .priority-dot {
  border-color: var(--color-primary);
  background: var(--color-primary);
}

.priority-card[data-priority='URGENT'].active {
  border-color: var(--color-danger);
  background: rgba(239, 68, 68, 0.08);
  color: var(--color-danger);
}

.priority-card[data-priority='URGENT'].active .priority-dot {
  border-color: var(--color-danger);
  background: var(--color-danger);
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
