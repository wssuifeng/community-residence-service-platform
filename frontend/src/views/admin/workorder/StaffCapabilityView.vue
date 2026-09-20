<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import { getCommunityList } from '@/api/community'
import { getServiceCategoryTree } from '@/api/workorder'
import { getStaffCapability, listStaffCapabilities, saveStaffCapability } from '@/api/staff'
import type { ICommunity } from '@/types/modules/community'
import type { IServiceCategoryTreeNode } from '@/types/modules/workorder'
import type { IStaffCapability } from '@/types/modules/staff'
import { userStatusLabels } from '@/types/modules/auth'
import { useUserStore } from '@/store/user'

/**
 * 服务人员能力绑定（V19，C4）：物业长期对接人 → 常驻社区 + 擅长服务类别。
 * 这页是工单派单推荐档位的数据来源（档位 1 = 常驻本社区且擅长该类别），
 * 因此页面首要任务是让管理员一眼看懂「绑了谁、管哪个社区、擅长什么」。
 * 保存为全量覆盖式：勾选集合即最终状态，取消勾选等于解绑该维度。
 */

const userStore = useUserStore()

/** 社区标签折叠阈值：超过 2 个折叠为 +N，避免多绑定人员撑坏表格列宽 */
const COMMUNITY_TAG_LIMIT = 2

const communities = ref<ICommunity[]>([])

const communityId = ref<number | null>(null)
const categoryId = ref<number | null>(null)
const keyword = ref('')

const page = ref(1)
const size = ref(10)
const total = ref(0)
const records = ref<IStaffCapability[]>([])
const loading = ref(false)

/* ---------------- 社区与类别选项 ---------------- */

const boundCommunityIds = computed(() => userStore.user?.boundCommunities ?? [])

/** 可选社区：管理员限绑定社区，超管回落全量（与后端数据权限同口径） */
async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 100 })
    const bound = boundCommunityIds.value
    communities.value = bound.length > 0 ? result.records.filter((item) => bound.includes(item.id)) : result.records
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '社区列表加载失败')
  }
}

const categoryOptions = ref<{ id: number; name: string; communityName: string }[]>([])

/** 服务类别树拍平（带社区名以便区分跨社区的同名类别） */
function flattenTree(
  trees: IServiceCategoryTreeNode[][],
  names: string[]
): { id: number; name: string; communityName: string }[] {
  const options: { id: number; name: string; communityName: string }[] = []
  const seen = new Set<number>()
  trees.forEach((tree, index) => {
    const communityName = names[index] ?? ''
    const walk = (nodes: IServiceCategoryTreeNode[]): void => {
      for (const node of nodes) {
        if (!seen.has(node.id)) {
          seen.add(node.id)
          options.push({ id: node.id, name: node.name, communityName })
        }
        walk(node.children ?? [])
      }
    }
    walk(tree)
  })
  return options
}

function communityNameOf(id: number): string {
  return communities.value.find((item) => item.id === id)?.name ?? `社区 ${id}`
}

/** 筛选用类别选项：类别按社区划分，取当前可管理社区的全部类别 */
async function loadCategoryOptions(): Promise<void> {
  const ids = boundCommunityIds.value.length > 0 ? boundCommunityIds.value : communities.value.map((item) => item.id)
  if (ids.length === 0) return
  try {
    const trees = await Promise.all(
      ids.map((id) => getServiceCategoryTree(id).catch(() => [] as IServiceCategoryTreeNode[]))
    )
    categoryOptions.value = flattenTree(trees, ids.map(communityNameOf))
  } catch {
    /* 类别选项失败不阻塞列表 */
  }
}

/* ---------------- 列表 ---------------- */

async function fetchList(): Promise<void> {
  loading.value = true
  try {
    const result = await listStaffCapabilities({
      page: page.value,
      size: size.value,
      communityId: communityId.value ?? undefined,
      categoryId: categoryId.value ?? undefined,
      keyword: keyword.value || undefined
    })
    records.value = result.records
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '服务人员列表加载失败')
  } finally {
    loading.value = false
  }
}

