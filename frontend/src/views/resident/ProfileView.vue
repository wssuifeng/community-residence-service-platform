<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { getMyProfile, updateMyProfile, changeMyPassword, getResidentResidenceList } from '@/api/resident'
import { getLeaseList } from '@/api/lease'
import type { IResident, IResidenceRelation, ResidenceRelationStatus } from '@/types/modules/resident'
import type { ILeaseRecord, LeaseStatus } from '@/types/modules/lease'
import { residenceRelationStatusLabels } from '@/types/modules/resident'
import { leaseStatusLabels } from '@/types/modules/lease'
import { formatDate } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'

/** 居民个人中心：资料卡 + 居住关系 + 租住记录（卡片式，非密集表格） */
const profile = ref<IResident | null>(null)
const relations = ref<IResidenceRelation[]>([])
const leases = ref<ILeaseRecord[]>([])
const loading = ref(false)

/** 居住关系状态 → StatusTag 语义色（在住 completed / 已搬出 canceled） */
function relationTagType(status: ResidenceRelationStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

/** 租住状态 → StatusTag 语义色（生效 completed / 到期、终止 canceled） */
function leaseTagType(status: LeaseStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    profile.value = await getMyProfile()
    /* 居住关系与租住记录仅展示近期记录，不在此页做完整分页管理 */
    const [relationResult, leaseResult] = await Promise.all([
      getResidentResidenceList(profile.value.id, { page: 1, size: 5 }),
      getLeaseList({ page: 1, size: 5, residentId: profile.value.id })
    ])
    relations.value = relationResult.records
    leases.value = leaseResult.records
  } catch {
    profile.value = null
    relations.value = []
    leases.value = []
  } finally {
    loading.value = false
  }
}

/* ---------------- 编辑资料 ---------------- */

const profileDialogVisible = ref(false)
const profileFormRef = ref<FormInstance>()
const profileForm = reactive({ realName: '', phone: '', email: '' })

const profileRules: FormRules = {
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1\d{10}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

function openProfileDialog(): void {
  if (!profile.value) return
  profileForm.realName = profile.value.realName
  profileForm.phone = profile.value.phone
  profileForm.email = profile.value.email ?? ''
  profileDialogVisible.value = true
}

async function handleProfileSubmit(): Promise<void> {
  const valid = await profileFormRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    await updateMyProfile({
      realName: profileForm.realName.trim(),
      phone: profileForm.phone.trim(),
      email: profileForm.email.trim() || undefined
    })
    ElMessage.success('资料已更新')
    profileDialogVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '更新失败')
  }
}

/* ---------------- 修改密码 ---------------- */

const passwordDialogVisible = ref(false)
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '新密码长度 6~32 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        if (value !== passwordForm.newPassword) callback(new Error('两次输入的新密码不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

function openPasswordDialog(): void {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordDialogVisible.value = true
}

async function handlePasswordSubmit(): Promise<void> {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    await changeMyPassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    ElMessage.success('密码已修改')
    passwordDialogVisible.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '修改失败')
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="profile-page">
    <!-- 个人信息卡 -->
    <div class="profile-card">
      <div class="avatar">{{ profile?.realName?.charAt(0) ?? '?' }}</div>
      <div class="profile-info">
        <div class="profile-header">
          <h2 class="profile-name">{{ profile?.realName ?? '-' }}</h2>
          <div class="profile-actions">
            <el-button @click="openProfileDialog">编辑资料</el-button>
            <el-button @click="openPasswordDialog">修改密码</el-button>
          </div>
        </div>
        <el-descriptions :column="2" border class="profile-descriptions">
          <el-descriptions-item label="用户名">{{ profile?.username ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ profile?.phone ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ profile?.email ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="注册时间">{{ formatDate(profile?.createdAt) }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <!-- 我的居住关系 -->
    <div class="section-card">
      <h3 class="section-title">我的居住关系</h3>
      <div v-if="relations.length === 0" class="empty-tip">暂无居住关系记录</div>
      <div v-else class="relation-list">
        <div v-for="relation in relations" :key="relation.id" class="relation-item">
          <div class="relation-main">
            <span class="relation-house">{{ relation.houseAddress }}</span>
            <StatusTag
              :label="residenceRelationStatusLabels[relation.status as ResidenceRelationStatus]"
              :type="relationTagType(relation.status)"
            />
          </div>
          <div class="relation-dates">
            入住 {{ formatDate(relation.moveInDate) }}
            <template v-if="relation.moveOutDate"> ~ 迁出 {{ formatDate(relation.moveOutDate) }}</template>
          </div>
        </div>
      </div>
    </div>

    <!-- 我的租住记录 -->
    <div class="section-card">
      <h3 class="section-title">我的租住记录</h3>
      <div v-if="leases.length === 0" class="empty-tip">暂无租住记录</div>
      <el-table v-else :data="leases" stripe class="lease-table">
        <el-table-column label="房屋" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.houseAddress }}</template>
        </el-table-column>
        <el-table-column label="租期" min-width="200">
          <template #default="{ row }">
            {{ formatDate(row.leaseStartDate) }} ~ {{ formatDate(row.leaseEndDate) }}
          </template>
        </el-table-column>
        <el-table-column label="月租金（元）" width="110" align="right">
          <template #default="{ row }">{{ row.monthlyRent }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <StatusTag
              :label="leaseStatusLabels[row.status as LeaseStatus]"
              :type="leaseTagType(row.status)"
            />
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 编辑资料对话框 -->
    <el-dialog v-model="profileDialogVisible" title="编辑资料" width="440px">
      <el-form ref="profileFormRef" :model="profileForm" :rules="profileRules" label-width="80px">
        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="profileForm.realName" maxlength="32" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="profileForm.phone" maxlength="11" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="profileForm.email" placeholder="选填" maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="profileDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleProfileSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码对话框 -->
    <el-dialog v-model="passwordDialogVisible" title="修改密码" width="440px">
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="100px">
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handlePasswordSubmit">修改</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.profile-page {
  max-width: 860px;
  margin: 0 auto;
  padding: var(--spacing-lg) var(--spacing-md);
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.profile-card {
  display: flex;
  gap: var(--spacing-lg);
  padding: var(--spacing-xl);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  flex-wrap: wrap;
}

.avatar {
  width: 72px;
  height: 72px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-circle);
  background-color: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
}

.profile-info {
  flex: 1;
  min-width: 260px;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.profile-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.profile-name {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.profile-actions {
  display: flex;
  gap: var(--spacing-sm);
}

.section-card {
  padding: var(--spacing-lg);
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
}

.section-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  margin-bottom: var(--spacing-md);
}

.empty-tip {
  padding: var(--spacing-lg) 0;
  text-align: center;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.relation-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.relation-item {
  padding: var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background-color: var(--color-bg);
}

.relation-main {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--spacing-md);
  margin-bottom: var(--spacing-xs);
}

.relation-house {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.relation-dates {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.lease-table {
  width: 100%;
}
</style>
