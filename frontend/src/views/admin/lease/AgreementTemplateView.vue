<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import {
  createAgreementTemplate,
  deleteAgreementTemplate,
  listAgreementTemplates,
  updateAgreementTemplate,
  updateAgreementTemplateStatus
} from '@/api/agreement'
import { getCommunityList } from '@/api/community'
import { uploadFile } from '@/api/upload'
import type {
  AgreementTemplateStatus,
  IAgreementTemplate,
  IAgreementTemplateDTO
} from '@/types/modules/agreement'
import { useUserStore } from '@/store/user'
import { formatDateTime } from '@/utils/date'

/**
 * 协议模板管理（R64 轻量版）：模板列表 + 新建/编辑（正文 + 变量 + 附件）+ 设默认/启停/删除。
 *
 * 数据口径：模板总量为社区级小数据集（每社区几条），故一次性拉全量后客户端筛选与分页——
 * 后端 communityId 参数无法表达「全局模板（communityId 为 null）」这一筛选档，
 * 全量拉取才能把「全局 / 本社区」两档都做成可筛选项。
 *
 * 权限口径：全局通用模板（communityId 为 null）仅 SUPER_ADMIN 可维护，
 * 普通管理员的归属下拉只列其所绑定社区（后端另有数据级权限兜底）。
 */

const userStore = useUserStore()

/** 归属筛选项的哨兵值：全局通用模板（communityId 为 null，不能用普通数字表达） */
const GLOBAL_SENTINEL = -1

const isSuperAdmin = computed(() => userStore.role === 'SUPER_ADMIN')

const templates = ref<IAgreementTemplate[]>([])
const loading = ref(false)
const communityOptions = ref<{ id: number; name: string }[]>([])
const submitting = ref(false)

/* ---------- 取数 ---------- */

/* 后端分页上限 100：循环翻页取全量（模板为小数据集，50 页保险上限） */
async function load(): Promise<void> {
  loading.value = true
  try {
    const collected: IAgreementTemplate[] = []
    let current = 1
    for (;;) {
      const result = await listAgreementTemplates({ page: current, size: 100 })
      collected.push(...result.records)
      if (result.records.length === 0 || collected.length >= result.total || current >= 50) break
      current += 1
    }
    templates.value = collected
  } catch (error) {
    templates.value = []
    ElMessage.error(error instanceof Error ? error.message : '协议模板加载失败')
  } finally {
    loading.value = false
  }
}

async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 100 })
    communityOptions.value = result.records.map((item) => ({ id: item.id, name: item.name }))
  } catch {
    communityOptions.value = []
  }
}

/** 归属下拉：超管可选全局模板 + 全部社区；普通管理员限本人绑定社区 */
const ownerOptions = computed(() => {
  if (isSuperAdmin.value) {
    return [
      { value: GLOBAL_SENTINEL, label: '全局通用模板' },
      ...communityOptions.value.map((item) => ({ value: item.id, label: item.name }))
    ]
  }
  const bound = userStore.user?.boundCommunities ?? []
  if (bound.length === 0) {
    return communityOptions.value.map((item) => ({ value: item.id, label: item.name }))
  }
  /* 社区名列表可能因权限或请求失败而缺项，用绑定 ID 兜底生成选项，避免下拉空白导致无法保存 */
  const optionMap = new Map(communityOptions.value.map((item) => [item.id, item.name]))
  return bound.map((id) => ({ value: id, label: optionMap.get(id) ?? `社区 ID ${id}` }))
})

/* ---------- 筛选与分页（客户端） ---------- */

/* 归属筛选：el-select 清空回填 undefined（非 null），故用 undefined 表示未筛选 */
const ownerFilter = ref<number | undefined>(undefined)
const statusFilter = ref<AgreementTemplateStatus | ''>('')

const filtered = computed(() => {
  let rows = templates.value
  if (ownerFilter.value === GLOBAL_SENTINEL) rows = rows.filter((row) => row.communityId == null)
  else if (ownerFilter.value != null) {
    rows = rows.filter((row) => row.communityId === ownerFilter.value)
  }
  if (statusFilter.value) rows = rows.filter((row) => row.status === statusFilter.value)
  return rows
})

const page = ref(1)
const size = ref(10)
const pagedRows = computed(() => filtered.value.slice((page.value - 1) * size.value, page.value * size.value))

