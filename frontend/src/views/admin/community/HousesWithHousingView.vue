<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import { getCommunityList } from '@/api/community'
import {
  createHousing,
  updateHousing,
  updateHousingStatus,
  getHousingDetail
} from '@/api/housing'
import { http } from '@/utils/request'
import type { ICommunity } from '@/types/modules/community'
import { communityStatusLabels } from '@/types/modules/community'
import type { HousingRentType, HousingStatus } from '@/types/modules/housing'
import { housingRentTypeLabels, housingStatusLabels } from '@/types/modules/housing'

/**
 * 房屋与房源一体化（R62，v1.5）：社区行内展开 楼栋→单元→房屋 树，
 * 房屋行直接完成房源挂牌/编辑与上架/下架，社区头部按社区批量挂牌。
 * 一体化树端点与批量挂牌端点尚无 api 层封装（api/types 目录禁改），
 * 按契约由本视图内联调用（见本地契约接口注释），联调归主会话。
 */

/* 契约：GET /communities/{id}/houses-with-housing → 楼栋→单元→房屋→房源摘要树（R62 v1.5，待 api 层收编） */
interface HouseHousingSummary {
  id: number
  status: HousingStatus
  title: string
  monthlyRent: number
  deposit: number | null
  rentType: HousingRentType
}

interface TreeHouse {
  id: number
  houseNumber: string
  floor: number
  /** 无房源为 null（界面展示「未挂牌」） */
  housing: HouseHousingSummary | null
}

interface TreeUnit {
  id: number
  name: string
  houses: TreeHouse[]
}

interface TreeBuilding {
  id: number
  name: string
  units: TreeUnit[]
}

/* 本地载荷扩展：HousingSaveDTO 未含租售类型（types 目录冻结），
   后端 V9 已有 rent_type 列，请求体字段名 rentType 待联调确认 */
interface HousingSavePayload {
  houseId: number
  title: string
  description: string
  monthlyRent: number
  deposit?: number
  images: string
  rentType?: HousingRentType
}

const HOUSING_TAG_TYPE: Record<HousingStatus, 'completed' | 'pending' | 'processing' | 'canceled'> = {
  AVAILABLE: 'completed',
  RESERVED: 'pending',
  RENTED: 'processing',
  OFFLINE: 'canceled'
}

const rentTypeOptions = (Object.keys(housingRentTypeLabels) as HousingRentType[]).map((value) => ({
  value,
  label: housingRentTypeLabels[value]
}))

const router = useRouter()

/* ===================== 社区列表 ===================== */

const communities = ref<ICommunity[]>([])
const listLoading = ref(false)

async function loadCommunities(): Promise<void> {
  listLoading.value = true
  try {
    /* 管理端社区规模有限（数据级权限过滤后更少），单页拉取（结构树同口径） */
    const result = await getCommunityList({ page: 1, size: 200 })
    communities.value = result.records
  } catch (error) {
    communities.value = []
    ElMessage.error(error instanceof Error ? error.message : '加载社区列表失败')
  } finally {
    listLoading.value = false
  }
}

/* ===================== 一体化树（社区行内展开） ===================== */

const expanded = ref<Record<number, boolean>>({})
const buildings = ref<Record<number, TreeBuilding[]>>({})
const treeLoading = ref<Record<number, boolean>>({})

async function loadTree(communityId: number): Promise<void> {
  treeLoading.value[communityId] = true
  try {
    buildings.value[communityId] = await http.get<TreeBuilding[]>(
      `/communities/${communityId}/houses-with-housing`
    )
  } catch (error) {
    buildings.value[communityId] = []
    ElMessage.error(error instanceof Error ? error.message : '加载房屋与房源失败')
  } finally {
    treeLoading.value[communityId] = false
  }
}

function toggleCommunity(community: ICommunity): void {
  const willExpand = !expanded.value[community.id]
  expanded.value[community.id] = willExpand
  /* 首次展开才拉取；后续展开复用缓存，动作后按社区整体刷新 */
  if (willExpand && !buildings.value[community.id]) {
    void loadTree(community.id)
  }
}

onMounted(() => {
  void loadCommunities()
})

/* 社区头部计数：房屋/已挂牌/未挂牌（由已加载的树聚合，未展开不显示） */
const communitySummaries = computed(() => {
  const map = new Map<number, { houses: number; listed: number }>()
  for (const community of communities.value) {
    const list = buildings.value[community.id]
    if (!list) continue
    let houses = 0
    let listed = 0
    for (const building of list) {
      for (const unit of building.units) {
        for (const house of unit.houses) {
          houses += 1
          if (house.housing) listed += 1
        }
      }
    }
    map.set(community.id, { houses, listed })
  }
  return map
})

