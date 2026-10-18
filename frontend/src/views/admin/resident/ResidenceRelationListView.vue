<script setup lang="ts">
/**
 * 居住关系 Tab：按居民查询居住关系（接口仅提供居民/房屋两视角列表，
 * 台账采用居民视角 9.2.3.1）+ 在住关系搬出登记（9.2.3.3）。
 * 支持由居民列表「居住关系」操作预选居民（presetResidentId）。
 */
import ResidentCoResidenceDialog from './ResidentCoResidenceDialog.vue'
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import {
  getResidentList,
  getResidenceDependents,
  getResidentResidenceList,
  moveOutResidenceRelation
} from '@/common/resident/api/resident'
import type {
  IResident,
  IResidenceRelation,
  ResidenceRelationStatus
} from '@/common/resident/types/resident'
import { residenceRelationStatusLabels } from '@/common/resident/types/resident'
import { formatDate, todayISO } from '@/common/web/utils/date'
import StatusTag from '@/common/web/components/StatusTag.vue'
import FilterPanel from '@/common/web/components/FilterPanel.vue'
import Pagination from '@/common/web/components/Pagination.vue'

const props = defineProps<{ presetResidentId?: number }>()

const relations = ref<IResidenceRelation[]>([])
const coVisible=ref(false), coAnchor=ref<IResidenceRelation|null>(null)
const moveOutMembers=ref<IResidenceRelation[]>([])
const memberLoading=ref(false), memberError=ref('')
let moveOutVersion=0
function openCo(row:IResidenceRelation){coAnchor.value=row;coVisible.value=true}
const householdMembers = ref<Record<number,IResidenceRelation[]>>({})
const householdErrors = ref<Record<number,string>>({})
let loadVersion = 0
function accountSummary(row:IResidenceRelation):string {
  return `${row.residentName || '未填写姓名'}（用户名 ${row.residentUsername || '未提供'}，手机号 ${row.residentPhone || '未提供'}，关系 #${row.id}）`
}
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const statusFilter = ref<ResidenceRelationStatus | ''>('')

/* 居民选择器（远程搜索，接口无全量居住关系列表，必须先定位居民） */
const residentId = ref<number | undefined>(undefined)
const residentOptions = ref<IResident[]>([])
const residentLoading = ref(false)

/** 居住关系状态 → StatusTag 语义色（居住中=绿 / 已迁出=灰） */
function statusTagType(status: ResidenceRelationStatus): 'completed' | 'canceled' {
  return status === 'ACTIVE' ? 'completed' : 'canceled'
}

async function load(): Promise<void> {
  const version = ++loadVersion
  householdMembers.value = {}; householdErrors.value = {}
  if (!residentId.value) {
    relations.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await getResidentResidenceList(residentId.value, {
      page: page.value,
      size: size.value,
      status: statusFilter.value || undefined
    })
    if (version !== loadVersion) return
    relations.value = result.records
    total.value = result.total
    await Promise.all(result.records.filter(row => row.status==='ACTIVE' && !row.anchorRelationId && ['OWNER','TENANT'].includes(row.relationType ?? '')).map(async row => {
      try { const members = await getResidenceDependents(row.id); if(version===loadVersion) householdMembers.value[row.id] = members }
      catch { if(version===loadVersion) householdErrors.value[row.id] = '成员加载失败，点击管理重新查询' }
    }))
  } catch {
    if(version!==loadVersion)return
    relations.value = []
    total.value = 0
  } finally {
    if(version===loadVersion)loading.value = false
  }
}

async function searchResidents(keyword: string): Promise<void> {
  residentLoading.value = true
  try {
    const result = await getResidentList({ page: 1, size: 20, keyword: keyword || undefined })
    residentOptions.value = result.records
  } catch {
    residentOptions.value = []
  } finally {
    residentLoading.value = false
  }
}

/* 预选居民（列表 Tab「居住关系」跳转）：带出选项并加载其关系 */
async function applyPreset(residentIdValue: number): Promise<void> {
  residentId.value = residentIdValue
  page.value = 1
  await searchResidents('')
  load()
}

watch(
  () => props.presetResidentId,
  (value) => {
    if (value && value !== residentId.value) {
      applyPreset(value)
    }
  }
)

function handleResidentChange(): void {
  page.value = 1
  load()
}

function handleStatusChange(): void {
  page.value = 1
  load()
}

