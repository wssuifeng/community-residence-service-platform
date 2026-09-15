<script setup lang="ts">
/** 后端 Jackson 不解析带 Z 的 ISO 时间，统一转本地无时区格式 */
function toLocalIso(date: Date): string {
  const pad = (value: number): string => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createNotice,
  deleteNotice,
  getNotice,
  listNoticeViewers,
  listNotices,
  publishNotice,
  updateNotice,
  withdrawNotice
} from '@/api/notice'
import { getCommunityList, getBuildingList } from '@/api/community'
import type {
  INotice,
  INoticeSaveRequest,
  INoticeSaveTargetItem,
  INoticeViewer,
  NoticePriority,
  NoticeType,
  TargetAudience
} from '@/types/modules/notice'
import {
  noticePriorityLabels,
  noticeStatusLabels,
  noticeTypeLabels,
  targetAudienceLabels
} from '@/types/modules/notice'
import { formatDate, formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import { useUserStore } from '@/store/user'

/**
 * 公告管理（对照设计稿 design-mockups/admin/06-公告管理.png，主从双栏）：
 * 左栏公告卡列表（keyword 真实参数 + 服务端分页），
 * 右栏查看态（元信息 + 正文 + 发布范围 + 查看记录抽屉 + 状态操作）/
 * 编辑态（原创建/编辑表单整体迁入）/空态。
 * 原 NoticeListView/NoticeCreateView/NoticeDetailView 收编于此（任务 7）；
 * ?action=create 深链为任务 1 redirect 契约，编辑态经 ?action=edit&id= 同步。
 * 表单 priority/type/expireTime 照常提交（D2 裁决，V12 后端已接收）；
 * DEF-039：V9 后补 isPinned 置顶开关 + targets 多社区/楼栋定向编辑 +
 * 左栏 priority 筛选（排序由后端 is_pinned DESC 负责，前端不重排）。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/* ===================== 图标（24×24 stroke path） ===================== */

const ICONS = {
  search: ['M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14z', 'M21 21l-4.35-4.35'],
  chevron: ['M9 6l6 6-6 6'],
  plus: ['M12 5v14', 'M5 12h14'],
  viewers: [
    'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    'M2 21v-1a6 6 0 0 1 6-6h2a6 6 0 0 1 6 6v1',
    'M16 3.5a4 4 0 0 1 0 7',
    'M20 14a6 6 0 0 1 2 4.5V21'
  ]
}

/* ===================== 左栏：公告列表 ===================== */

const notices = ref<INotice[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
/** 优先级筛选（后端真实参数 priority，DEF-031/V12） */
const filterPriority = ref<NoticePriority | ''>('')

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listNotices({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      priority: filterPriority.value || undefined
    })
    notices.value = result.records
    total.value = result.total
  } catch {
    notices.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  load()
}

/** 置顶判定：isPinned 真实字段（0/1，V9 起 VO 返回）优先，旧布尔命名留兜底
 *  （优先级是展示属性、置顶是排序属性，二者后端已分工，priority 不再参与判定） */
function isPinned(notice: INotice): boolean {
  return notice.isPinned === 1 || notice.pinned === true
}

/** 状态 → StatusTag 语义色 */
function statusTagType(status: INotice['status']): 'pending' | 'completed' | 'canceled' | 'rejected' {
  if (status === 'PUBLISHED') return 'completed'
  if (status === 'DRAFT') return 'pending'
  if (status === 'EXPIRED') return 'canceled'
  return 'rejected'
}

/** 列表卡短日期（对照稿 09-08 发布） */
function shortPublishDate(iso: string | null): string {
  const full = formatDate(iso)
  return full === '-' ? '-' : full.slice(5)
}

/* ===================== 右栏模式：查看 / 创建 / 编辑 ===================== */

type PanelMode = 'view' | 'create' | 'edit'
const mode = ref<PanelMode>('view')
const selectedId = ref<number | null>(null)
const current = ref<INotice | null>(null)
const detailLoading = ref(false)
const editingId = ref<number | null>(null)

/** 右栏 URL 同步：view 清 action/id；create 写 ?action=create（任务 1 redirect 契约） */
function syncQuery(next: PanelMode, id?: number): void {
  const query: Record<string, string> = {}
  for (const [key, value] of Object.entries(route.query)) {
    if (key !== 'action' && key !== 'id' && typeof value === 'string') query[key] = value
  }
  if (next === 'create') {
    query.action = 'create'
  } else if (next === 'edit' && id !== undefined) {
    query.action = 'edit'
    query.id = String(id)
  }
  router.replace({ query })
}

/* 外部 URL 变化（前进/后退）跟随：与本地态一致时不动作，避免自激 */
watch(
  () => route.query,
  (query) => {
    const action = query.action
    if (action === 'create') {
      if (mode.value !== 'create') enterCreate(false)
    } else if (action === 'edit') {
      const id = Number(query.id)
      if (Number.isFinite(id) && id > 0 && !(mode.value === 'edit' && editingId.value === id)) {
        enterEdit(id, false)
      }
    } else if (mode.value !== 'view') {
      mode.value = 'view'
      editingId.value = null
    }
  }
)

async function loadDetail(id: number): Promise<void> {
  detailLoading.value = true
  try {
    current.value = await getNotice(id)
    selectedId.value = id
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '公告加载失败')
    current.value = null
    selectedId.value = null
  } finally {
    detailLoading.value = false
  }
}

