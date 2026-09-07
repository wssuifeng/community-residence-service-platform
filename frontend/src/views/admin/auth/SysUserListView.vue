<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  createSysUser,
  getSysUserList,
  updateSysUser,
  updateSysUserStatus,
  changePassword
} from '@/api/sysuser'
import type {
  ISysUser,
  SystemRole,
  UserStatus,
  ICreateSysUserDTO,
  IUpdateSysUserDTO
} from '@/types/modules/auth'
import { systemRoleLabels, userStatusLabels } from '@/types/modules/auth'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import SearchBar from '@/components/common/SearchBar.vue'
import FilterPanel from '@/components/common/FilterPanel.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 系统用户列表：角色/状态筛选 + 关键词搜索 + 创建/编辑/冻结解冻/改密 */
const router = useRouter()

const users = ref<ISysUser[]>([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
const roleFilter = ref<SystemRole | ''>('')
const statusFilter = ref<UserStatus | ''>('')

/** 创建对话框 */
const createVisible = ref(false)
const createLoading = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive<ICreateSysUserDTO>({
  username: '',
  password: '',
  realName: '',
  phone: '',
  email: '',
  role: 'STAFF'
})

/** 编辑对话框（不含用户名/密码） */
const editVisible = ref(false)
const editLoading = ref(false)
const editFormRef = ref<FormInstance>()
const editUserId = ref(0)
const editForm = reactive<IUpdateSysUserDTO>({
  realName: '',
  phone: '',
  email: '',
  role: 'STAFF'
})

/** 修改密码对话框 */
const passwordVisible = ref(false)
const passwordLoading = ref(false)
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const createRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 30, message: '用户名长度 3-30 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度 8-64 个字符', trigger: 'blur' }
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [
    {
      pattern: /^1\d{10}$/,
      message: '手机号格式不正确',
      trigger: 'blur'
    }
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

const editRules: FormRules = {
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [
    {
      pattern: /^1\d{10}$/,
      message: '手机号格式不正确',
      trigger: 'blur'
    }
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

const passwordRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度 8-64 个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value !== passwordForm.newPassword) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

/** 状态 → StatusTag 语义色 */
function statusTagType(status: UserStatus): 'completed' | 'rejected' {
  return status === 'ACTIVE' ? 'completed' : 'rejected'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await getSysUserList({
      page: page.value,
      size: size.value,
      keyword: keyword.value || undefined,
      role: roleFilter.value || undefined,
      status: statusFilter.value || undefined
    })
    users.value = result.records
    total.value = result.total
  } catch {
    users.value = []
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
  roleFilter.value = ''
  statusFilter.value = ''
  page.value = 1
  load()
}

function openCreate(): void {
  createFormRef.value?.resetFields()
  Object.assign(createForm, {
    username: '',
    password: '',
    realName: '',
    phone: '',
    email: '',
    role: 'STAFF'
  })
  createVisible.value = true
}

async function handleCreate(): Promise<void> {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return
  createLoading.value = true
  try {
    await createSysUser({ ...createForm })
    ElMessage.success('用户创建成功')
    createVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建失败')
  } finally {
    createLoading.value = false
  }
}

function openEdit(row: ISysUser): void {
  editFormRef.value?.resetFields()
  editUserId.value = row.id
  Object.assign(editForm, {
    realName: row.realName,
    phone: row.phone ?? '',
    email: row.email ?? '',
    role: row.role
  })
  editVisible.value = true
}

async function handleEdit(): Promise<void> {
  const valid = await editFormRef.value?.validate().catch(() => false)
  if (!valid) return
  editLoading.value = true
  try {
    await updateSysUser(editUserId.value, { ...editForm })
    ElMessage.success('用户信息已更新')
    editVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '更新失败')
  } finally {
    editLoading.value = false
  }
}

/** 冻结：需填写原因；解冻：直接确认 */
async function handleToggleStatus(row: ISysUser): Promise<void> {
  if (row.status === 'ACTIVE') {
    let reason: string
    try {
      const result = await ElMessageBox.prompt(
        '请输入冻结原因，将记录至操作日志',
        `冻结账号「${row.username}」`,
        {
          type: 'warning',
          confirmButtonText: '冻结',
          cancelButtonText: '取消',
          inputValidator: (value: string) =>
            value.trim().length > 0 ? true : '冻结原因不能为空'
        }
      )
      reason = result.value.trim()
    } catch {
      return
    }
    try {
      await updateSysUserStatus(row.id, { status: 'FROZEN', reason })
      ElMessage.success('账号已冻结')
      load()
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '冻结失败')
    }
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定解冻账号「${row.username}」？解冻后该用户可正常登录。`,
      '解冻账号',
      { type: 'info', confirmButtonText: '解冻', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await updateSysUserStatus(row.id, { status: 'ACTIVE' })
    ElMessage.success('账号已解冻')
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '解冻失败')
  }
}

function openChangePassword(row: ISysUser): void {
  passwordFormRef.value?.resetFields()
  Object.assign(passwordForm, {
    oldPassword: '',
    newPassword: '',
    confirmPassword: ''
  })
  editUserId.value = row.id
  passwordVisible.value = true
}

async function handleChangePassword(): Promise<void> {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) return
  passwordLoading.value = true
  try {
    await changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    ElMessage.success('密码修改成功')
    passwordVisible.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '密码修改失败')
  } finally {
    passwordLoading.value = false
  }
}

function goCommunities(row: ISysUser): void {
  router.push(`/admin/sys-users/${row.id}/communities`)
}

onMounted(load)
</script>

<template>
  <section class="sys-user-list">
    <div class="list-toolbar">
      <SearchBar v-model="keyword" placeholder="搜索用户名/姓名/手机号" @search="handleSearch" />
      <el-button type="primary" @click="openCreate">创建用户</el-button>
    </div>

    <FilterPanel resettable @reset="handleReset">
      <span class="filter-label">角色</span>
      <el-select v-model="roleFilter" style="width: 140px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in systemRoleLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
      <span class="filter-label">状态</span>
      <el-select v-model="statusFilter" style="width: 120px" @change="handleSearch">
        <el-option label="全部" value="" />
        <el-option
          v-for="(label, value) in userStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <el-table v-loading="loading" :data="users" border>
      <el-table-column prop="username" label="用户名" min-width="120" show-overflow-tooltip />
      <el-table-column prop="realName" label="姓名" min-width="100" show-overflow-tooltip />
      <el-table-column label="手机号" width="130">
        <template #default="{ row }">{{ row.phone || '-' }}</template>
      </el-table-column>
      <el-table-column label="角色" width="110">
        <template #default="{ row }">
          <StatusTag :label="systemRoleLabels[row.role as keyof typeof systemRoleLabels]" type="info" />
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <StatusTag :label="userStatusLabels[row.status as keyof typeof userStatusLabels]" :type="statusTagType(row.status)" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="280" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" @click="goCommunities(row)">社区绑定</el-button>
          <el-button
            link
            :type="row.status === 'ACTIVE' ? 'danger' : 'success'"
            size="small"
            @click="handleToggleStatus(row)"
          >
            {{ row.status === 'ACTIVE' ? '冻结' : '解冻' }}
          </el-button>
          <el-button link type="warning" size="small" @click="openChangePassword(row)">修改密码</el-button>
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

    <!-- 创建用户 -->
    <el-dialog v-model="createVisible" title="创建用户" width="480px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="登录用户名" maxlength="30" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input
            v-model="createForm.password"
            type="password"
            show-password
            placeholder="8-64 位"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="createForm.realName" placeholder="真实姓名" maxlength="30" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="createForm.phone" placeholder="选填" maxlength="11" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="createForm.email" placeholder="选填" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="createForm.role" style="width: 100%">
            <el-option
              v-for="(label, value) in systemRoleLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="handleCreate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 编辑用户 -->
    <el-dialog v-model="editVisible" title="编辑用户" width="480px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px">
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="editForm.realName" placeholder="真实姓名" maxlength="30" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="editForm.phone" placeholder="选填" maxlength="11" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="editForm.email" placeholder="选填" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="editForm.role" style="width: 100%">
            <el-option
              v-for="(label, value) in systemRoleLabels"
              :key="value"
              :label="label"
              :value="value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editLoading" @click="handleEdit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 修改密码 -->
    <el-dialog v-model="passwordVisible" title="修改密码" width="440px">
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-width="100px">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="passwordForm.oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="passwordForm.newPassword" type="password" show-password placeholder="8-64 位" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="passwordForm.confirmPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordLoading" @click="handleChangePassword">确定</el-button>
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