const hasFilter = computed(() => !!communityId.value || !!categoryId.value || !!keyword.value)

function handleSearch(): void {
  page.value = 1
  fetchList()
}

function handleReset(): void {
  communityId.value = null
  categoryId.value = null
  keyword.value = ''
  page.value = 1
  fetchList()
}

function handleSizeChange(): void {
  page.value = 1
  fetchList()
}

/* ---------------- 编辑抽屉 ---------------- */

const drawerVisible = ref(false)
const editing = ref<IStaffCapability | null>(null)
const detailLoading = ref(false)
const form = ref<{ communityIds: number[]; categoryIds: number[] }>({ communityIds: [], categoryIds: [] })
const saving = ref(false)

/** 抽屉内可选类别：来自已勾选社区的服务类别树（未选社区则不给选项并提示） */
const drawerCategoryOptions = ref<{ id: number; name: string; communityName: string }[]>([])
const drawerCategoryLoading = ref(false)

async function loadDrawerCategories(): Promise<void> {
  const ids = form.value.communityIds
  if (ids.length === 0) {
    drawerCategoryOptions.value = []
    /* 未绑定社区即无法确定类别归属社区，先清掉类别勾选，避免存下无归属的绑定 */
    form.value.categoryIds = []
    return
  }
  drawerCategoryLoading.value = true
  try {
    const trees = await Promise.all(
      ids.map((id) => getServiceCategoryTree(id).catch(() => [] as IServiceCategoryTreeNode[]))
    )
    drawerCategoryOptions.value = flattenTree(trees, ids.map(communityNameOf))
    /* 社区变更后可能剔除原类别：只保留仍在选项内的勾选 */
    const allowed = new Set(drawerCategoryOptions.value.map((item) => item.id))
    form.value.categoryIds = form.value.categoryIds.filter((item) => allowed.has(item))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '服务类别加载失败')
  } finally {
    drawerCategoryLoading.value = false
  }
}