/** 选中列表项 → 右栏查看态（编辑/创建中切换时先离开并清理 URL） */
async function select(notice: INotice): Promise<void> {
  if (mode.value !== 'view') {
    mode.value = 'view'
    editingId.value = null
    syncQuery('view')
  }
  selectedId.value = notice.id
  await loadDetail(notice.id)
}

function enterCreate(writeUrl = true): void {
  editingId.value = null
  resetForm()
  mode.value = 'create'
  if (writeUrl) syncQuery('create')
  nextTick(() => formRef.value?.clearValidate())
}

async function enterEdit(id: number, writeUrl = true): Promise<void> {
  formLoading.value = true
  try {
    const notice: INotice = await getNotice(id)
    if (notice.status !== 'DRAFT') {
      ElMessage.warning('仅草稿状态的公告可编辑')
      mode.value = 'view'
      editingId.value = null
      syncQuery('view')
      return
    }
    editingId.value = id
    selectedId.value = id
    mode.value = 'edit'
    form.value.title = notice.title
    form.value.content = notice.content
    form.value.type = notice.type ?? 'ANNOUNCEMENT'
    form.value.priority = notice.priority ?? 'NORMAL'
    form.value.targetAudience = notice.targetAudience ?? 'ALL'
    form.value.communityId = notice.communityId
    form.value.broadcast = notice.communityId === null
    form.value.publishTime = notice.publishTime ? new Date(notice.publishTime) : null
    form.value.expireTime = notice.expireTime ? new Date(notice.expireTime) : null
    /* DEF-039 回显：置顶开关（0/1 → 布尔）与 targets 拆分回两组选择
       （V9 起 VO 真实返回；无 targets = 广播/单社区旧语义，保持空选择） */
    form.value.isPinned = notice.isPinned === 1
    form.value.targetCommunityIds = notice.targets
      .filter((item) => item.targetType === 'COMMUNITY')
      .map((item) => item.targetId)
    form.value.targetBuildingIds = notice.targets
      .filter((item) => item.targetType === 'BUILDING')
      .map((item) => item.targetId)
    /* 楼栋选项回显：拿不到社区列表时用 VO targetName 补位（楼栋归属社区不在
       编辑者绑定范围外时无社区选项可选，name 兜底保证已选项可读） */
    const knownBuildings = new Set(buildingOptions.value.map((item) => item.id))
    const fallbackBuildings = notice.targets
      .filter((item) => item.targetType === 'BUILDING' && item.targetName !== null
        && !knownBuildings.has(item.targetId))
      .map((item) => ({ id: item.targetId, name: item.targetName as string }))
    if (fallbackBuildings.length > 0) {
      buildingOptions.value = [...buildingOptions.value, ...fallbackBuildings]
    }
    /* 编辑回显的社区选项：接口列表为空时用 VO 自带名称补位（原编辑页经独立路由进入
       未加载绑定社区，社区名显示为裸 ID，迁移时顺修） */
    if (notice.communityId !== null) {
      const known = boundCommunities.value.some((item) => item.id === notice.communityId)
      if (!known && notice.communityName) {
        boundCommunities.value = [
          ...boundCommunities.value,
          { id: notice.communityId, name: notice.communityName }
        ]
      }
    }
    if (writeUrl) syncQuery('edit', id)
    nextTick(() => formRef.value?.clearValidate())
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '公告加载失败')
    mode.value = 'view'
    editingId.value = null
    syncQuery('view')
  } finally {
    formLoading.value = false
  }
}

