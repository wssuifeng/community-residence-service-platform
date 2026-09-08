<script setup lang="ts">
/** 后端 Jackson 不解析带 Z 的 ISO 时间，统一转本地无时区格式 */
function toLocalIso(date: Date): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createNotice,
  updateNotice,
  getNotice,
  publishNotice
} from '@/api/notice'
import { getCommunityList } from '@/api/community'
import type {
  INotice,
  INoticeSaveRequest,
  NoticePriority,
  NoticeType,
  TargetAudience
} from '@/types/modules/notice'
import {
  noticeTypeLabels,
  noticePriorityLabels,
  targetAudienceLabels
} from '@/types/modules/notice'
import { useUserStore } from '@/store/user'

/**
 * 创建/编辑公告：编辑模式经 ?id= 进入（仅草稿可改，updateNotice 约束），
 * 支持存草稿与直接发布（publishTime 超前即为定时发布）
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const editId = ref<number | null>(
  route.query.id ? Number(route.query.id) : null
)
const isEdit = computed(() => editId.value !== null)

const isSuperAdmin = computed(() => userStore.role === 'SUPER_ADMIN')
/** 绑定社区 ID 列表（登录响应）；社区名经社区列表接口补全（后端仅返回 ID 数组） */
const boundCommunityIds = computed(() => userStore.user?.boundCommunities ?? [])
const boundCommunities = ref<{ id: number; name: string }[]>([])

async function loadBoundCommunities(): Promise<void> {
  if (boundCommunityIds.value.length === 0) return
  try {
    const result = await getCommunityList({ page: 1, size: 200 })
    boundCommunities.value = result.records.filter(
      (item) => (boundCommunityIds.value as number[]).includes(item.id)
    )
  } catch {
    boundCommunities.value = []
  }
}

const form = ref({
  title: '',
  content: '',
  type: 'ANNOUNCEMENT' as NoticeType,
  priority: 'NORMAL' as NoticePriority,
  targetAudience: 'ALL' as TargetAudience,
  communityId: null as number | null,
  /** 全系统广播开关（仅 SUPER_ADMIN 可见） */
  broadcast: false,
  publishTime: new Date() as Date | null,
  expireTime: null as Date | null
})

const submitting = ref(false)
const publishing = ref(false)
const pageLoading = ref(false)

const rules = {
  title: [
    { required: true, message: '请输入公告标题', trigger: 'blur' },
    { max: 100, message: '标题不超过 100 字', trigger: 'blur' }
  ],
  content: [{ required: true, message: '请输入公告内容', trigger: 'blur' }],
  publishTime: [{ required: true, message: '请选择发布时间', trigger: 'change' }]
}

const formRef = ref()

onMounted(async () => {
  if (editId.value === null) {
    /* ADMIN 默认选中第一个绑定社区（登录响应仅含 ID，先拉社区名） */
    if (!isSuperAdmin.value) {
      await loadBoundCommunities()
      if (boundCommunities.value.length > 0) {
        form.value.communityId = boundCommunities.value[0].id
      }
    }
    return
  }
  pageLoading.value = true
  try {
    const notice: INotice = await getNotice(editId.value)
    if (notice.status !== 'DRAFT') {
      ElMessage.warning('仅草稿状态的公告可编辑')
      router.replace('/admin/notices')
      return
    }
    form.value.title = notice.title
    form.value.content = notice.content
    form.value.type = notice.type
    form.value.priority = notice.priority
    form.value.targetAudience = notice.targetAudience
    form.value.communityId = notice.communityId
    form.value.broadcast = notice.communityId === null
    form.value.publishTime = notice.publishTime ? new Date(notice.publishTime) : null
    form.value.expireTime = notice.expireTime ? new Date(notice.expireTime) : null
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '公告加载失败')
    router.replace('/admin/notices')
  } finally {
    pageLoading.value = false
  }
})

/** 组装保存请求体 */
function buildRequest(): INoticeSaveRequest {
  return {
    communityId: isSuperAdmin.value && form.value.broadcast
      ? null
      : form.value.communityId,
    title: form.value.title.trim(),
    content: form.value.content.trim(),
    type: form.value.type,
    priority: form.value.priority,
    targetAudience: form.value.targetAudience,
    publishTime: toLocalIso(form.value.publishTime ?? new Date()),
    expireTime: form.value.expireTime
      ? toLocalIso(form.value.expireTime)
      : undefined
  }
}

/** 校验 + 社区必选检查（ADMIN 未绑定社区时阻断） */
async function validate(): Promise<boolean> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return false
  if (!(isSuperAdmin.value && form.value.broadcast) && form.value.communityId === null) {
    ElMessage.warning(isSuperAdmin.value ? '请选择发布社区或勾选全系统广播' : '请选择发布社区')
    return false
  }
  return true
}