async function openDrawer(row: IStaffCapability): Promise<void> {
  editing.value = row
  drawerVisible.value = true
  detailLoading.value = true
  form.value = { communityIds: [...row.communityIds], categoryIds: [...row.categoryIds] }
  try {
    const detail = await getStaffCapability(row.staffId)
    editing.value = detail
    form.value = { communityIds: [...detail.communityIds], categoryIds: [...detail.categoryIds] }
    await loadDrawerCategories()
  } catch (error) {
    /* 详情失败保留列表行数据，至少能继续编辑已知绑定 */
    drawerCategoryOptions.value = []
    ElMessage.error(error instanceof Error ? error.message : '能力详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function handleSave(): Promise<void> {
  const target = editing.value
  if (!target) return
  saving.value = true
  try {
    const result = await saveStaffCapability(target.staffId, {
      communityIds: form.value.communityIds,
      categoryIds: form.value.categoryIds
    })
    ElMessage.success(
      `已保存 ${result.realName} 的能力绑定：常驻社区 ${result.communityIds.length} 个 · 擅长类别 ${result.categoryIds.length} 个`
    )
    drawerVisible.value = false
    fetchList()
    loadCategoryOptions()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

/** 社区标签折叠后的完整名称交给原生 title 提示 */
function communityTitle(row: IStaffCapability): string {
  return row.communityNames.length > 0 ? row.communityNames.join('、') : '未绑定常驻社区'
}

function categoryTitle(row: IStaffCapability): string {
  return row.categoryNames.length > 0 ? row.categoryNames.join('、') : '未绑定擅长类别'
}

onMounted(async () => {
  await loadCommunities()
  await loadCategoryOptions()
  await fetchList()
})
</script>

<template>
  <section class="staff-capability">
    <AdminPageHeader
      title="服务人员"
      :subtitle="`物业长期对接人员：绑定常驻社区与擅长的服务类别后，派单时会被优先推荐（当前 ${total} 人）`"
    />

    <p class="page-note">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <circle cx="12" cy="12" r="9" />
        <path d="M12 11v5.5M12 7.8v.2" />
      </svg>
      常驻社区决定派单推荐档位（常驻本社区优先），擅长服务类别决定同类工单优先派给谁；
      两者都显示在派单候选清单上，供调度判断。账号本身在「系统管理 · 用户」中创建。
    </p>

    <FilterPanel resettable @reset="handleReset">
      <el-select
        v-model="communityId"
        placeholder="全部社区"
        clearable
        filterable
        class="filter-select"
        @change="handleSearch"
      >
        <el-option v-for="item in communities" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <el-select
        v-model="categoryId"
        placeholder="全部擅长类别"
        clearable
        filterable
        class="filter-select is-wide"
        @change="handleSearch"
      >
        <el-option
          v-for="item in categoryOptions"
          :key="item.id"
          :label="`${item.name}（${item.communityName}）`"
          :value="item.id"
        />
      </el-select>
      <SearchBar v-model="keyword" placeholder="搜索姓名/账号/电话…" @search="handleSearch" />
    </FilterPanel>

    <div class="table-panel">
      <el-table v-loading="loading" :data="records" class="staff-table">
        <el-table-column label="姓名" width="130">
          <template #default="{ row }">
            <span class="name-cell">{{ row.realName }}</span>
            <StatusTag
              v-if="row.status"
              class="status-cell"
              :label="userStatusLabels[row.status as keyof typeof userStatusLabels] ?? row.status"
              :type="row.status === 'ACTIVE' ? 'completed' : 'rejected'"
            />
          </template>
        </el-table-column>
        <el-table-column prop="username" label="账号" width="130" />
        <el-table-column label="电话" width="130">
          <template #default="{ row }">{{ row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column label="常驻社区" min-width="220">
          <template #default="{ row }">
            <div v-if="row.communityNames.length > 0" class="tag-row" :title="communityTitle(row)">
              <span v-for="name in row.communityNames.slice(0, COMMUNITY_TAG_LIMIT)" :key="name" class="tag is-community">
                {{ name }}
              </span>
              <span v-if="row.communityNames.length > COMMUNITY_TAG_LIMIT" class="tag is-more">
                +{{ row.communityNames.length - COMMUNITY_TAG_LIMIT }}
              </span>
            </div>
            <span v-else class="cell-empty">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="擅长类别" min-width="240">
          <template #default="{ row }">
            <div v-if="row.categoryNames.length > 0" class="tag-row" :title="categoryTitle(row)">
              <span v-for="name in row.categoryNames.slice(0, 3)" :key="name" class="tag is-category">{{ name }}</span>
              <span v-if="row.categoryNames.length > 3" class="tag is-more">+{{ row.categoryNames.length - 3 }}</span>
            </div>
            <span v-else class="cell-empty">未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="openDrawer(row)">编辑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="hasFilter ? '暂无符合条件的服务人员' : '暂无服务人员账号'" :image-size="80">
            <p v-if="!hasFilter" class="empty-tip">
              服务人员为系统用户中角色为「服务人员」的账号，请先在「系统管理 · 用户」中创建；
              若已创建，请检查账号状态是否为正常，以及是否已通过本页绑定社区。
            </p>
          </el-empty>
        </template>
      </el-table>

      <Pagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        @update:page="fetchList"
        @update:size="handleSizeChange"
      />
    </div>

    <el-drawer
      v-model="drawerVisible"
      :title="editing ? `能力绑定 · ${editing.realName}` : '能力绑定'"
      size="500px"
      append-to-body
    >
      <div v-loading="detailLoading" class="drawer-body">
        <p class="drawer-note">
          保存为<strong>全量覆盖</strong>：本次勾选的社区与类别即最终绑定；某一维度全部取消勾选表示清空该维度，
          该人员将不再因常驻社区或擅长类别被优先推荐。
        </p>

        <el-form label-position="top" @submit.prevent>
          <el-form-item label="常驻社区（决定派单档位：常驻本社区优先）">
            <el-select
              v-model="form.communityIds"
              multiple
              filterable
              collapse-tags
              class="full-width"
              placeholder="选择该人员常驻的社区"
              @change="loadDrawerCategories"
            >
              <el-option v-for="item in communities" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>

          <el-form-item label="擅长服务类别（决定同类工单优先派给谁）">
            <el-alert
              v-if="form.communityIds.length === 0"
              type="warning"
              :closable="false"
              title="请先选择常驻社区"
              description="服务类别按社区划分，选定社区后才能列出该社区可绑定的类别。"
              show-icon
            />
            <template v-else>
              <el-select
                v-model="form.categoryIds"
                multiple
                filterable
                collapse-tags
                :loading="drawerCategoryLoading"
                class="full-width"
                placeholder="选择该人员擅长的服务类别"
              >
                <el-option
                  v-for="item in drawerCategoryOptions"
                  :key="item.id"
                  :label="form.communityIds.length > 1 ? `${item.name}（${item.communityName}）` : item.name"
                  :value="item.id"
                />
              </el-select>
              <p v-if="!drawerCategoryLoading && drawerCategoryOptions.length === 0" class="form-hint">
                所选社区尚未配置服务类别，可先在工单调度页的「服务类别」抽屉中维护。
              </p>
            </template>
          </el-form-item>
        </el-form>

        <dl v-if="editing" class="drawer-summary">
          <div>
            <dt>账号</dt>
            <dd>{{ editing.username }}</dd>
          </div>
          <div>
            <dt>电话</dt>
            <dd>{{ editing.phone || '未填写' }}</dd>
          </div>
          <div>
            <dt>当前绑定</dt>
            <dd>{{ communityTitle(editing) }} · {{ categoryTitle(editing) }}</dd>
          </div>
        </dl>
      </div>

      <template #footer>
        <el-button @click="drawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="detailLoading" @click="handleSave">保存绑定</el-button>
      </template>
    </el-drawer>
  </section>
</template>

<style scoped>
.page-note {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-primary-bg);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.page-note svg {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
  margin-top: 3px;
  color: var(--color-primary);
}

.filter-select {
  width: 150px;
}

.filter-select.is-wide {
  width: 230px;
}

.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.staff-table {
  --el-table-border-color: var(--color-border);
}

.name-cell {
  font-weight: var(--font-weight-medium);
}

.status-cell {
  margin-left: var(--spacing-xs);
}

/* 社区/类别标签行：单行不换行，超出交给 +N 与悬浮提示 */
.tag-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
  overflow: hidden;
}

.tag {
  padding: 1px var(--spacing-sm);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-xs);
  white-space: nowrap;
}

.tag.is-community {
  background-color: var(--color-primary-bg);
  color: var(--color-primary-dark);
}

.tag.is-category {
  background-color: var(--color-success-soft);
  color: #047857;
}

.tag.is-more {
  background-color: var(--color-bg-hover);
  color: var(--color-text-secondary);
}

.cell-empty {
  font-size: var(--font-size-sm);
  color: var(--color-text-disabled);
}

.empty-tip {
  max-width: 440px;
  margin: 0 auto;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.drawer-body {
  min-height: 200px;
}

.drawer-note {
  margin: 0 0 var(--spacing-lg);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-warning-soft);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: var(--line-height-normal);
}

.drawer-note strong {
  color: #b45309;
}

.full-width {
  width: 100%;
}

.form-hint {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  line-height: var(--line-height-normal);
}

.drawer-summary {
  margin: var(--spacing-lg) 0 0;
  padding-top: var(--spacing-md);
  border-top: 1px dashed var(--color-border);
  font-size: var(--font-size-sm);
}

.drawer-summary > div {
  display: flex;
  gap: var(--spacing-md);
  padding: var(--spacing-xs) 0;
}

.drawer-summary dt {
  flex-shrink: 0;
  width: 72px;
  color: var(--color-text-secondary);
}

.drawer-summary dd {
  margin: 0;
  min-width: 0;
  color: var(--color-text-primary);
  word-break: break-all;
}
</style>