/** 全局房源视图入口：挂牌管理并入社区结构后，跨社区总览降级为视图内入口 */
function goGlobalHousing(): void {
  void router.push({ path: '/admin/housings', query: { tab: 'list' } })
}

/* ===================== 挂牌 / 编辑房源 ===================== */

const dialogVisible = ref(false)
const saving = ref(false)
const dialogContext = ref<{
  communityId: number
  houseId: number
  houseLabel: string
  housingId: number | null
  images: string
} | null>(null)

const form = reactive({
  title: '',
  monthlyRent: undefined as number | undefined,
  deposit: undefined as number | undefined,
  rentType: 'RENT' as HousingRentType,
  description: ''
})

function formatRent(value: number): string {
  return Number(value).toLocaleString('zh-CN')
}

/**
 * 打开挂牌/编辑：无房源走挂牌（标题默认「{楼栋}{单元}{房号}·精装房源」可改）；
 * 已有房源先拉房源详情回显描述与图片（摘要树不含），详情失败以摘要兜底。
 */
async function openHousingDialog(
  house: TreeHouse,
  building: TreeBuilding,
  unit: TreeUnit,
  community: ICommunity
): Promise<void> {
  const label = `${building.name}${unit.name}${house.houseNumber}`
  const summary = house.housing
  dialogContext.value = {
    communityId: community.id,
    houseId: house.id,
    houseLabel: label,
    housingId: summary?.id ?? null,
    images: ''
  }
  Object.assign(form, {
    title: summary ? summary.title : `${label}·精装房源`,
    monthlyRent: summary ? summary.monthlyRent : undefined,
    deposit: summary ? (summary.deposit ?? undefined) : undefined,
    rentType: summary ? summary.rentType : 'RENT',
    description: ''
  })
  dialogVisible.value = true

  if (summary) {
    try {
      const detail = await getHousingDetail(summary.id)
      form.description = detail.description
      /* 更新载荷须回传既有图片串（后端按整串落值），避免详情拉取后图片被清空 */
      dialogContext.value.images = detail.images.join(',')
    } catch {
      /* 详情拉取失败：摘要兜底已回显关键字段，图片留空待用户补填 */
    }
  }
}

