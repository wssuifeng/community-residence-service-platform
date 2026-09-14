<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSysUser, getUserCommunities, bindCommunity, unbindCommunity } from '@/api/sysuser'
import { getCommunityList } from '@/api/community'
import type { ICommunity } from '@/types/modules/community'
import type { ISysUser, IUserCommunityBinding } from '@/types/modules/auth'
import { systemRoleLabels, userStatusLabels } from '@/types/modules/auth'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'

/**
 * 社区绑定抽屉（原 /admin/sys-users/:id/communities 独立页收编，对照 design-mockups/admin/11 任务 12）：
 * 用户信息摘要 + 已绑定社区列表（解绑）+ 绑定新社区（下拉选未绑定社区）。
 * 按传入用户打开，绑定/解绑成功后 emit('changed') 由列表刷新绑定社区列。
 * 后端契约：绑定仅对 ADMIN 角色生效（SysUserService 校验 BIND_ROLE_INVALID），非 ADMIN 行隐藏绑定面板。
 */
const props = defineProps<{
  modelValue: boolean
  /** 目标用户 ID；null 时抽屉不加载 */
  userId: number | null
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  /** 绑定/解绑成功，通知列表刷新绑定社区列 */
  changed: []
}>()

const visible = ref(props.modelValue)

watch(
  () => props.modelValue,
  (value) => {
    visible.value = value
    /* 每次打开都按当前目标用户拉取，避免读到上一行的缓存数据 */
    if (value && props.userId) {
      loadUser()
      loadBindings()
      loadCommunities()
    }
  }
)

watch(visible, (value) => {
  if (value !== props.modelValue) emit('update:modelValue', value)
})

const user = ref<ISysUser | null>(null)
const bindings = ref<IUserCommunityBinding[]>([])
const loading = ref(false)

/** 可选社区（分页递归拉全量启用社区，过滤已绑定） */
const allCommunities = ref<ICommunity[]>([])
const communityLoading = ref(false)
const selectedCommunityId = ref<number | ''>('')
const bindLoading = ref(false)

const COMMUNITY_PAGE_SIZE = 50

/** 仅 ADMIN 角色可绑定社区（后端 SysUserService 校验，其余角色绑定必失败） */
const canBind = computed(() => user.value?.role === 'ADMIN')

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
  if (!props.userId) return
  try {
    user.value = await getSysUser(props.userId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '用户信息加载失败')
  }
}

async function loadBindings(): Promise<void> {
  if (!props.userId) return
  loading.value = true
  try {
    bindings.value = await getUserCommunities(props.userId)
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
  if (!props.userId) return
  if (selectedCommunityId.value === '') {
    ElMessage.warning('请选择要绑定的社区')
    return
  }
  const communityId = selectedCommunityId.value
  const community = allCommunities.value.find((item) => item.id === communityId)
  try {
    await ElMessageBox.confirm(
      `确定将用户「${user.value?.realName ?? user.value?.username ?? props.userId}」绑定到社区「${community?.name ?? communityId}」？`,
      '绑定社区',
      { type: 'info', confirmButtonText: '绑定', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  bindLoading.value = true
  try {
    await bindCommunity(props.userId, communityId)
    ElMessage.success('社区绑定成功')
    selectedCommunityId.value = ''
    loadBindings()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '绑定失败')
  } finally {
    bindLoading.value = false
  }
}

async function handleUnbind(row: IUserCommunityBinding): Promise<void> {
  if (!props.userId) return
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
    await unbindCommunity(props.userId, row.communityId)
    ElMessage.success('社区已解绑')
    loadBindings()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解绑失败')
  }
}
</script>

<template>
  <el-drawer v-model="visible" title="社区绑定管理" size="640px" class="community-drawer">
    <div class="drawer-body">
      <!-- 用户信息摘要 -->
      <el-descriptions v-if="user" :column="2" border class="user-summary">
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

      <!-- 绑定新社区（仅 ADMIN 角色可绑定，后端对其他角色拒绝） -->
      <el-alert
        v-if="user && !canBind"
        type="info"
        :closable="false"
        show-icon
        title="仅社区管理员角色支持绑定社区"
      />
      <template v-else>
        <div class="bind-panel">
          <span class="bind-label">绑定新社区</span>
          <el-select
            v-model="selectedCommunityId"
            :loading="communityLoading"
            placeholder="选择未绑定的社区"
            class="bind-select"
            clearable
          >
            <el-option
              v-for="item in unboundCommunities"
              :key="item.id"
              :label="item.name"
              :value="item.id"
            />
          </el-select>
          <el-button v-permission="['SUPER_ADMIN']" type="primary" :loading="bindLoading" @click="handleBind">绑定</el-button>
          <span v-if="!communityLoading && unboundCommunities.length === 0" class="bind-empty">
            暂无可绑定的社区
          </span>
        </div>

        <!-- 已绑定社区列表 -->
        <el-table v-loading="loading" :data="bindings">
          <el-table-column prop="communityName" label="社区名称" min-width="140" show-overflow-tooltip />
          <el-table-column label="社区地址" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.communityAddress || '-' }}</template>
          </el-table-column>
          <el-table-column label="绑定时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.boundAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button v-permission="['SUPER_ADMIN']" link type="danger" size="small" @click="handleUnbind(row)">解绑</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂未绑定社区" :image-size="70" />
          </template>
        </el-table>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.bind-panel {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
}

.bind-label {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.bind-select {
  width: 240px;
}

.bind-empty {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}
</style>
