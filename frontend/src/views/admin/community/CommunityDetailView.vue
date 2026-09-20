<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getBuildingList, getCommunity } from '@/api/community'
import type { IBuilding, ICommunity } from '@/types/modules/community'
import { communityStatusLabels } from '@/types/modules/community'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'
import AdminPageHeader from '@/views/admin/AdminPageHeader.vue'
import CommunityEditDialog from '@/views/admin/community/CommunityEditDialog.vue'

/**
 * 社区详情（隐藏下钻）：基本信息 + 该社区楼栋列表（任务 3 换壳美化：
 * AdminPageHeader + 白卡 token，数据绑定零删减；编辑对话框收编为共用
 * CommunityEditDialog，返回目标为社区管理页）。
 */
const route = useRoute()
const router = useRouter()
const communityId = Number(route.params.id)

const community = ref<ICommunity | null>(null)
const loading = ref(false)

async function loadCommunity(): Promise<void> {
  loading.value = true
  try {
    community.value = await getCommunity(communityId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载社区详情失败')
  } finally {
    loading.value = false
  }
}

/* 面包屑返回：社区管理页（本页的上级页面） */
function goBack(): void {
  router.push({ name: 'AdminCommunity' })
}

/* 楼栋入口：直达社区管理页并选中本社区（?communityId= 由社区列表消费） */
function goBuildings(): void {
  router.push({
    name: 'AdminCommunity',
    query: { communityId: String(communityId), tab: 'tree' }
  })
}

/* ---------------------------------- 编辑对话框（共用组件） ---------------------------------- */

const dialogVisible = ref(false)

function openEdit(): void {
  if (!community.value) return
  dialogVisible.value = true
}

/* ---------------------------------- 楼栋列表 ---------------------------------- */

const buildings = ref<IBuilding[]>([])
const buildingsLoading = ref(false)
const buildingPage = ref(1)
const buildingSize = ref(10)
const buildingTotal = ref(0)

async function loadBuildings(): Promise<void> {
  buildingsLoading.value = true
  try {
    const result = await getBuildingList(communityId, {
      page: buildingPage.value,
      size: buildingSize.value
    })
    buildings.value = result.records
    buildingTotal.value = result.total
  } catch {
    buildings.value = []
    buildingTotal.value = 0
  } finally {
    buildingsLoading.value = false
  }
}

onMounted(() => {
  loadCommunity()
  loadBuildings()
})
</script>

<template>
  <div v-loading="loading" class="admin-page community-detail">
    <!-- 面包屑返回 -->
    <button type="button" class="back-link" @click="goBack">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M19 12H5" />
        <path d="M12 19l-7-7 7-7" />
      </svg>
      返回社区管理
    </button>

    <AdminPageHeader :title="community?.name ?? '社区详情'" subtitle="社区基础信息与楼栋结构">
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openEdit">编辑社区</el-button>
    </AdminPageHeader>

    <article class="panel">
      <header class="panel-header">
        <h3 class="panel-title">基本信息</h3>
      </header>
      <el-descriptions v-if="community" :column="2" border>
        <el-descriptions-item label="社区名称">{{ community.name }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag
            :label="communityStatusLabels[community.status]"
            :type="community.status === 'ACTIVE' ? 'completed' : 'canceled'"
          />
        </el-descriptions-item>
        <el-descriptions-item label="社区地址" :span="2">{{ community.address }}</el-descriptions-item>
        <el-descriptions-item label="联系人">{{ community.contactPerson || '-' }}</el-descriptions-item>
        <el-descriptions-item label="联系电话">{{ community.contactPhone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="入住申请" :span="2">
          {{
            community.autoApproveResidence
              ? `提交即通过（默认租期 ${community.defaultLeaseMonths ?? 12} 个月）`
              : '人工审核'
          }}
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(community.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ formatDateTime(community.updatedAt) }}</el-descriptions-item>
        <el-descriptions-item label="社区简介" :span="2">{{ community.description || '-' }}</el-descriptions-item>
      </el-descriptions>
    </article>

    <article class="panel">
      <header class="panel-header">
        <h3 class="panel-title">楼栋列表</h3>
        <el-button type="primary" link @click="goBuildings">前往社区结构 →</el-button>
      </header>
      <el-table v-loading="buildingsLoading" :data="buildings" border>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="name" label="楼栋名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="floors" label="总层数" width="90" />
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <Pagination
        v-model:page="buildingPage"
        v-model:size="buildingSize"
        :total="buildingTotal"
        @update:page="loadBuildings"
        @update:size="loadBuildings"
      />
    </article>

    <CommunityEditDialog v-model="dialogVisible" :community="community" @saved="loadCommunity" />
  </div>
</template>

<style scoped>
.community-detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

/* 面包屑返回链接（token 着色，悬停品牌蓝） */
.back-link {
  display: inline-flex;
  align-items: center;
  align-self: flex-start;
  gap: var(--spacing-xs);
  padding: 0;
  border: none;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: color 0.2s ease;
}

.back-link:hover {
  color: var(--color-primary);
}

.back-link svg {
  width: 14px;
  height: 14px;
}

/* 白卡容器（管理端统一 token，替代原 #fff + 硬边框） */
.panel {
  min-width: 0;
  padding: var(--spacing-lg);
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-md);
}

.panel-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}
</style>