/** 取消编辑：有选中公告回其查看态，否则右栏空态 */
function cancelEdit(): void {
  mode.value = 'view'
  editingId.value = null
  syncQuery('view')
  nextTick(() => formRef.value?.clearValidate())
}

/* ===================== 查看态操作（发布/下线/删除，二次确认沿用现状） ===================== */

/** 发布：立即发布（publishTime 取当前时间） */
async function handlePublishCurrent(): Promise<void> {
  const notice = current.value
  if (!notice) return
  try {
    await ElMessageBox.confirm('确定发布该公告？发布后居民端立即可见。', `发布公告「${notice.title}」`, {
      type: 'info',
      confirmButtonText: '发布',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await publishNotice(notice.id, { publishTime: toLocalIso(new Date()) })
    ElMessage.success('公告已发布')
    await Promise.all([loadDetail(notice.id), load()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  }
}

/** 下线（撤回）：需填写撤回原因 */
async function handleWithdrawCurrent(): Promise<void> {
  const notice = current.value
  if (!notice) return
  let reason: string
  try {
    const result = await ElMessageBox.prompt('请输入撤回原因', `撤回「${notice.title}」`, {
      type: 'warning',
      confirmButtonText: '下线',
      cancelButtonText: '取消',
      inputValidator: (value: string) =>
        value.trim().length > 0 ? true : '撤回原因不能为空'
    })
    reason = result.value.trim()
  } catch {
    return
  }
  try {
    await withdrawNotice(notice.id, { reason })
    ElMessage.success('公告已撤回')
    await Promise.all([loadDetail(notice.id), load()])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '下线失败')
  }
}

/** 删除：确认后清空右栏选中 */
async function handleDeleteCurrent(): Promise<void> {
  const notice = current.value
  if (!notice) return
  try {
    await ElMessageBox.confirm(`确定删除公告「${notice.title}」？删除后不可恢复。`, '删除公告', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await deleteNotice(notice.id)
    ElMessage.success('公告已删除')
    current.value = null
    selectedId.value = null
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

/* ===================== 查看记录抽屉（原详情页查看回执迁入） ===================== */

const viewerVisible = ref(false)
const viewers = ref<INoticeViewer[]>([])
const viewerTotal = ref(0)
const viewerPage = ref(1)
const viewerSize = ref(10)
const viewersLoading = ref(false)

async function loadViewers(): Promise<void> {
  if (current.value === null) return
  viewersLoading.value = true
  try {
    const result = await listNoticeViewers(current.value.id, {
      page: viewerPage.value,
      size: viewerSize.value
    })
    viewers.value = result.records
    viewerTotal.value = result.total
  } catch {
    viewers.value = []
    viewerTotal.value = 0
  } finally {
    viewersLoading.value = false
  }
}

function openViewers(): void {
  viewerVisible.value = true
  viewerPage.value = 1
  loadViewers()
}

/* ===================== 编辑态表单（原创建/编辑页整体迁入，D2：字段照常提交） ===================== */

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
  /** 置顶开关（isPinned 0/1，R25 v1.2） */
  isPinned: false,
  /** 社区级定向目标（COMMUNITY targets；空=按上方发布范围单社区） */
  targetCommunityIds: [] as number[],
  /** 楼栋级定向目标（BUILDING targets；可跨社区累加） */
  targetBuildingIds: [] as number[],
  publishTime: new Date() as Date | null,
  expireTime: null as Date | null
})

/* 楼栋定向：选社区加载楼栋，选项跨社区累加（编辑回显不同社区楼栋时可复选） */
const buildingCommunityId = ref<number | null>(null)
const buildingOptions = ref<{ id: number; name: string }[]>([])
const buildingsLoading = ref(false)

async function handleBuildingCommunityChange(communityId: number | null): Promise<void> {
  if (communityId === null) return
  buildingsLoading.value = true
  try {
    const result = await getBuildingList(communityId, { page: 1, size: 200 })
    const known = new Set(buildingOptions.value.map((item) => item.id))
    buildingOptions.value = [
      ...buildingOptions.value,
      ...result.records
        .filter((item) => !known.has(item.id))
        .map((item) => ({ id: item.id, name: item.name }))
    ]
  } catch {
    /* 楼栋加载失败静默：定向范围仍可仅用社区级 */
  } finally {
    buildingsLoading.value = false
  }
}

/* 全系统广播与定向范围互斥：勾选广播时清空定向选择（广播 = 无 targets） */
watch(
  () => form.value.broadcast,
  (broadcast) => {
    if (broadcast) {
      form.value.targetCommunityIds = []
      form.value.targetBuildingIds = []
    }
  }
)

const submitting = ref(false)
const publishing = ref(false)
const formLoading = ref(false)

const rules = {
  title: [
    { required: true, message: '请输入公告标题', trigger: 'blur' },
    { max: 100, message: '标题不超过 100 字', trigger: 'blur' }
  ],
  content: [{ required: true, message: '请输入公告内容', trigger: 'blur' }],
  publishTime: [{ required: true, message: '请选择发布时间', trigger: 'change' }]
}

const formRef = ref()

function resetForm(): void {
  form.value.title = ''
  form.value.content = ''
  form.value.type = 'ANNOUNCEMENT'
  form.value.priority = 'NORMAL'
  form.value.targetAudience = 'ALL'
  form.value.communityId = isSuperAdmin.value ? null : (boundCommunities.value[0]?.id ?? null)
  form.value.broadcast = false
  form.value.isPinned = false
  form.value.targetCommunityIds = []
  form.value.targetBuildingIds = []
  buildingCommunityId.value = null
  buildingOptions.value = []
  form.value.publishTime = new Date()
  form.value.expireTime = null
}

/** 组装保存请求体（targets 优先；两组定向均空时回退 communityId 单目标旧写法） */
function buildRequest(): INoticeSaveRequest {
  const targets: INoticeSaveTargetItem[] = [
    ...form.value.targetCommunityIds.map((id) => ({ targetType: 'COMMUNITY' as const, targetId: id })),
    ...form.value.targetBuildingIds.map((id) => ({ targetType: 'BUILDING' as const, targetId: id }))
  ]
  return {
    communityId: isSuperAdmin.value && form.value.broadcast
      ? null
      : form.value.communityId,
    targets: targets.length > 0 ? targets : undefined,
    isPinned: form.value.isPinned ? 1 : 0,
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

/** 保存后刷新左栏并选中新项（新建翻回第 1 页：列表按发布时间倒序，新公告可见） */
async function afterSaved(noticeId: number, created: boolean): Promise<void> {
  mode.value = 'view'
  editingId.value = null
  syncQuery('view')
  if (created) page.value = 1
  await load()
  await loadDetail(noticeId)
}

/** 保存不发布（草稿） */
async function handleSaveDraft(): Promise<void> {
  if (!(await validate())) return
  submitting.value = true
  try {
    const data = buildRequest()
    if (mode.value === 'edit' && editingId.value !== null) {
      await updateNotice(editingId.value, data)
      ElMessage.success('草稿已保存')
      await afterSaved(editingId.value, false)
    } else {
      const created = await createNotice(data)
      ElMessage.success('草稿已保存')
      await afterSaved(created.id, true)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

/** 保存并发布：先保存（新建或更新草稿），再调发布接口（publishTime 超前为定时发布） */
async function handleSavePublish(): Promise<void> {
  if (!(await validate())) return
  publishing.value = true
  try {
    const data = buildRequest()
    let noticeId: number
    let created = false
    if (mode.value === 'edit' && editingId.value !== null) {
      await updateNotice(editingId.value, data)
      noticeId = editingId.value
    } else {
      const result = await createNotice(data)
      noticeId = result.id
      created = true
    }
    await publishNotice(noticeId, { publishTime: data.publishTime })
    /* 定时判定按真实时间戳比较（原迁入代码用本地无时区串与 UTC 串做字典序比较，
       UTC+8 下立即发布也恒判为「定时」，迁移顺修） */
    ElMessage.success(
      new Date(data.publishTime).getTime() > Date.now() ? '定时发布已设置' : '公告已发布'
    )
    await afterSaved(noticeId, created)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发布失败')
  } finally {
    publishing.value = false
  }
}

/** 主按钮文案：发布时间超前为定时发布（沿用现状判定） */
const publishButtonLabel = computed(() =>
  form.value.publishTime && form.value.publishTime.getTime() > Date.now() ? '定时发布' : '保存并发布'
)

/* ===================== 初始化：深链消费 + 首屏自动选中第一条 ===================== */

onMounted(async () => {
  /* ADMIN 编辑/创建都需要绑定社区选项（原编辑页缺失此加载致社区名显示裸 ID，迁移顺修） */
  if (!isSuperAdmin.value) await loadBoundCommunities()
  const action = route.query.action
  if (action === 'create') {
    enterCreate(false)
  } else if (action === 'edit') {
    const id = Number(route.query.id)
    if (Number.isFinite(id) && id > 0) await enterEdit(id, false)
  }
  /* 深链进编辑态时左栏列表照常加载，保证可切换选中 */
  await load()
  /* 对照稿首屏：右栏默认展示第一条公告（列表为空才落空态引导） */
  if (mode.value === 'view' && current.value === null && notices.value.length > 0) {
    await loadDetail(notices.value[0].id)
  }
})
</script>

<template>
  <div class="admin-page">
    <AdminPageHeader title="公告管理" subtitle="面向居民的通知与公告发布管理">
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="enterCreate()">
        <svg class="btn-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path v-for="(d, i) in ICONS.plus" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        发布公告
      </el-button>
    </AdminPageHeader>

    <div class="notice-master">
      <!-- 左栏：公告卡列表 -->
      <aside class="list-panel">
        <header class="list-head">
          <h3 class="panel-title">公告列表</h3>
          <span class="list-count">共 {{ total }} 条</span>
        </header>

        <div class="list-search">
          <el-input
            v-model="keyword"
            placeholder="搜索公告标题/内容"
            clearable
            @keyup.enter="handleSearch"
            @clear="handleSearch"
          >
            <template #prefix>
              <svg class="search-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path v-for="(d, i) in ICONS.search" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </template>
          </el-input>
          <!-- DEF-039：priority 真实过滤参数（DEF-031/V12），取值与表单优先级枚举对齐 -->
          <el-select
            v-model="filterPriority"
            placeholder="优先级"
            clearable
            class="priority-filter"
            @change="handleSearch"
          >
            <el-option
              v-for="(label, value) in noticePriorityLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </div>

        <div v-loading="loading" class="notice-cards">
          <el-empty
            v-if="!loading && notices.length === 0"
            :description="keyword ? '未找到匹配的公告' : '暂无公告，点击右上角「发布公告」新建'"
            :image-size="72"
          />
          <button
            v-for="notice in notices"
            :key="notice.id"
            type="button"
            class="notice-card"
            :class="{ selected: selectedId === notice.id }"
            :aria-pressed="selectedId === notice.id"
            @click="select(notice)"
          >
            <span v-if="isPinned(notice)" class="pin-badge">置顶</span>
            <span class="card-main">
              <span class="card-title">{{ notice.title }}</span>
              <span class="card-meta">阅读 {{ notice.viewCount }} · {{ shortPublishDate(notice.publishTime) }} 发布</span>
            </span>
            <span class="card-side">
              <StatusTag
                :label="noticeStatusLabels[notice.status as keyof typeof noticeStatusLabels]"
                :type="statusTagType(notice.status)"
              />
              <svg class="card-chevron" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path v-for="(d, i) in ICONS.chevron" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
          </button>
        </div>

        <Pagination
          v-if="total > 0"
          v-model:page="page"
          v-model:size="size"
          :total="total"
          layout="prev, pager, next"
          @update:page="load"
          @update:size="load"
        />
      </aside>

      <!-- 右栏：查看 / 编辑 / 空态 -->
      <section class="detail-panel">
        <!-- 查看态 -->
        <template v-if="mode === 'view' && current">
          <div v-loading="detailLoading" class="view-body">
            <header class="view-head">
              <div class="view-heading">
                <h2 class="view-title">{{ current.title }}</h2>
                <span v-if="isPinned(current)" class="pin-badge">置顶</span>
                <StatusTag
                  :label="noticeStatusLabels[current.status]"
                  :type="statusTagType(current.status)"
                />
              </div>
              <div class="view-actions">
                <el-button
                  v-if="current.status === 'DRAFT'"
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  type="primary"
                  @click="handlePublishCurrent"
                >
                  发布
                </el-button>
                <el-button
                  v-if="current.status === 'DRAFT'"
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  @click="enterEdit(current.id)"
                >
                  编辑
                </el-button>
                <el-button
                  v-if="current.status === 'PUBLISHED'"
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  type="warning"
                  @click="handleWithdrawCurrent"
                >
                  下线
                </el-button>
                <el-button
                  v-if="current.status === 'DRAFT' || current.status === 'WITHDRAWN'"
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  type="danger"
                  plain
                  @click="handleDeleteCurrent"
                >
                  删除
                </el-button>
              </div>
            </header>

            <dl class="meta-row">
              <div class="meta-item">
                <dt class="meta-label">发布人</dt>
                <dd class="meta-value">{{ current.publisherName || '—' }}</dd>
              </div>
              <div class="meta-item">
                <dt class="meta-label">发布时间</dt>
                <dd class="meta-value">{{ formatDateTime(current.publishTime) }}</dd>
              </div>
              <div class="meta-item">
                <dt class="meta-label">有效期至</dt>
                <dd class="meta-value">{{ formatDateTime(current.endTime ?? current.expireTime) }}</dd>
              </div>
              <div class="meta-item">
                <dt class="meta-label">阅读数</dt>
                <dd class="meta-value">{{ current.viewCount }}</dd>
              </div>
              <div class="meta-item">
                <dt class="meta-label">发布范围</dt>
                <dd class="meta-value">
                  <!-- DEF-039：targets 真实返回后逐条展示（社区/楼栋分组名），无目标=全系统广播 -->
                  <template v-if="current.targets && current.targets.length > 0">
                    <span
                      v-for="target in current.targets"
                      :key="target.targetType + target.targetId"
                      class="scope-chip"
                    >
                      {{ target.targetName ?? (target.targetType === 'COMMUNITY' ? `社区#${target.targetId}` : `楼栋#${target.targetId}`) }}
                    </span>
                  </template>
                  <span v-else class="scope-chip">全系统广播</span>
                </dd>
              </div>
            </dl>

            <div class="view-content">
              <p v-for="(paragraph, index) in current.content.split(/\n+/).filter(Boolean)" :key="index">
                {{ paragraph }}
              </p>
            </div>

            <button type="button" class="viewer-entry" @click="openViewers">
              <svg class="viewer-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path v-for="(d, i) in ICONS.viewers" :key="i" :d="d" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              查看记录
              <span class="viewer-count">{{ current.viewCount }} 次阅读</span>
            </button>
          </div>
        </template>

        <!-- 编辑态（创建 / 编辑） -->
        <template v-else-if="mode === 'create' || mode === 'edit'">
          <div v-loading="formLoading" class="edit-body">
            <header class="edit-head">
              <h2 class="edit-title">{{ mode === 'edit' ? '编辑公告' : '发布公告' }}</h2>
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

              <el-form-item label="公告正文" prop="content">
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
                <span class="form-tip">展示属性；置顶展示由下方置顶开关控制</span>
              </el-form-item>

              <!-- DEF-039：置顶开关（R25 v1.2，置顶列表排最前） -->
              <el-form-item label="置顶">
                <el-switch v-model="form.isPinned" />
                <span class="form-tip">开启后公告在列表中置顶展示（排序最前）</span>
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
                <span class="form-tip">公告面向定向范围内的全部居民</span>
              </el-form-item>

              <el-form-item label="发布范围">
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

              <!-- DEF-039：定向目标（R25 v1.2 多社区/楼栋定向）；
                   空选 = 仅上方发布范围单社区；全系统广播时隐藏（广播无 targets） -->
              <el-form-item v-if="!form.broadcast" label="定向目标">
                <div class="targets-editor">
                  <el-select
                    v-model="form.targetCommunityIds"
                    multiple
                    collapse-tags
                    collapse-tags-tooltip
                    clearable
                    placeholder="定向社区（可多选）"
                    class="target-select"
                  >
                    <el-option
                      v-for="community in boundCommunities"
                      :key="community.id"
                      :label="community.name"
                      :value="community.id"
                    />
                  </el-select>
                  <div class="building-row">
                    <el-select
                      v-model="buildingCommunityId"
                      placeholder="选择社区加载楼栋"
                      clearable
                      style="width: 180px"
                      @change="handleBuildingCommunityChange"
                    >
                      <el-option
                        v-for="community in boundCommunities"
                        :key="community.id"
                        :label="community.name"
                        :value="community.id"
                      />
                    </el-select>
                    <el-select
                      v-model="form.targetBuildingIds"
                      multiple
                      collapse-tags
                      collapse-tags-tooltip
                      clearable
                      filterable
                      placeholder="定向楼栋（可跨社区多选）"
                      class="target-select"
                      :loading="buildingsLoading"
                    >
                      <el-option
                        v-for="building in buildingOptions"
                        :key="building.id"
                        :label="building.name"
                        :value="building.id"
                      />
                    </el-select>
                  </div>
                  <span class="form-tip is-block">
                    定向社区/楼栋会在发布范围基础上叠加触达；两者均不选 = 仅发布范围所选社区
                  </span>
                </div>
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
                <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" :loading="publishing" @click="handleSavePublish">
                  {{ publishButtonLabel }}
                </el-button>
                <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" :loading="submitting" @click="handleSaveDraft">保存不发布</el-button>
                <el-button text @click="cancelEdit">取消</el-button>
              </el-form-item>
            </el-form>
          </div>
        </template>

        <!-- 空态 -->
        <template v-else>
          <el-empty description="在左侧选择公告查看详情，或点击右上角「发布公告」新建" />
        </template>
      </section>
    </div>

    <!-- 查看记录抽屉（原详情页查看回执：统计 + 分页列表） -->
    <el-drawer v-model="viewerVisible" title="查看记录" size="520px">
      <div class="viewer-drawer-body">
        <div class="viewer-stats">
          <div class="stat-item">
            <span class="stat-value">{{ current?.viewCount ?? 0 }}</span>
            <span class="stat-label">已读次数</span>
          </div>
          <div class="stat-item">
            <span class="stat-value">{{ viewerTotal }}</span>
            <span class="stat-label">已读人数</span>
          </div>
        </div>

        <el-table v-loading="viewersLoading" :data="viewers" stripe>
          <el-table-column prop="residentId" label="居民ID" width="100" />
          <el-table-column prop="residentName" label="居民姓名" min-width="160" />
          <el-table-column label="查看时间" width="200">
            <template #default="{ row }">{{ formatDateTime(row.viewedAt) }}</template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!viewersLoading && viewers.length === 0"
          description="暂无居民查看记录"
          :image-size="60"
        />

        <Pagination
          v-model:page="viewerPage"
          v-model:size="viewerSize"
          :total="viewerTotal"
          layout="prev, pager, next"
          @update:page="loadViewers"
          @update:size="loadViewers"
        />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.btn-icon {
  width: 14px;
  height: 14px;
  margin-right: 2px;
}

/* ---------- 主从双栏（左 380px : 右 1fr，两栏各自白卡） ---------- */

.notice-master {
  display: grid;
  grid-template-columns: 380px minmax(0, 1fr);
  gap: var(--spacing-lg);
  align-items: start;
}

.list-panel,
.detail-panel {
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.panel-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

/* ---------- 左栏 ---------- */

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.list-count {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.list-search {
  display: flex;
  gap: var(--spacing-xs);
  margin: var(--spacing-md) 0;
}

.priority-filter {
  width: 96px;
  flex-shrink: 0;
}

.search-icon {
  width: 14px;
  height: 14px;
  color: var(--color-text-secondary);
}

.notice-cards {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-height: 120px;
}

.notice-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  border: none;
  border-radius: var(--radius-md);
  background: none;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s ease;
}

.notice-card:hover {
  background-color: var(--color-bg-hover);
}

.notice-card.selected {
  background-color: var(--color-primary-bg);
}

.card-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.card-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.notice-card.selected .card-title {
  color: var(--color-primary);
}

.card-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.card-side {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-shrink: 0;
}

.card-chevron {
  width: 14px;
  height: 14px;
  color: var(--color-text-disabled);
}

/* 置顶红标（对照稿）：实心语义红 + 卡色文字（V9 起 isPinned 真实返回，DEF-039 点亮） */
.pin-badge {
  flex-shrink: 0;
  padding: 1px var(--spacing-xs);
  border-radius: var(--radius-sm);
  background-color: var(--color-danger);
  color: var(--admin-card-bg);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  line-height: var(--line-height-tight);
}

/* ---------- 右栏：查看态 ---------- */

.view-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-height: 320px;
}

.view-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.view-heading {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  min-width: 0;
}

.view-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-tight);
  word-break: break-word;
}

.view-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-shrink: 0;
  flex-wrap: wrap;
}

.meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-md) var(--spacing-xl);
  margin: 0;
  padding: var(--spacing-md) var(--spacing-lg);
  background-color: var(--color-bg-subtle);
  border-radius: var(--radius-md);
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  min-width: 96px;
}

.meta-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.meta-value {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}

/* 多 targets 逐条 chip 展示（DEF-039），meta-item 内自动换行 */
.meta-value .scope-chip {
  margin: 0 var(--spacing-xs) var(--spacing-xs) 0;
}

.meta-value .scope-chip:last-child {
  margin-right: 0;
}

.scope-chip {
  display: inline-flex;
  align-items: center;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}

.view-content {
  padding: var(--spacing-lg);
  background-color: var(--color-bg-subtle);
  border-radius: var(--radius-md);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
}

.view-content p {
  margin: 0 0 var(--spacing-md);
  white-space: pre-wrap;
  word-break: break-word;
}

.view-content p:last-child {
  margin-bottom: 0;
}

.viewer-entry {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  align-self: flex-start;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  cursor: pointer;
  transition: background-color 0.15s ease, border-color 0.15s ease;
}

.viewer-entry:hover {
  background-color: var(--color-primary-bg);
  border-color: var(--color-primary);
}

.viewer-icon {
  width: 15px;
  height: 15px;
}

.viewer-count {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* ---------- 右栏：编辑态 ---------- */

.edit-body {
  min-height: 320px;
}

.edit-head {
  margin-bottom: var(--spacing-lg);
}

.edit-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
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

/* ---------- 定向目标编辑（DEF-039：社区多选 + 社区→楼栋多选，紧凑两行） ---------- */

.targets-editor {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
}

.target-select {
  width: 360px;
  max-width: 100%;
}

.building-row {
  display: flex;
  gap: var(--spacing-xs);
  flex-wrap: wrap;
}

.form-tip.is-block {
  margin-left: 0;
}

/* ---------- 查看记录抽屉 ---------- */

.viewer-drawer-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.viewer-stats {
  display: flex;
  gap: var(--spacing-lg);
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-xs);
  min-width: 120px;
  padding: var(--spacing-md);
  background-color: var(--color-primary-bg);
  border-radius: var(--radius-md);
}

.stat-value {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-primary);
}

.stat-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* ---------- 响应式 ---------- */

@media (max-width: 1199px) {
  .notice-master {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
