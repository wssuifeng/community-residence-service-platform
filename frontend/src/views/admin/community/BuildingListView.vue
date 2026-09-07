<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createBuilding,
  deleteBuilding,
  getBuildingList,
  getCommunityList,
  updateBuilding
} from '@/api/community'
import type { IBuilding, IBuildingDTO, ICommunity } from '@/types/modules/community'
import { formatDateTime } from '@/utils/date'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 楼栋管理：社区下拉筛选 + 表格 CRUD（删除失败由后端引用保护报错提示） */
const route = useRoute()

const buildings = ref<IBuilding[]>([])
const communities = ref<ICommunity[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
/** 社区筛选（支持从社区详情跳转带入 query 预选） */
const communityFilter = ref<number | ''>(
  route.query.communityId ? Number(route.query.communityId) : ''
)

/** 社区下拉选项（管理端社区规模有限，单页拉取） */
async function loadCommunities(): Promise<void> {
  try {
    const result = await getCommunityList({ page: 1, size: 200 })
    communities.value = result.records
  } catch {
    communities.value = []
  }
}

async function load(): Promise<void> {
  if (communityFilter.value === '') {
    buildings.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await getBuildingList(communityFilter.value, {
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined
    })
    buildings.value = result.records
    total.value = result.total
  } catch {
    buildings.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function handleSearch(): void {
  page.value = 1
  load()
}

function handleCommunityChange(): void {
  page.value = 1
  load()
}

function handleReset(): void {
  keyword.value = ''
  communityFilter.value = ''
  page.value = 1
  load()
}

/* ---------------------------------- 新建/编辑 ---------------------------------- */

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()

/** 表单态 DTO：外键未选择时为 undefined（提交前经 rules 校验收敛） */
type BuildingForm = Omit<IBuildingDTO, 'communityId'> & { communityId?: number }

const form = reactive<BuildingForm>({
  communityId: undefined,
  name: '',
  totalFloors: 1,
  description: ''
})

const rules: FormRules = {
  communityId: [{ required: true, message: '请选择所属社区', trigger: 'change' }],
  name: [{ required: true, message: '请输入楼栋名称', trigger: 'blur' }],
  totalFloors: [{ required: true, message: '请输入总层数', trigger: 'blur' }]
}

function openCreate(): void {
  editingId.value = null
  form.communityId = communityFilter.value === '' ? undefined : communityFilter.value
  form.name = ''
  form.totalFloors = 1
  form.description = ''
  dialogVisible.value = true
}

function openEdit(row: IBuilding): void {
  editingId.value = row.id
  form.communityId = row.communityId
  form.name = row.name
  form.totalFloors = row.totalFloors
  form.description = row.description ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    if (editingId.value === null) {
      await createBuilding({ ...form, communityId: form.communityId as number })
      ElMessage.success('楼栋创建成功')
    } else {
      await updateBuilding(editingId.value, { ...form, communityId: form.communityId as number })
      ElMessage.success('楼栋已更新')
    }
    dialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
}

/* ---------------------------------- 删除（引用保护由后端报错） ---------------------------------- */

async function handleDelete(row: IBuilding): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除楼栋「${row.name}」？若该楼栋下存在单元/房屋，删除将被拒绝。`,
      '删除楼栋',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteBuilding(row.id)
    ElMessage.success('楼栋已删除')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(() => {
  loadCommunities()
  load()
})
</script>

<template>
  <section class="building-list">
    <div class="list-toolbar">
      <SearchBar v-model="keyword" placeholder="搜索楼栋名称" @search="handleSearch" />
      <el-button type="primary" @click="openCreate">新建楼栋</el-button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">所属社区</span>
      <el-select
        v-model="communityFilter"
        placeholder="请选择社区"
        style="width: 200px"
        @change="handleCommunityChange"
      >
        <el-option label="全部社区" value="" />
        <el-option
          v-for="item in communities"
          :key="item.id"
          :label="item.name"
          :value="item.id"
        />
      </el-select>
    </FilterPanel>

    <el-alert
      v-if="communityFilter === ''"
      title="请先选择社区查看楼栋"
      type="info"
      :closable="false"
      class="empty-hint"
    />
    <template v-else>
      <el-table v-loading="loading" :data="buildings" border>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="communityName" label="所属社区" min-width="140" show-overflow-tooltip />
        <el-table-column prop="name" label="楼栋名称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="totalFloors" label="总层数" width="90" />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(row)">删除</el-button>
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
    </template>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId === null ? '新建楼栋' : '编辑楼栋'"
      width="480px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="所属社区" prop="communityId">
          <el-select v-model="form.communityId" placeholder="请选择社区" style="width: 100%">
            <el-option
              v-for="item in communities"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="楼栋名称" prop="name">
          <el-input v-model="form.name" placeholder="如：1号楼" maxlength="30" />
        </el-form-item>
        <el-form-item label="总层数" prop="totalFloors">
          <el-input-number v-model="form.totalFloors" :min="1" :max="99" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入楼栋描述"
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

.empty-hint {
  margin-bottom: var(--spacing-md);
}
</style>
