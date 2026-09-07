<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCommunityList } from '@/api/community'
import {
  createServiceCategory,
  deleteServiceCategory,
  getServiceCategory,
  getServiceCategoryTree,
  updateServiceCategory
} from '@/api/workorder'
import type { ICommunity } from '@/types/modules/community'
import type { IServiceCategory, IServiceCategoryTreeNode } from '@/types/modules/workorder'
import { useUserStore } from '@/store/user'

/** 服务类别管理（UI设计.md §3.4.4）：社区选择 + 两级类别树表格 + CRUD 对话框 */

const userStore = useUserStore()

const communities = ref<ICommunity[]>([])
const communityId = ref<number | null>(null)
const tree = ref<IServiceCategoryTreeNode[]>([])
const loading = ref(false)

/* 新增/编辑对话框 */
const dialogVisible = ref(false)
const dialogTitle = ref('新增类别')
const editingId = ref<number | null>(null)
const saving = ref(false)

const form = reactive({
  name: '',
  parentId: null as number | null,
  description: '',
  sortOrder: 0
})

/** 顶级类别选项（仅两级：顶级为 null，二级挂顶级下） */
const topCategories = ref<{ id: number; name: string }[]>([])

async function loadCommunities(): Promise<void> {
  try {
    const page = await getCommunityList({ page: 1, size: 100 })
    communities.value = page.records
    const bound = userStore.user?.boundCommunities ?? []
    /* 管理员只管绑定社区：单绑定自动锁定，多绑定取第一个（可切换） */
    if (bound.length > 0) {
      communityId.value = bound[0].communityId
    } else if (page.records.length > 0) {
      communityId.value = page.records[0].id
    }
    if (communityId.value === null) {
      ElMessage.warning('暂无可管理的社区，请先创建社区或绑定社区')
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '社区列表加载失败')
  }
}

async function loadTree(): Promise<void> {
  if (!communityId.value) return
  loading.value = true
  try {
    tree.value = await getServiceCategoryTree(communityId.value)
    topCategories.value = tree.value.map((node) => ({ id: node.id, name: node.name }))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '服务类别加载失败')
  } finally {
    loading.value = false
  }
}

watch(communityId, () => {
  loadTree()
})

function openCreate(parentId: number | null): void {
  editingId.value = null
  dialogTitle.value = parentId ? '新增子类别' : '新增顶级类别'
  form.name = ''
  form.parentId = parentId
  form.description = ''
  form.sortOrder = 0
  dialogVisible.value = true
}

async function openEdit(row: IServiceCategoryTreeNode): Promise<void> {
  try {
    /* 树节点不含 parentId/communityId，编辑前拉取详情 */
    const detail: IServiceCategory = await getServiceCategory(row.id)
    editingId.value = detail.id
    dialogTitle.value = '编辑类别'
    form.name = detail.name
    form.parentId = detail.parentId
    form.description = detail.description ?? ''
    form.sortOrder = detail.sortOrder
    dialogVisible.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '类别详情加载失败')
  }
}

async function handleSave(): Promise<void> {
  if (!communityId.value) {
    ElMessage.warning('请先选择社区')
    return
  }
  if (!form.name.trim()) {
    ElMessage.warning('请填写类别名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      communityId: communityId.value,
      parentId: form.parentId,
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      sortOrder: form.sortOrder
    }
    if (editingId.value === null) {
      await createServiceCategory(payload)
      ElMessage.success('类别创建成功')
    } else {
      await updateServiceCategory(editingId.value, payload)
      ElMessage.success('类别更新成功')
    }
    dialogVisible.value = false
    loadTree()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

/* 有子类别或关联工单时后端会拒绝删除（接口设计.md 9.4.1.3） */
async function handleDelete(row: IServiceCategoryTreeNode): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除类别「${row.name}」吗？`, '删除类别', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteServiceCategory(row.id)
    ElMessage.success('类别已删除')
    loadTree()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(() => {
  loadCommunities().then(() => {
    if (communityId.value) loadTree()
  })
})
</script>

<template>
  <section class="category-page">
    <header class="page-header">
      <h1 class="page-title">服务类别</h1>
      <div class="header-actions">
        <el-select
          v-model="communityId"
          placeholder="选择社区"
          class="community-select"
          :disabled="communities.length === 0"
        >
          <el-option v-for="item in communities" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openCreate(null)">新增顶级类别</el-button>
      </div>
    </header>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="类别最多两级：顶级为大类（如维修/保洁），二级为具体事项（如水管维修）；有子类别或工单关联的类别不可删除"
      class="page-tip"
    />

    <el-table
      v-loading="loading"
      :data="tree"
      row-key="id"
      default-expand-all
      :tree-props="{ children: 'children' }"
      class="category-table"
    >
      <el-table-column prop="name" label="类别名称" min-width="220" />
      <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.description ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="80" />
      <el-table-column label="子类别数" width="100">
        <template #default="{ row }">{{ row.children.length }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" text type="primary" size="small" @click="openCreate(row.id)">新增子类别</el-button>
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" text type="danger" size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无服务类别，点击右上角「新增顶级类别」创建" :image-size="80" />
      </template>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="上级类别">
          <el-select v-model="form.parentId" placeholder="无（作为顶级类别）" clearable class="parent-select">
            <el-option v-for="item in topCategories" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类别名称" required>
          <el-input v-model="form.name" maxlength="50" show-word-limit placeholder="如：维修 / 水管维修" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="类别用途说明（可选）" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.page-title {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
}

.community-select {
  width: 200px;
}

.page-tip {
  margin-bottom: var(--spacing-md);
}

.category-table {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
}

.parent-select {
  width: 100%;
}
</style>
