<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSysUser, getUserCommunities, bindCommunity, unbindCommunity } from '@/api/sysuser'
import { getCommunityList } from '@/api/community'
import type { ICommunity } from '@/types/modules/community'
import type { ISysUser, IUserCommunityBinding } from '@/types/modules/auth'
import { systemRoleLabels, userStatusLabels } from '@/types/modules/auth'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'

/** 社区绑定：用户信息摘要 + 已绑定社区列表（解绑）+ 绑定新社区（下拉选未绑定社区） */
const route = useRoute()
const router = useRouter()

const userId = Number(route.params.id)

const user = ref<ISysUser | null>(null)
const bindings = ref<IUserCommunityBinding[]>([])
const loading = ref(false)

/** 可选社区（分页递归拉全量启用社区，过滤已绑定） */
const allCommunities = ref<ICommunity[]>([])
const communityLoading = ref(false)
const selectedCommunityId = ref<number | ''>('')
const bindLoading = ref(false)

const COMMUNITY_PAGE_SIZE = 50

const boundIds = computed(() => new Set(bindings.value.map((item) => item.communityId)))

/** 未绑定的启用社区 */
const unboundCommunities = computed(() =>
  allCommunities.value.filter(
    (item) => item.status === 'ACTIVE' && !boundIds.value.has(item.id)
  )
)

/** 状态 → StatusTag 语义色 */
function statusTagType(status: string): 'completed' | 'rejected' {
  return status === 'ACTIVE' ? 'completed' : 'rejected'
}

async function loadUser(): Promise<void> {
  try {
    user.value = await getSysUser(userId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '用户信息加载失败')
  }
}

async function loadBindings(): Promise<void> {
  loading.value = true
  try {
    bindings.value = await getUserCommunities(userId)
  } catch (error) {
    bindings.value = []
    ElMessage.error(error instanceof Error ? error.message : '绑定列表加载失败')
  } finally {
    loading.value = false
  }
}

/** 递归翻页拉取社区全量（数量有限，一次会话内拉全以便过滤已绑定项） */
async function loadCommunities(page = 1): Promise<void> {
  communityLoading.value = true
  try {
    const result = await getCommunityList({ page, size: COMMUNITY_PAGE_SIZE })
    if (page === 1) {
      allCommunities.value = result.records
    } else {
      allCommunities.value.push(...result.records)
    }
    if (allCommunities.value.length < result.total) {
      await loadCommunities(page + 1)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '社区列表加载失败')
  } finally {
    communityLoading.value = false
  }
}

async function handleBind(): Promise<void> {
  if (selectedCommunityId.value === '') {
    ElMessage.warning('请选择要绑定的社区')
    return
  }
  const communityId = selectedCommunityId.value
  const community = allCommunities.value.find((item) => item.id === communityId)
  try {
    await ElMessageBox.confirm(
      `确定将用户「${user.value?.realName ?? user.value?.username ?? userId}」绑定到社区「${community?.name ?? communityId}」？`,
      '绑定社区',
      { type: 'info', confirmButtonText: '绑定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  bindLoading.value = true
  try {
    await bindCommunity(userId, communityId)
    ElMessage.success('社区绑定成功')
    selectedCommunityId.value = ''
    loadBindings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '绑定失败')
  } finally {
    bindLoading.value = false
  }
}

async function handleUnbind(row: IUserCommunityBinding): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定解绑社区「${row.communityName}」？解绑后该用户不再管理此社区。`,
      '解绑社区',
      { type: 'warning', confirmButtonText: '解绑', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await unbindCommunity(userId, row.communityId)
    ElMessage.success('社区已解绑')
    loadBindings()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解绑失败')
  }
}

function goBack(): void {
  router.push('/admin/sys-users')
}

onMounted(() => {
  loadUser()
  loadBindings()
  loadCommunities()
})
</script>

<template>
  <section class="user-community">
    <!-- 用户信息摘要 -->
    <el-card class="user-summary" shadow="never">
      <template #header>
        <div class="summary-header">
          <span>用户信息</span>
          <el-button link type="primary" @click="goBack">返回用户列表</el-button>
        </div>
      </template>
      <el-descriptions v-if="user" :column="3" border>
        <el-descriptions-item label="用户名">{{ user.username }}</el-descriptions-item>
        <el-descriptions-item label="姓名">{{ user.realName }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ user.phone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="角色">
          <StatusTag :label="systemRoleLabels[user.role]" type="info" />
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag :label="userStatusLabels[user.status]" :type="statusTagType(user.status)" />
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDateTime(user.createdAt) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 绑定新社区 -->
    <el-card class="bind-panel" shadow="never">
      <template #header>绑定新社区</template>
      <div class="bind-form">
        <el-select
          v-model="selectedCommunityId"
          :loading="communityLoading"
          placeholder="选择未绑定的社区"
          style="width: 280px"
          clearable
        >
          <el-option
            v-for="item in unboundCommunities"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
        <el-button type="primary" :loading="bindLoading" @click="handleBind">绑定</el-button>
        <span v-if="!communityLoading && unboundCommunities.length === 0" class="bind-empty">
          暂无可绑定的社区
        </span>
      </div>
    </el-card>

    <!-- 已绑定社区列表 -->
    <el-table v-loading="loading" :data="bindings" border>
      <el-table-column prop="communityId" label="社区ID" width="80" />
      <el-table-column prop="communityName" label="社区名称" min-width="160" show-overflow-tooltip />
      <el-table-column label="社区地址" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.communityAddress || '-' }}</template>
      </el-table-column>
      <el-table-column label="绑定时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.boundAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="handleUnbind(row)">解绑</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.user-summary {
  margin-bottom: var(--spacing-md);
}

.summary-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.bind-panel {
  margin-bottom: var(--spacing-md);
}

.bind-form {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.bind-empty {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