async function handleSaveDraft(): Promise<void> {
  if (!(await validate())) return
  submitting.value = true
  try {
    const data = buildRequest()
    if (isEdit.value && editId.value !== null) {
      await updateNotice(editId.value, data)
      ElMessage.success('草稿已保存')
    } else {
      await createNotice(data)
      ElMessage.success('草稿已保存')
    }
    router.push('/admin/notices')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

/** 发布：先保存（新建或更新草稿），再调发布接口（publishTime 超前为定时发布） */
async function handlePublish(): Promise<void> {
  if (!(await validate())) return
  publishing.value = true
  try {
    const data = buildRequest()
    let noticeId: number
    if (isEdit.value && editId.value !== null) {
      await updateNotice(editId.value, data)
      noticeId = editId.value
    } else {
      const created = await createNotice(data)
      noticeId = created.id
    }
    await publishNotice(noticeId, { publishTime: data.publishTime })
    ElMessage.success(
      data.publishTime > new Date().toISOString() ? '定时发布已设置' : '公告已发布'
    )
    router.push('/admin/notices')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

function goBack(): void {
  router.push('/admin/notices')
}
</script>

<template>
  <section v-loading="pageLoading" class="notice-create">
    <header class="page-head">
      <h1>{{ isEdit ? '编辑公告' : '创建公告' }}</h1>
      <el-button text @click="goBack">← 返回列表</el-button>
    </header>

    <el-form
      ref="formRef"
      :model="form"
      :rules="rules"
      label-width="100px"
      class="notice-form"
    >
      <el-form-item label="公告标题" prop="title">
        <el-input
          v-model="form.title"
          maxlength="100"
          show-word-limit
          placeholder="请输入公告标题"
        />
      </el-form-item>

      <el-form-item label="公告内容" prop="content">
        <el-input
          v-model="form.content"
          type="textarea"
          :rows="10"
          maxlength="2000"
          show-word-limit
          placeholder="请输入公告正文，空行分段展示"
        />
      </el-form-item>

      <el-form-item label="公告类型">
        <el-select v-model="form.type" style="width: 200px">
          <el-option
            v-for="(label, value) in noticeTypeLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="优先级">
        <el-select v-model="form.priority" style="width: 200px">
          <el-option
            v-for="(label, value) in noticePriorityLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <span class="form-tip">高 / 紧急优先级公告在居民端置顶展示</span>
      </el-form-item>

      <el-form-item label="定向范围">
        <el-select v-model="form.targetAudience" style="width: 200px">
          <el-option
            v-for="(label, value) in targetAudienceLabels"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <span class="form-tip">当前仅支持面向全部居民发布</span>
      </el-form-item>

      <el-form-item label="发布社区">
        <template v-if="isSuperAdmin">
          <el-checkbox v-model="form.broadcast">全系统广播（所有社区）</el-checkbox>
          <el-select
            v-if="!form.broadcast"
            v-model="form.communityId"
            placeholder="选择绑定社区"
            style="width: 260px"
          >
            <el-option
              v-for="community in boundCommunities"
              :key="community.id"
              :label="community.name"
              :value="community.id"
            />
          </el-select>
        </template>
        <template v-else>
          <el-select
            v-model="form.communityId"
            placeholder="选择发布社区（限绑定社区）"
            style="width: 260px"
          >
            <el-option
              v-for="community in boundCommunities"
              :key="community.id"
              :label="community.name"
              :value="community.id"
            />
          </el-select>
          <span v-if="boundCommunities.length === 0" class="form-tip is-warning">
            当前账号未绑定社区，无法发布
          </span>
        </template>
      </el-form-item>

      <el-form-item label="发布时间" prop="publishTime">
        <el-date-picker
          v-model="form.publishTime"
          type="datetime"
          placeholder="选择发布时间（晚于当前时间即为定时发布）"
          style="width: 320px"
        />
      </el-form-item>

      <el-form-item label="有效期至">
        <el-date-picker
          v-model="form.expireTime"
          type="datetime"
          placeholder="留空默认发布时间后 30 天"
          style="width: 320px"
        />
      </el-form-item>

      <el-form-item>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" :loading="submitting" @click="handleSaveDraft">存草稿</el-button>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" :loading="publishing" @click="handlePublish">
          {{ form.publishTime && form.publishTime.getTime() > Date.now() ? '定时发布' : '立即发布' }}
        </el-button>
      </el-form-item>
    </el-form>
  </section>
</template>

<style scoped>
.notice-create {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-lg);
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--spacing-lg);
}

.page-head h1 {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
}

.notice-form {
  max-width: 720px;
}

.form-tip {
  margin-left: var(--spacing-md);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.form-tip.is-warning {
  color: var(--color-warning);
}
</style>
