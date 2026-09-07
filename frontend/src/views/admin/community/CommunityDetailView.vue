<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { getBuildingList, getCommunity, updateCommunity } from '@/api/community'
import type { IBuilding, ICommunity, ICreateCommunityDTO } from '@/types/modules/community'
import { communityStatusLabels } from '@/types/modules/community'
import { formatDateTime } from '@/utils/date'
import StatusTag from '@/components/common/StatusTag.vue'
import Pagination from '@/components/common/Pagination.vue'

/** 社区详情：基本信息 el-descriptions + 编辑对话框 + 该社区楼栋列表 */
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

function goBack(): void {
  router.push('/admin/communities')
}

function goBuildings(): void {
  router.push({ path: '/admin/buildings', query: { communityId: String(communityId) } })
}

/* ---------------------------------- 编辑对话框 ---------------------------------- */

const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<ICreateCommunityDTO>({
  name: '',
  address: '',
  contactPhone: '',
  contactPerson: '',
  description: ''
})

const rules: FormRules = {
  name: [{ required: true, message: '请输入社区名称', trigger: 'blur' }],
  address: [{ required: true, message: '请输入社区地址', trigger: 'blur' }]
}

function openEdit(): void {
  if (!community.value) return
  form.name = community.value.name
  form.address = community.value.address
  form.contactPhone = community.value.contactPhone ?? ''
  form.contactPerson = community.value.contactPerson ?? ''
  form.description = community.value.description ?? ''
  dialogVisible.value = true
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  try {
    await updateCommunity(communityId, { ...form })
    ElMessage.success('社区已更新')
    dialogVisible.value = false
    loadCommunity()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  }
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
  <section v-loading="loading" class="community-detail">
    <div class="detail-header">
      <div class="detail-title">
        <el-button link @click="goBack">← 返回社区列表</el-button>
        <h2 v-if="community">{{ community.name }}</h2>
      </div>
      <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" @click="openEdit">编辑社区</el-button>
    </div>

    <el-descriptions v-if="community" :column="2" border class="detail-descriptions">
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
      <el-descriptions-item label="创建时间">{{ formatDateTime(community.createdAt) }}</el-descriptions-item>
      <el-descriptions-item label="更新时间">{{ formatDateTime(community.updatedAt) }}</el-descriptions-item>
      <el-descriptions-item label="社区简介" :span="2">{{ community.description || '-' }}</el-descriptions-item>
    </el-descriptions>

    <div class="building-section">
      <div class="section-header">
        <h3>楼栋列表</h3>
        <el-button type="primary" link @click="goBuildings">前往楼栋管理 →</el-button>
      </div>
      <el-table v-loading="buildingsLoading" :data="buildings" border>
        <el-table-column prop="id" label="ID" width="64" />
        <el-table-column prop="name" label="楼栋名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="totalFloors" label="总层数" width="90" />
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
    </div>

    <el-dialog v-model="dialogVisible" title="编辑社区" width="520px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="社区名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入社区名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="社区地址" prop="address">
          <el-input v-model="form.address" placeholder="请输入社区地址" maxlength="100" />
        </el-form-item>
        <el-form-item label="联系人" prop="contactPerson">
          <el-input v-model="form.contactPerson" placeholder="请输入联系人" maxlength="20" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="form.contactPhone" placeholder="请输入联系电话" maxlength="20" />
        </el-form-item>
        <el-form-item label="社区简介" prop="description">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            placeholder="请输入社区简介"
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
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.detail-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
}

.detail-title h2 {
  font-size: var(--font-size-lg);
  color: var(--color-text-primary);
  margin: 0;
}

.detail-descriptions {
  background-color: #fff;
  border-radius: var(--radius-md);
  margin-bottom: var(--spacing-lg);
}

.building-section {
  background-color: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--spacing-md);
}

.section-header h3 {
  font-size: var(--font-size-md);
  color: var(--color-text-primary);
  margin: 0;
}
</style>