/* 重置后必重新加载（清空选择即回到「请先选择居民」空态，保持单一路径刷新） */
function handleReset(): void {
  residentId.value = undefined
  statusFilter.value = ''
  page.value = 1
  load()
}

/* 搬出登记：必须填写搬出日期 */
const moveOutVisible = ref(false)
const moveOutLoading = ref(false)
const moveOutFormRef = ref<FormInstance>()
const moveOutTarget = ref<IResidenceRelation | null>(null)
const moveOutForm = reactive<{ moveOutDate: string; reason: string }>({
  moveOutDate: '',
  reason: ''
})

const moveOutRules: FormRules = {
  moveOutDate: [{ required: true, message: '请选择搬出日期', trigger: 'change' }],
  reason: [{ max: 200, message: '搬出原因不超过 200 字', trigger: 'blur' }]
}

async function openMoveOut(row: IResidenceRelation): Promise<void> {
  moveOutTarget.value = row
  moveOutFormRef.value?.resetFields()
  Object.assign(moveOutForm, { moveOutDate: todayISO(), reason: '' })
  moveOutVisible.value = true
  const version=++moveOutVersion;moveOutMembers.value=[];memberError.value='';memberLoading.value=true
  try{const members=await getResidenceDependents(row.id);if(version===moveOutVersion&&moveOutVisible.value)moveOutMembers.value=members}
  catch(e){if(version===moveOutVersion)memberError.value=e instanceof Error?e.message:'同住关系核对失败'}
  finally{if(version===moveOutVersion)memberLoading.value=false}
}

async function handleMoveOut(): Promise<void> {
  if (!moveOutTarget.value || memberLoading.value || memberError.value) return
  const valid = await moveOutFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!moveOutTarget.value.anchorRelationId) {
    try {
      await ElMessageBox.confirm(`主住户：${accountSummary(moveOutTarget.value)}。房屋：${moveOutTarget.value.houseLocation}。迁出日期：${moveOutForm.moveOutDate}。${moveOutMembers.value.length ? `以下同住关系同时结束：${moveOutMembers.value.map(accountSummary).join('；')}` : '当前无有效依赖成员。'} 账号和历史记录保留。`, '第二次确认主住户迁出', { type:'warning', confirmButtonText: '确认结束整户关系', cancelButtonText:'返回核对' })
    } catch { return }
  }
  moveOutLoading.value = true
  try {
    await moveOutResidenceRelation(moveOutTarget.value.id, {
      moveOutDate: moveOutForm.moveOutDate,
      confirmDependents: true,
      reason: moveOutForm.reason.trim() || undefined
    })
    ElMessage.success('搬出登记成功')
    moveOutVisible.value = false
    load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '搬出登记失败')
  } finally {
    moveOutLoading.value = false
  }
}

onMounted(() => {
  if (props.presetResidentId) {
    applyPreset(props.presetResidentId)
  } else {
    searchResidents('')
  }
})
</script>