async function handleSave(): Promise<void> {
  const context = dialogContext.value
  if (!context) return
  if (!form.title.trim()) {
    ElMessage.warning('请填写房源标题')
    return
  }
  if (form.monthlyRent == null) {
    ElMessage.warning('请填写月租金')
    return
  }
  saving.value = true
  try {
    const payload: HousingSavePayload = {
      houseId: context.houseId,
      title: form.title.trim(),
      description: form.description,
      monthlyRent: form.monthlyRent,
      deposit: form.deposit,
      images: context.images,
      rentType: form.rentType
    }
    if (context.housingId === null) {
      await createHousing(payload)
      ElMessage.success('房源已挂牌')
    } else {
      await updateHousing(context.housingId, payload)
      ElMessage.success('房源已更新')
    }
    dialogVisible.value = false
    await loadTree(context.communityId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存房源失败')
  } finally {
    saving.value = false
  }
}

/* ===================== 上架 / 下架 ===================== */

/** 上架/下架：口径与全局房源列表一致（OFFLINE ↔ AVAILABLE，下架二次确认） */
async function handleToggleStatus(house: TreeHouse, community: ICommunity): Promise<void> {
  const housing = house.housing
  if (!housing) return
  if (housing.status !== 'OFFLINE') {
    try {
      await ElMessageBox.confirm(`确认下架「${housing.title}」？下架后居民端不再展示`, '下架房源', {
        confirmButtonText: '确认下架',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await updateHousingStatus(housing.id, { status: 'OFFLINE' })
      ElMessage.success('房源已下架')
      await loadTree(community.id)
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      ElMessage.error(error instanceof Error ? error.message : '下架失败')
    }
    return
  }
  try {
    await updateHousingStatus(housing.id, { status: 'AVAILABLE' })
    ElMessage.success('房源已上架')
    await loadTree(community.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上架失败')
  }
}

/* ===================== 批量挂牌（按社区） ===================== */

/* 契约：POST /housings/batch-generate {communityId, monthlyRent, deposit, rentType?}
   → {created, skipped}（R62 v1.5，待 api 层收编为 batchGenerateHousings） */
const batchVisible = ref(false)
const batchSubmitting = ref(false)
const batchCommunity = ref<ICommunity | null>(null)
const batchForm = reactive({
  monthlyRent: undefined as number | undefined,
  deposit: undefined as number | undefined,
  rentType: 'RENT' as HousingRentType
})

function openBatchDialog(community: ICommunity): void {
  batchCommunity.value = community
  batchForm.monthlyRent = undefined
  batchForm.deposit = undefined
  batchForm.rentType = 'RENT'
  batchVisible.value = true
}

async function handleBatchSubmit(): Promise<void> {
  const community = batchCommunity.value
  if (!community) return
  if (batchForm.monthlyRent == null) {
    ElMessage.warning('请填写默认月租金')
    return
  }
  batchSubmitting.value = true
  try {
    const result = await http.post<{ created: number; skipped: number }>(
      '/housings/batch-generate',
      {
        communityId: community.id,
        monthlyRent: batchForm.monthlyRent,
        deposit: batchForm.deposit ?? null,
        rentType: batchForm.rentType
      }
    )
    ElMessage.success(`批量挂牌完成：新建 ${result.created} 套，跳过 ${result.skipped} 套（已有房源）`)
    batchVisible.value = false
    await loadTree(community.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量挂牌失败')
  } finally {
    batchSubmitting.value = false
  }
}
</script>

<template>
  <section class="houses-housing">
    <header class="page-lead">
      <div class="page-lead-text">
        <h2 class="page-lead-title">房屋与房源</h2>
        <p class="page-lead-sub">按社区展开 楼栋 → 单元 → 房屋，房屋行内直接挂牌、编辑与上下架</p>
      </div>
      <el-button type="primary" link @click="goGlobalHousing">全局房源视图 →</el-button>
    </header>

    <div v-loading="listLoading" class="community-rows">
      <article v-for="community in communities" :key="community.id" class="community-card">
        <header class="community-head">
          <button
            type="button"
            class="community-toggle"
            :aria-expanded="!!expanded[community.id]"
            @click="toggleCommunity(community)"
          >
            <svg
              class="toggle-chevron"
              :class="{ 'is-open': expanded[community.id] }"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M9 6l6 6-6 6" />
            </svg>
            <span class="community-name">{{ community.name }}</span>
            <StatusTag
              :label="communityStatusLabels[community.status]"
              :type="community.status === 'ACTIVE' ? 'completed' : 'canceled'"
            />
            <span v-if="communitySummaries.get(community.id)" class="community-counts">
              房屋 {{ communitySummaries.get(community.id)!.houses }} · 已挂牌
              {{ communitySummaries.get(community.id)!.listed }} · 未挂牌
              {{ communitySummaries.get(community.id)!.houses - communitySummaries.get(community.id)!.listed }}
            </span>
          </button>
          <el-button
            v-permission="['ADMIN', 'SUPER_ADMIN']"
            size="small"
            @click="openBatchDialog(community)"
          >
            批量挂牌
          </el-button>
        </header>

        <div v-if="expanded[community.id]" v-loading="treeLoading[community.id]" class="community-body">
          <p
            v-if="!treeLoading[community.id] && (buildings[community.id] ?? []).length === 0"
            class="body-empty"
          >
            该社区暂无楼栋或房屋数据加载失败
          </p>
          <div v-for="building in buildings[community.id] ?? []" :key="building.id" class="building-block">
            <h4 class="building-name">{{ building.name }}</h4>
            <p v-if="building.units.length === 0" class="unit-empty">暂无单元</p>
            <div v-for="unit in building.units" :key="unit.id" class="unit-block">
              <p class="unit-name">{{ unit.name }}</p>
              <p v-if="unit.houses.length === 0" class="unit-empty">暂无房屋</p>
              <div v-else class="house-rows">
                <div v-for="house in unit.houses" :key="house.id" class="house-row">
                  <span class="house-number">{{ house.houseNumber }}</span>
                  <span class="house-floor">{{ house.floor }} 层</span>
                  <StatusTag
                    v-if="house.housing"
                    :label="housingStatusLabels[house.housing.status]"
                    :type="HOUSING_TAG_TYPE[house.housing.status]"
                  />
                  <StatusTag v-else label="未挂牌" type="canceled" />
                  <span v-if="house.housing" class="house-rent"
                    >¥{{ formatRent(house.housing.monthlyRent) }}/月</span
                  >
                  <span class="row-actions">
                    <el-button
                      v-permission="['ADMIN', 'SUPER_ADMIN']"
                      link
                      type="primary"
                      size="small"
                      @click="openHousingDialog(house, building, unit, community)"
                    >
                      {{ house.housing ? '编辑房源' : '挂牌' }}
                    </el-button>
                    <el-button
                      v-if="house.housing"
                      v-permission="['ADMIN', 'SUPER_ADMIN']"
                      link
                      :type="house.housing.status === 'OFFLINE' ? 'success' : 'warning'"
                      size="small"
                      @click="handleToggleStatus(house, community)"
                    >
                      {{ house.housing.status === 'OFFLINE' ? '上架' : '下架' }}
                    </el-button>
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </article>

      <el-empty v-if="!listLoading && communities.length === 0" description="暂无社区，请先到结构总览新建" />
    </div>

    <!-- 挂牌 / 编辑房源 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogContext?.housingId ? '编辑房源' : '房屋挂牌'"
      width="560px"
      destroy-on-close
    >
      <p class="dialog-context">{{ dialogContext?.houseLabel }}</p>
      <el-form label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="60" show-word-limit placeholder="如：精装两居 · 拎包入住" />
        </el-form-item>
        <el-form-item label="月租金" required>
          <el-input-number v-model="form.monthlyRent" :min="0" :step="100" />
          <span class="form-unit">元/月</span>
        </el-form-item>
        <el-form-item label="押金">
          <el-input-number v-model="form.deposit" :min="0" :step="100" />
          <span class="form-unit">元（不填则面议）</span>
        </el-form-item>
        <el-form-item label="租售类型" required>
          <el-radio-group v-model="form.rentType">
            <el-radio v-for="option in rentTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="1000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量挂牌：为社区内无房源房屋批量生成 AVAILABLE 房源 -->
    <el-dialog v-model="batchVisible" title="批量挂牌" width="480px" destroy-on-close>
      <p class="dialog-context">
        为「{{ batchCommunity?.name }}」下全部无房源房屋按默认参数生成在售（可租）房源，已有房源的房屋自动跳过。
      </p>
      <el-form label-width="80px">
        <el-form-item label="默认月租" required>
          <el-input-number v-model="batchForm.monthlyRent" :min="0" :step="100" />
          <span class="form-unit">元/月</span>
        </el-form-item>
        <el-form-item label="押金">
          <el-input-number v-model="batchForm.deposit" :min="0" :step="100" />
          <span class="form-unit">元（不填则面议）</span>
        </el-form-item>
        <el-form-item label="租售类型">
          <el-radio-group v-model="batchForm.rentType">
            <el-radio v-for="option in rentTypeOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchSubmitting" @click="handleBatchSubmit">
          确认批量挂牌
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.houses-housing {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-height: 320px;
}

/* ---------- 页首说明 ---------- */

.page-lead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--spacing-md);
  flex-wrap: wrap;
}

.page-lead-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.page-lead-sub {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

/* ---------- 社区行卡片 ---------- */

.community-rows {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.community-card {
  background-color: var(--admin-card-bg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  overflow: hidden;
}

.community-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-lg);
}

.community-toggle {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex: 1;
  min-width: 0;
  padding: var(--spacing-xs) 0;
  border: none;
  background: none;
  font-family: inherit;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  text-align: left;
  cursor: pointer;
}

.toggle-chevron {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  color: var(--color-text-secondary);
  transition: transform 0.15s ease;
}

.toggle-chevron.is-open {
  transform: rotate(90deg);
}

.community-counts {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ---------- 一体化树 ---------- */

.community-body {
  padding: 0 var(--spacing-lg) var(--spacing-lg);
  border-top: 1px solid var(--color-border);
}

.body-empty,
.unit-empty {
  margin: 0;
  padding: var(--spacing-sm) 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.building-block {
  padding-top: var(--spacing-md);
}

.building-name {
  margin: 0 0 var(--spacing-xs);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.unit-block {
  padding-left: var(--spacing-lg);
}

.unit-name {
  margin: var(--spacing-xs) 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.house-rows {
  display: flex;
  flex-direction: column;
}

.house-row {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-xs) 0;
  border-bottom: 1px dashed var(--color-border);
}

.house-row:last-child {
  border-bottom: none;
}

.house-number {
  min-width: 56px;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.house-floor {
  width: 48px;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.house-rent {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.row-actions {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  flex-shrink: 0;
}

/* ---------- 对话框 ---------- */

.dialog-context {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.form-unit {
  margin-left: var(--spacing-sm);
  color: var(--color-text-disabled);
  font-size: var(--font-size-xs);
}

/* ---------- 响应式 ---------- */

@media (max-width: 767px) {
  .house-row {
    flex-wrap: wrap;
  }

  .row-actions {
    margin-left: 0;
  }
}
</style>
