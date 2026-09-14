<script setup lang="ts">
/** 管理员代建居民对话框（DEF-038，R8 v1.2）：代建成功后展示初始密码供转交居民 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { adminCreateResident } from '@/api/resident'
import { getCommunityList } from '@/api/community'
import type { IAdminCreateResidentVO } from '@/types/modules/resident'
import type { ICommunity } from '@/types/modules/community'

/**
 * 两段式：表单段（提交）→ 结果段（username + initialPassword 展示）。
 * 初始密码明文仅本次返回（后端不落明文），结果段强调可复制并提示转交，
 * 关闭即丢弃——错过需走「忘记密码」类流程，故不提供二次查看。
 */

const visible = defineModel<boolean>({ required: true })
/** created 携带代建结果，父层刷新列表与统计卡 */
const emit = defineEmits<{ created: [result: IAdminCreateResidentVO] }>()

const communities = ref<ICommunity[]>([])
const formRef = ref<FormInstance>()
const submitting = ref(false)
/** 代建结果：非空时对话框切换为结果展示段 */
const result = ref<IAdminCreateResidentVO | null>(null)

const form = reactive({
  communityId: undefined as number | undefined,
  realName: '',
  phone: '',
  idCard: '',
  username: ''
})

/* 校验口径与后端 AdminCreateResidentDTO 注解逐条对齐 */
const rules: FormRules = {
  communityId: [{ required: true, message: '请选择所属社区', trigger: 'change' }],
  realName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { max: 50, message: '真实姓名最多 50 字符', trigger: 'blur' }
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  idCard: [
    { pattern: /^\d{17}[\dXx]$/, message: '身份证号格式不正确（18 位，尾号可为 X）', trigger: 'blur' }
  ],
  username: [
    { pattern: /^[a-zA-Z0-9_]+$/, message: '用户名仅支持字母、数字、下划线', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        if (value && (value.length < 4 || value.length > 20)) {
          callback(new Error('用户名长度 4~20 字符'))
          return
        }
        callback()
      },
      trigger: 'blur'
    }
  ]
}

/* 社区下拉：ADMIN 数据级权限由后端限定（仅返回绑定社区），前端全量渲染 */
async function loadCommunities(): Promise<void> {
  try {
    const page = await getCommunityList({ page: 1, size: 200 })
    communities.value = page.records
  } catch {
    communities.value = []
  }
}

watch(visible, (value) => {
  if (!value) return
  result.value = null
  form.communityId = undefined
  form.realName = ''
  form.phone = ''
  form.idCard = ''
  form.username = ''
  formRef.value?.clearValidate()
  if (communities.value.length === 0) {
    loadCommunities()
  }
})

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid || form.communityId === undefined) return
  submitting.value = true
  try {
    result.value = await adminCreateResident({
      communityId: form.communityId,
      realName: form.realName.trim(),
      phone: form.phone.trim(),
      idCard: form.idCard.trim() || undefined,
      username: form.username.trim() || undefined
    })
    emit('created', result.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '代建失败')
  } finally {
    submitting.value = false
  }
}

async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    /* 剪贴板权限被拒：保留手动选择复制，不视为错误 */
    ElMessage.info('复制失败，请手动选择复制')
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="result ? '代建成功' : '代建居民'"
    width="480px"
    :close-on-click-modal="false"
  >
    <!-- 结果段：初始密码仅本次返回，关闭后不可再查 -->
    <div v-if="result" class="create-result">
      <p class="result-tip">请将以下账号信息转交居民，初始密码仅本次展示，关闭后无法再次查看。</p>
      <div class="result-row">
        <span class="result-label">用户名</span>
        <code class="result-value">{{ result.username }}</code>
        <el-button text type="primary" size="small" @click="copyText(result.username)">复制</el-button>
      </div>
      <div class="result-row">
        <span class="result-label">初始密码</span>
        <code class="result-value">{{ result.initialPassword }}</code>
        <el-button text type="primary" size="small" @click="copyText(result.initialPassword)">复制</el-button>
      </div>
      <p class="result-hint">居民首次登录后建议尽快修改密码。</p>
    </div>

    <!-- 表单段 -->
    <el-form v-else ref="formRef" :model="form" :rules="rules" label-width="88px">
      <el-form-item label="所属社区" prop="communityId">
        <el-select
          v-model="form.communityId"
          placeholder="请选择社区（仅可选您管理的社区）"
          style="width: 100%"
        >
          <el-option
            v-for="item in communities"
            :key="item.id"
            :label="item.name"
            :value="item.id"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="真实姓名" prop="realName">
        <el-input v-model="form.realName" placeholder="居民真实姓名" maxlength="50" />
      </el-form-item>
      <el-form-item label="手机号" prop="phone">
        <el-input v-model="form.phone" placeholder="用于登录与联系" maxlength="11" />
      </el-form-item>
      <el-form-item label="身份证号" prop="idCard">
        <el-input v-model="form.idCard" placeholder="选填，18 位" maxlength="18" />
      </el-form-item>
      <el-form-item label="用户名" prop="username">
        <el-input
          v-model="form.username"
          placeholder="留空按手机号生成"
          maxlength="20"
        />
        <span class="form-hint">选填，4~20 字符（字母/数字/下划线）；初始密码为手机号后 6 位</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <template v-if="result">
        <el-button type="primary" @click="visible = false">我已转交，关闭</el-button>
      </template>
      <template v-else>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ submitting ? '创建中' : '代建账号' }}
        </el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.form-hint {
  display: block;
  margin-top: var(--spacing-xs);
  font-size: var(--font-size-xs);
  line-height: 1.5;
  color: var(--color-text-secondary);
}

.create-result {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.result-tip {
  margin: 0;
  font-size: var(--font-size-sm);
  line-height: 1.6;
  color: var(--color-warning);
}

.result-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background-color: var(--color-bg-subtle);
}

.result-label {
  flex-shrink: 0;
  width: 60px;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.result-value {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  word-break: break-all;
}

.result-hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
</style>
