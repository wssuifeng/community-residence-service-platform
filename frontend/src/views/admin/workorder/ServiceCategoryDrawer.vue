<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
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

/**
 * 服务类别抽屉（原 /admin/service-categories 独立页收编，对照 design-mockups/admin/03 任务 4）：
 * 社区选择 + 两级类别树表格 + 新增/编辑/删除全套，挂在工作列表页页头入口。
 * CRUD 完成后 emit('changed')（当前列表数据与类别无联动、不强制刷新，留作扩展点）。
 */
const props = defineProps<{ modelValue: boolean }>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  changed: []
}>()

const userStore = useUserStore()

const visible = ref(props.modelValue)

watch(
  () => props.modelValue,
  (value) => {
    visible.value = value
    /* 首次打开再初始化社区与类别树，避免页面挂载即发请求 */
    if (value && !initialized) {
      initialized = true
      loadCommunities().then(() => {
        if (communityId.value) loadTree()
      })
    }
  }
)

watch(visible, (value) => {
  if (value !== props.modelValue) emit('update:modelValue', value)
})

let initialized = false

const communities = ref<ICommunity[]>([])
const communityId = ref<number | null>(null)
const tree = ref<IServiceCategoryTreeNode[]>([])
const loading = ref(false)

/* 树展开受控：所有含子类别的节点恒展开。
   不用 default-expand-all——el-table 树模式在数据重载后保留旧展开态，
   新增子类别的行不会渲染（受控 expand-row-keys 每次重载全量展开）。 */
const expandedKeys = computed(() => {
  const keys: string[] = []
  const walk = (nodes: IServiceCategoryTreeNode[]): void => {
    for (const node of nodes) {
      if (node.children.length > 0) {
        keys.push(String(node.id))
        walk(node.children)
      }
    }
  }
  walk(tree.value)
  return keys
})

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
    /* 管理员只管绑定社区：单绑定自动锁定，多绑定取第一个（可切换）；登录响应为 ID 数组 */
    if (bound.length > 0) {
      communityId.value = bound[0]
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
    /* 后端对二级类别返回 children:null，统一归一为数组，避免树表渲染与子类别计数空指针 */
    const withChildren = (nodes: IServiceCategoryTreeNode[]): IServiceCategoryTreeNode[] =>
      nodes.map((node) => ({ ...node, children: withChildren(node.children ?? []) }))
    tree.value = withChildren(await getServiceCategoryTree(communityId.value))
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
    emit('changed')
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
    emit('changed')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '删除失败')
  }
}
</script>

<template>
  <el-drawer v-model="visible" title="服务类别管理" size="520px" class="category-drawer">
    <div class="drawer-body">
      <div class="drawer-toolbar">
        <el-select
          v-model="communityId"
          placeholder="选择社区"
          class="community-select"
          :disabled="communities.length === 0"
          aria-label="社区选择"
        >
          <el-option v-for="item in communities" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openCreate(null)">新增顶级类别</el-button>
      </div>

      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="类别最多两级；有子类别或工单关联的类别不可删除"
        class="drawer-tip"
      />

      <el-table
        v-loading="loading"
        :data="tree"
        row-key="id"
        :expand-row-keys="expandedKeys"
        :tree-props="{ children: 'children' }"
        class="category-table"
      >
        <el-table-column prop="name" label="类别名称" min-width="150" />
        <el-table-column prop="description" label="描述" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="子类别" width="70" align="center">
          <template #default="{ row }">{{ row.children.length }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" text type="primary" size="small" @click="openCreate(row.id)">新增子类别</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" text type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" text type="danger" size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无服务类别，点击「新增顶级类别」创建" :image-size="70" />
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="440px" append-to-body>
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
  </el-drawer>
</template>

<style scoped>
.drawer-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.drawer-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.community-select {
  flex: 1;
  min-width: 0;
}

.drawer-tip {
  --el-alert-padding: 8px 12px;
}

.category-table {
  width: 100%;
}

.parent-select {
  width: 100%;
}
</style>