/* 仅筛选变化时回到第一页（数据重载不重置页码） */
watch([ownerFilter, statusFilter], () => {
  page.value = 1
})

function resetFilters(): void {
  ownerFilter.value = undefined
  statusFilter.value = ''
}

/* ---------- 展示与权限辅助 ---------- */

const isGlobal = (row: IAgreementTemplate): boolean => row.communityId == null

function ownerText(row: IAgreementTemplate): string {
  if (isGlobal(row)) return '全局通用模板'
  return row.communityName || `社区 ID ${row.communityId}`
}

/** 全局模板仅超管可维护；非超管按钮置灰并给出原因 */
function maintainDisabled(row: IAgreementTemplate): boolean {
  return isGlobal(row) && !isSuperAdmin.value
}

const maintainReason = '全局通用模板仅超级管理员可维护'

function attachmentName(row: IAgreementTemplate): string {
  if (row.fileName) return row.fileName
  return urlFileName(row.fileUrl)
}

/** 附件 URL 兜底文件名（后端未回填名称时取 URL 末段） */
function urlFileName(url?: string | null): string {
  if (!url) return ''
  return url.split('/').pop() ?? '查看附件'
}

function fileSizeText(size?: number): string {
  if (size == null) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

/* ---------- 表单弹窗 ---------- */

/** 协议正文可用占位符（后端 AgreementService.render 支持，点击插入到光标处） */
const TEMPLATE_VARIABLES: { token: string; desc: string }[] = [
  { token: '{{社区名称}}', desc: '租约所属社区名称' },
  { token: '{{房屋位置}}', desc: '楼栋-单元-房号' },
  { token: '{{房号}}', desc: '房屋房号' },
  { token: '{{楼层}}', desc: '房屋所在楼层' },
  { token: '{{租客姓名}}', desc: '租客姓名' },
  { token: '{{租期开始}}', desc: '租期开始日期' },
  { token: '{{租期结束}}', desc: '租期结束日期' },
  { token: '{{月租金}}', desc: '月租金额' },
  { token: '{{押金}}', desc: '押金金额' },
  { token: '{{租约编号}}', desc: '租约编号' },
  { token: '{{签约日期}}', desc: '协议生成日期' }
]

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const contentRef = ref<HTMLTextAreaElement | null>(null)
const attachmentInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

const form = reactive<{
  name: string
  communityId: number | null
  content: string
  fileName: string
  fileUrl: string
  fileSize: number | null
  isDefault: boolean
  remark: string
}>({
  name: '',
  communityId: null,
  content: '',
  fileName: '',
  fileUrl: '',
  fileSize: null,
  isDefault: false,
  remark: ''
})

const dialogTitle = computed(() => (editingId.value === null ? '新建协议模板' : '编辑协议模板'))

const formRules: FormRules = {
  name: [{ required: true, message: '请输入模板名称', trigger: 'blur' }],
  communityId: [{ required: true, message: '请选择模板归属', trigger: 'change' }],
  content: [
    {
      /* 正文与附件至少一项：均为空时协议无法渲染出可读文本 */
      validator: (_rule, value: string, callback) => {
        if (!value?.trim() && !form.fileUrl) callback(new Error('协议正文与模板附件至少填写一项'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, {
    name: '',
    /* 默认归属：超管默认全局通用，管理员默认其唯一绑定社区（无可选项时留空由用户选择） */
    communityId: isSuperAdmin.value
      ? GLOBAL_SENTINEL
      : ownerOptions.value.length === 1
        ? ownerOptions.value[0].value
        : null,
    content: '',
    fileName: '',
    fileUrl: '',
    fileSize: null,
    isDefault: false,
    remark: ''
  })
  dialogVisible.value = true
}

function openEdit(row: IAgreementTemplate): void {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    communityId: isGlobal(row) ? GLOBAL_SENTINEL : row.communityId ?? null,
    content: row.content ?? '',
    fileName: row.fileName ?? '',
    fileUrl: row.fileUrl ?? '',
    fileSize: row.fileSize ?? null,
    isDefault: row.isDefault === 1,
    remark: row.remark ?? ''
  })
  dialogVisible.value = true
}

/** 变量插入到正文光标处（无光标位置时追加到末尾） */
function insertVariable(token: string): void {
  const el = contentRef.value
  if (!el) {
    form.content += token
    return
  }
  const start = el.selectionStart ?? form.content.length
  const end = el.selectionEnd ?? start
  form.content = `${form.content.slice(0, start)}${token}${form.content.slice(end)}`
  /* 插入后把光标移到变量之后，便于连续插入 */
  requestAnimationFrame(() => {
    el.focus()
    const caret = start + token.length
    el.setSelectionRange(caret, caret)
  })
}

async function handleAttachmentUpload(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.warning('模板附件不能超过 10MB')
    return
  }
  uploading.value = true
  try {
    const result = await uploadFile(file, 'DOCUMENT')
    form.fileName = result.fileName
    form.fileUrl = result.fileUrl
    form.fileSize = result.fileSize
    ElMessage.success('模板附件已上传，保存后生效')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '附件上传失败')
  } finally {
    uploading.value = false
  }
}

function clearAttachment(): void {
  form.fileName = ''
  form.fileUrl = ''
  form.fileSize = null
}

/** 表单值 → 后端 DTO（归属哨兵值还原为 null，即全局通用模板，仅超管可提交） */
function toPayload(): IAgreementTemplateDTO {
  const owner =
    form.communityId == null || form.communityId === GLOBAL_SENTINEL ? null : form.communityId
  return {
    communityId: owner,
    name: form.name.trim(),
    content: form.content.trim() || undefined,
    fileName: form.fileName || undefined,
    fileUrl: form.fileUrl || undefined,
    fileSize: form.fileSize ?? undefined,
    isDefault: form.isDefault ? 1 : 0,
    remark: form.remark.trim() || undefined
  }
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const owner =
    form.communityId == null || form.communityId === GLOBAL_SENTINEL ? null : form.communityId
  if (!isSuperAdmin.value && owner == null) {
    ElMessage.error('仅超级管理员可创建全局通用模板')
    return
  }
  submitting.value = true
  try {
    if (editingId.value === null) {
      await createAgreementTemplate(toPayload())
      ElMessage.success('协议模板已创建')
    } else {
      await updateAgreementTemplate(editingId.value, toPayload())
      ElMessage.success('协议模板已更新')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

/* ---------- 行操作 ---------- */

/** 设为默认：同范围内唯一，后端会自动取消其他模板的默认标记 */
async function handleSetDefault(row: IAgreementTemplate): Promise<void> {
  try {
    await updateAgreementTemplate(row.id, {
      communityId: isGlobal(row) ? null : row.communityId,
      name: row.name,
      content: row.content,
      fileName: row.fileName,
      fileUrl: row.fileUrl,
      fileSize: row.fileSize,
      isDefault: 1,
      remark: row.remark
    })
    ElMessage.success('已设为默认模板，同范围其他默认标记已自动取消')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '设置默认模板失败')
  }
}

async function handleToggleStatus(row: IAgreementTemplate): Promise<void> {
  const next: AgreementTemplateStatus = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    await updateAgreementTemplateStatus(row.id, next)
    ElMessage.success(next === 'ACTIVE' ? '模板已启用' : '模板已停用，发起协议时不再列出')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '状态更新失败')
  }
}

async function handleDelete(row: IAgreementTemplate): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除模板「${row.name}」？已生成的协议保存了正文快照，删除模板不影响历史协议；此后发起协议将无法再选到本模板。`,
      '删除协议模板',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteAgreementTemplate(row.id)
    ElMessage.success('协议模板已删除')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(() => {
  load()
  loadCommunities()
})
</script>

<template>
  <section class="admin-agreement-template">
    <AdminPageHeader title="协议模板" subtitle="自定义租赁协议：正文占位符渲染 + 模板附件备案">
      <el-button
        v-permission="['ADMIN', 'SUPER_ADMIN']"
        type="primary"
        @click="openCreate"
      >
        <svg class="btn-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
        </svg>
        新建模板
      </el-button>
    </AdminPageHeader>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="page-alert"
      title="模板与协议的关系"
      :description="
        isSuperAdmin
          ? '「全局通用模板」对所有社区可用；各社区模板仅本社区可用。所有社区各保留一份默认模板，设为默认时同范围其他模板自动取消。'
          : '您可维护本人绑定社区的模板；全局通用模板由超级管理员维护，本页只读展示。所有社区各保留一份默认模板，设为默认时同范围其他模板自动取消。'
      "
    />

    <FilterPanel resettable @reset="resetFilters">
      <el-select v-model="ownerFilter" placeholder="全部归属" clearable class="filter-select-wide">
        <el-option label="全局通用模板" :value="GLOBAL_SENTINEL" />
        <el-option
          v-for="item in communityOptions"
          :key="item.id"
          :label="item.name"
          :value="item.id"
        />
      </el-select>
      <el-select v-model="statusFilter" placeholder="全部状态" clearable class="filter-select">
        <el-option label="启用" value="ACTIVE" />
        <el-option label="停用" value="INACTIVE" />
      </el-select>
    </FilterPanel>

    <div class="table-panel">
      <el-table v-loading="loading" :data="pagedRows">
        <el-table-column label="模板名称" min-width="220">
          <template #default="{ row }">
            <div class="name-cell">
              <span class="cell-primary">{{ row.name }}</span>
              <span v-if="row.isDefault === 1" class="default-badge">默认模板</span>
            </div>
            <p v-if="row.remark" class="cell-secondary ellipsis">{{ row.remark }}</p>
          </template>
        </el-table-column>
        <el-table-column label="归属" width="170">
          <template #default="{ row }">
            <span class="owner-chip" :class="{ 'is-global': isGlobal(row) }">{{ ownerText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag
              :label="row.status === 'ACTIVE' ? '启用' : '停用'"
              :type="row.status === 'ACTIVE' ? 'completed' : 'canceled'"
            />
          </template>
        </el-table-column>
        <el-table-column label="模板附件" min-width="180">
          <template #default="{ row }">
            <a
              v-if="row.fileUrl"
              class="file-link"
              :href="row.fileUrl"
              target="_blank"
              rel="noopener"
            >
              {{ attachmentName(row) }}
            </a>
            <span v-else class="muted-text">未上传</span>
            <span v-if="row.fileSize != null" class="cell-size">{{ fileSizeText(row.fileSize) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="正文" width="90">
          <template #default="{ row }">
            <span :class="row.content ? 'cell-primary' : 'muted-text'">
              {{ row.content ? `${row.content.length} 字` : '无' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="150">
          <template #default="{ row }">
            {{ formatDateTime(row.updatedAt || row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.isDefault !== 1"
              v-permission="['ADMIN', 'SUPER_ADMIN']"
              text
              type="primary"
              size="small"
              :disabled="maintainDisabled(row)"
              :title="maintainDisabled(row) ? maintainReason : '设为默认模板'"
              @click="handleSetDefault(row)"
            >
              设为默认
            </el-button>
            <el-button
              v-permission="['ADMIN', 'SUPER_ADMIN']"
              text
              type="primary"
              size="small"
              :disabled="maintainDisabled(row)"
              :title="maintainDisabled(row) ? maintainReason : ''"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-permission="['ADMIN', 'SUPER_ADMIN']"
              text
              size="small"
              :disabled="maintainDisabled(row)"
              :title="maintainDisabled(row) ? maintainReason : ''"
              @click="handleToggleStatus(row)"
            >
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
            <el-button
              v-permission="['ADMIN', 'SUPER_ADMIN']"
              text
              type="danger"
              size="small"
              :disabled="maintainDisabled(row)"
              :title="maintainDisabled(row) ? maintainReason : ''"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无协议模板，点击右上角「新建模板」开始配置" :image-size="80" />
        </template>
      </el-table>

      <Pagination v-model:page="page" v-model:size="size" :total="filtered.length" />
    </div>

    <!-- 新建/编辑模板 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="760px" top="6vh">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="模板名称" prop="name">
          <el-input v-model="form.name" placeholder="如：社区房屋租赁协议（标准版）" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="归属" prop="communityId">
          <el-select
            v-model="form.communityId"
            :disabled="!isSuperAdmin"
            placeholder="选择归属社区"
            style="width: 100%"
          >
            <el-option
              v-for="item in ownerOptions"
              :key="String(item.value)"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <p class="field-hint">
            {{
              isSuperAdmin
                ? '「全局通用模板」= 所有社区可用，仅超管可维护'
                : '归属限本人绑定的社区（全局通用模板由超级管理员维护）'
            }}
          </p>
        </el-form-item>

        <el-form-item label="协议正文" prop="content">
          <textarea
            ref="contentRef"
            v-model="form.content"
            class="content-input"
            rows="10"
            placeholder="填写协议正文，可在右侧点击变量插入占位符，如：甲方（出租方）{{社区名称}}……"
          />
          <p class="field-hint">正文与模板附件至少填写一项；正文中的占位符会在发起协议时渲染成快照存库。</p>
        </el-form-item>

        <el-form-item label="可用变量">
          <div class="variable-list">
            <button
              v-for="item in TEMPLATE_VARIABLES"
              :key="item.token"
              type="button"
              class="variable-chip"
              :title="item.desc"
              @click="insertVariable(item.token)"
            >
              <span class="variable-token">{{ item.token }}</span>
              <span class="variable-desc">{{ item.desc }}</span>
            </button>
          </div>
        </el-form-item>

        <el-form-item label="模板附件">
          <div class="attachment-block">
            <input
              ref="attachmentInput"
              type="file"
              hidden
              accept=".pdf,.doc,.docx,.txt"
              @change="handleAttachmentUpload"
            />
            <template v-if="form.fileUrl">
              <a class="file-link" :href="form.fileUrl" target="_blank" rel="noopener">
                {{ form.fileName || urlFileName(form.fileUrl) }}
              </a>
              <span v-if="form.fileSize != null" class="cell-size">{{ fileSizeText(form.fileSize) }}</span>
              <el-button size="small" :loading="uploading" @click="attachmentInput?.click()">替换</el-button>
              <el-button size="small" @click="clearAttachment">清除</el-button>
            </template>
            <el-button v-else size="small" :loading="uploading" @click="attachmentInput?.click()">
              {{ uploading ? '上传中…' : '上传模板文件（pdf/doc/docx/txt，≤10MB）' }}
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" />
          <span class="field-hint inline">同范围内唯一：设为默认后，本范围内其他模板的默认标记由后端自动取消</span>
        </el-form-item>

        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.btn-icon {
  width: 14px;
  height: 14px;
  margin-right: var(--spacing-xs);
  vertical-align: -2px;
}

.page-alert {
  margin-bottom: var(--spacing-md);
}

.filter-select {
  width: 140px;
}

.filter-select-wide {
  width: 180px;
}

.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.name-cell {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  min-width: 0;
}

.cell-primary {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.cell-secondary {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-size {
  margin-left: var(--spacing-sm);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.muted-text {
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

/* 默认模板徽章：即时可见的视觉反馈 */
.default-badge {
  flex-shrink: 0;
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
  font-weight: var(--font-weight-medium);
}

/* 归属标签：全局通用模板用强调色与本社区模板区分 */
.owner-chip {
  display: inline-block;
  padding: 2px var(--spacing-sm);
  border-radius: var(--radius-pill);
  font-size: var(--font-size-xs);
  line-height: var(--line-height-tight);
  color: var(--color-text-secondary);
  background-color: var(--color-bg-subtle);
}

.owner-chip.is-global {
  color: var(--color-primary);
  background-color: var(--color-primary-bg);
  font-weight: var(--font-weight-medium);
}

.file-link {
  color: var(--color-primary);
  font-size: var(--font-size-sm);
  word-break: break-all;
}

/* 正文编辑区：等宽字体便于对齐占位符与条款编号 */
.content-input {
  width: 100%;
  min-height: 200px;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-bg);
  font-family: var(--font-family-mono);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-relaxed);
  color: var(--color-text-primary);
  resize: vertical;
  outline: none;
}

.content-input:focus {
  border-color: var(--color-primary);
  background-color: var(--color-bg-subtle);
}

.field-hint {
  flex-basis: 100%;
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  line-height: var(--line-height-normal);
}

.field-hint.inline {
  margin-left: var(--spacing-md);
}

/* 变量清单：点击插入到正文光标处 */
.variable-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  width: 100%;
}

.variable-chip {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-xs);
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background-color: var(--color-bg-subtle);
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease;
}

.variable-chip:hover {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.variable-token {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  color: var(--color-primary);
}

.variable-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.attachment-block {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  width: 100%;
}
</style>
