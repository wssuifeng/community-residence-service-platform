<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createCommunity,
  getCommunityList,
  updateCommunity,
  updateCommunityStatus
} from '@/api/community'
import type { ICommunity, ICommunityQuery, ICreateCommunityDTO, CommunityStatus } from '@/types/modules/community'
import { communityStatusLabels } from '@/types/modules/community'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 社区列表：搜索/状态筛选 + 新建/编辑 + 启用/停用（新建仅超管） */
const router = useRouter()

const communities = ref<ICommunity[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref<CommunityStatus | ''>('')

function statusTagType(status: CommunityStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: ICommunityQuery = {
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      status: statusFilter.value || undefined
    }
    const result = await getCommunityList(params)
    communities.value = result.records
    total.value = result.total
  } catch {
    communities.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  keyword.value = ''
  statusFilter.value = ''
  page.value = 1
  load()
}

function goDetail(id: number): void {
  router.push(`/admin/communities/${id}`)
}

/* ---------------------------------- 新建/编辑 ---------------------------------- */

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<ICreateCommunityDTO>({
  name: '',
  address: '',
  contactPhone: '',
  contactPerson: '',
  description: ''
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入社区名称', trigger: 'blur' }],
  address: [{ required: true, message: '请输入社区地址', trigger: 'blur' }]
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.address = ''
  form.contactPhone = ''
  form.contactPerson = ''
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row: ICommunity): void {
  editingId.value = row.id
  form.name = row.name
  form.address = row.address
  form.contactPhone = row.contactPhone ?? ''
  form.contactPerson = row.contactPerson ?? ''
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    if (editingId.value === null) {
      await createCommunity({ ...form })
      ElMessage.success('社区创建成功')
    } else {
      await updateCommunity(editingId.value, { ...form })
      ElMessage.success('社区已更新')
    }
    dialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

/* ---------------------------------- 启用/停用 ---------------------------------- */

async function toggleStatus(row: ICommunity): Promise<void> {
  const next: CommunityStatus = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  const action = next === 'ACTIVE' ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      `确定${action}社区「${row.name}」？${next === 'INACTIVE' ? '停用后该社区相关业务将不可用。' : ''}`,
      `${action}社区`,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await updateCommunityStatus(row.id, { status: next })
    ElMessage.success(`社区已${action}`)
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${action}失败`)
  }
}

onMounted(load)
</script>

<template>
  <section class="community-list">
    <div class="list-toolbar">
      <SearchBar v-model="keyword" placeholder="搜索社区名称/地址" @search="handleSearch" />
      <el-button v-permission="['SUPER_ADMIN']" type="primary" @click="openCreate">新建社区</el-button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">状态</span>
      <el-select v-model="statusFilter" style="width: 140px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in communityStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="communities" border>
      <el-table-column prop="id" label="ID" width="64" />
      <el-table-column label="社区名称" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" @click="goDetail(row.id)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column prop="address" label="地址" min-width="200" show-overflow-tooltip />
      <el-table-column prop="contactPerson" label="联系人" width="100" show-overflow-tooltip>
        <template #default="{ row }">{{ row.contactPerson || '-' }}</template>
      </el-table-column>
      <el-table-column prop="contactPhone" label="联系电话" width="130">
        <template #default="{ row }">{{ row.contactPhone || '-' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag
            :label="communityStatusLabels[row.status as CommunityStatus]"
            :type="statusTagType(row.status)"
          />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="150">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="goDetail(row.id)">详情</el-button>
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button
            link
            size="small"
            :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
            @click="toggleStatus(row)"
          >
            {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <Pagination
      v-model:page="page"
      v-model:size="size"
      :total="total"
      @update:page="load"
      @update:size="load"
    />

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建社区' : '编辑社区'"
      width="520px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="社区名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入社区名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="社区地址" prop="address">
          <el-input v-model="form.address" placeholder="请输入社区地址" maxlength="100" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactPerson">
          <el-input v-model="form.contactPerson" placeholder="请输入联系人" maxlength="20" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" placeholder="请输入联系电话" maxlength="20" />
        </el-form-item>
        <el-form-item label="社区简介" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入社区简介"
            maxlength="200"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.filter-label {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