<template>
  <section class="relation-list">
    <FilterPanel resettable @reset="handleReset">
      <el-select
        v-model="residentId"
        filterable
        remote
        clearable
        :remote-method="searchResidents"
        :loading="residentLoading"
        placeholder="搜索并选择居民（用户名/姓名/手机号）"
        class="resident-select"
        @change="handleResidentChange"
        @focus="searchResidents('')"
      >
        <el-option
          v-for="item in residentOptions"
          :key="item.id"
          :label="`${item.realName}（${item.username}）`"
          :value="item.id"
        />
      </el-select>
      <el-select
        v-model="statusFilter"
        clearable
        :disabled="!residentId"
        placeholder="全部状态"
        class="status-select"
        @change="handleStatusChange"
      >
        <el-option
          v-for="(label, value) in residenceRelationStatusLabels"
          :key="value"
          :label="label"
          :value="value"
        />
      </el-select>
    </FilterPanel>

    <div class="table-panel">
      <el-table
        v-loading="loading"
        :data="relations"
      >
        <el-table-column prop="residentName" label="居民" min-width="110" show-overflow-tooltip />
        <el-table-column label="同住人员" min-width="240">
          <template #default="{ row }">
            <template v-if="row.status==='ACTIVE' && !row.anchorRelationId && ['OWNER','TENANT'].includes(row.relationType)">
              <span v-if="householdErrors[row.id]">{{ householdErrors[row.id] }}</span>
              <span v-else-if="!householdMembers[row.id]">正在核对…</span>
              <span v-else-if="!householdMembers[row.id]?.length">暂无同住人员</span>
              <div v-for="member in householdMembers[row.id]" :key="member.id" class="household-member">
                <span>{{ member.residentName }} · {{ member.residentUsername }}（{{ member.relationType==='FAMILY'?'家属':'同住人员' }}）</span>
                <el-button text size="small" type="warning" @click="openMoveOut(member)">个人迁出</el-button>
              </div>
              <el-button text type="primary" @click="openCo(row)">管理 / 添加同住人员</el-button>
            </template>
            <span v-else>{{ row.anchorRelationId ? `依赖主关系 #${row.anchorRelationId}` : '历史关系' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="houseLocation" label="房屋" min-width="180" show-overflow-tooltip />
        <el-table-column label="入住日期" width="120">
          <template #default="{ row }">{{ formatDate(row.moveInDate) }}</template>
        </el-table-column>
        <el-table-column label="搬出日期" width="120">
          <template #default="{ row }">{{ formatDate(row.moveOutDate) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <StatusTag
              :label="residenceRelationStatusLabels[row.status as ResidenceRelationStatus]"
              :type="statusTagType(row.status)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status==='ACTIVE' && !row.anchorRelationId && ['OWNER','TENANT'].includes(row.relationType)" text type="primary" @click="openCo(row)">登记同住人员</el-button>
            <el-button
              v-if="row.status === 'ACTIVE'"
              v-permission="['ADMIN', 'SUPER_ADMIN']"
              text
              type="warning"
              size="small"
              @click="openMoveOut(row)"
            >
              搬出登记
            </el-button>
            <span v-else class="op-done">-</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="residentId ? '暂无居住关系' : '请先在上方选择居民'" :image-size="80" />
        </template>
      </el-table>

      <Pagination
        v-model:page="page"
        v-model:size="size"
        :total="total"
        @update:page="load"
        @update:size="load"
      />
    </div>

    <!-- 搬出登记 -->
    <el-dialog v-model="moveOutVisible" title="搬出登记" width="480px">
      <el-alert
        v-if="moveOutTarget"
        :title="`登记后「${moveOutTarget.residentName}」与「${moveOutTarget.houseLocation}」的居住关系将结束，关联租住记录同步终止`"
        type="info"
        :closable="false"
        class="move-out-tip"
      />
      <p v-if="moveOutTarget">{{ moveOutTarget.anchorRelationId ? '迁出成员' : '主关系账号' }}：{{ accountSummary(moveOutTarget) }}</p>
      <ul v-if="moveOutMembers.length"><li v-for="member in moveOutMembers" :key="member.id">{{ accountSummary(member) }}</li></ul>
      <el-alert v-if="memberError" :title="memberError" type="error" :closable="false" />
      <p v-if="memberLoading">正在核对同住关系…</p>
      <el-alert v-if="moveOutMembers.length" type="warning" :closable="false" :title="`同时结束以下 ${moveOutMembers.length} 名同住人员的居住关系：${moveOutMembers.map(m=>m.residentName).join('、')}。账号和历史保留。`" />
      <el-form ref="moveOutFormRef" :model="moveOutForm" :rules="moveOutRules" label-width="90px">
        <el-form-item label="搬出日期" prop="moveOutDate">
          <el-date-picker
            v-model="moveOutForm.moveOutDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择搬出日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="搬出原因" prop="reason">
          <el-input
            v-model="moveOutForm.reason"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="选填，如：租约到期"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moveOutVisible = false">取消</el-button>
        <el-button type="primary" :loading="moveOutLoading" :disabled="memberLoading || !!memberError" @click="handleMoveOut">
          {{ moveOutMembers.length ? '确认整户迁出' : '确认登记' }}
        </el-button>
      </template>
    </el-dialog>
  <ResidentCoResidenceDialog v-model="coVisible" :anchor="coAnchor" @saved="load" />
  </section>
</template>

<style scoped>
.resident-select {
  width: 260px;
}

.status-select {
  width: 130px;
}

.table-panel {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md) var(--spacing-md) 0;
}

.op-done {
  color: var(--color-text-disabled);
}

.move-out-tip {
  margin-bottom: var(--spacing-md);
}
</style>
