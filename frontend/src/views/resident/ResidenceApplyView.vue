<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getHousingDetail } from '@/api/housing'
import { getCommunity } from '@/api/community'
import { createResidenceApplication } from '@/api/resident'
import { APPLICATION_PENDING_EXISTS } from '@/constants/apiCodes'
import { ApiError } from '@/utils/request'
import type { IHousing } from '@/types/modules/housing'
import { housingRentTypeLabels } from '@/types/modules/housing'
import type { RelationType } from '@/types/modules/resident'
import { relationTypeLabels } from '@/types/modules/resident'

/**
 * 申请租住（R62 补：居民端此前缺少直接的租住申请入口；R62 补 v2：社区级自动通过）。
 * 流程：选择身份（租客 / 业主 / 家属）→ 提交入住申请 → 管理方审核通过后
 * 生成居住关系与租约（租期从审核通过起算）。
 * 社区开启「入住申请自动通过」后提交即自动审核，正式租客关系延后至协议与首期成功，本页据社区配置切换文案与结果态，
 * 结果态以提交响应的 status 为准（不靠前端猜测社区开关）。
 *
 * 批次 g：已有待审核申请被拒（后端 ErrorCode.APPLICATION_PENDING_EXISTS）时，
 * 提示语必须指引到「租住与房源 → 入住申请」（本人申请的进度与撤回入口），
 * 而不是让居民只看到一句「已有待审核」却无处可去。
 */

const route = useRoute()
const router = useRouter()

const housingId = Number(route.params.id)
const housing = ref<IHousing | null>(null)
const loading = ref(true)
const submitting = ref(false)
const submitted = ref(false)
/** 提交被拒：已有待审核申请（同社区），页面给出查看/撤回的直达入口 */
const pendingExists = ref(false)
/** 提交后是否即时通过（社区自动审批路径）：决定结果态文案与后续引导 */
const autoApproved = ref(false)
/** 社区是否开启自动通过（提交前提示文案用；取不到时按人工审核口径展示） */
const communityAutoApprove = ref(false)

const form = reactive({
  relationType: 'TENANT' as RelationType,
  remark: ''
})

const relationOptions: Array<{ value: RelationType; label: string; hint: string }> = [
  { value: 'TENANT', label: relationTypeLabels.TENANT, hint: '租赁入住，审核通过后生成租约' },
  { value: 'OWNER', label: relationTypeLabels.OWNER, hint: '房屋产权人登记（自住）' }
]

/** 仅可租房源可申请租住（与后端房屋状态校验同口径） */
const rentable = computed(() => housing.value?.status === 'AVAILABLE')

async function loadHousing(): Promise<void> {
  loading.value = true
  try {
    housing.value = await getHousingDetail(housingId)
    await loadCommunityPolicy(housing.value.communityId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载房源失败')
  } finally {
    loading.value = false
  }
}

/* 社区自动审批开关（GET /communities/{id} 对居民开放）：仅影响文案，判定仍以后端提交结果为准 */
async function loadCommunityPolicy(communityId: number | undefined): Promise<void> {
  if (!communityId) return
  try {
    const community = await getCommunity(communityId)
    communityAutoApprove.value = community.autoApproveResidence === 1
  } catch {
    /* 取不到社区配置不影响申请主流程，按人工审核口径提示 */
    communityAutoApprove.value = false
  }
}

async function handleSubmit(): Promise<void> {
  if (!housing.value || submitting.value) return
  submitting.value = true
  pendingExists.value = false
  try {
    const application = await createResidenceApplication({
      houseId: housing.value.houseId,
      housingId: housing.value.id,
      relationType: form.relationType,
      remark: form.remark.trim() === '' ? undefined : form.remark.trim()
    })
    autoApproved.value = application.status === 'APPROVED'
    submitted.value = true
    ElMessage.success(autoApproved.value ? '申请已通过，租约已生成' : '申请已提交，等待管理方审核')
  } catch (error) {
    if (error instanceof ApiError && error.code === APPLICATION_PENDING_EXISTS) {
      /* 已有待审核申请：给出进度与撤回入口，避免居民只看到一句拒绝却无处可去 */
      pendingExists.value = true
      ElMessage.warning('您已有待审核的入住申请，可在「租住与房源 → 入住申请」查看或撤回')
    } else {
      /* 其余业务错误（已在住/房屋不可租）直接展示后端 message */
      ElMessage.error(error instanceof Error ? error.message : '申请提交失败')
    }
  } finally {
    submitting.value = false
  }
}

/* Hub 内跳转：租约在「租住契约」Tab，本人申请在「入住申请」Tab */
function goHub(tab: 'lease' | 'application'): void {
  router.push({ path: '/resident/housing-lease', query: { tab } })
}

onMounted(() => {
  if (!Number.isInteger(housingId) || housingId <= 0) {
    router.replace('/resident/housings')
    return
  }
  void loadHousing()
})
</script>

<template>
  <section v-loading="loading" class="apply-page">
    <nav class="breadcrumb">
      <router-link to="/resident/housings">房源</router-link>
      <span class="breadcrumb-sep">/</span>
      <router-link v-if="housing" :to="`/resident/housings/${housingId}`">{{ housing.title }}</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">申请租住</span>
    </nav>

    <header class="page-head">
      <h1>申请租住</h1>
      <p class="page-head-sub">
        {{ communityAutoApprove
          ? '本社区自动审核：提交后保留房屋并生成待签约租约；协议确认且首期成功后入住'
          : '提交后临时保留房屋，由管理方审核；协议确认且首期成功后正式入住' }}
      </p>
    </header>

    <!-- 提交被拒态：已有待审核申请时给出「查看/撤回」直达入口（批次 g） -->
    <div v-if="pendingExists" class="result-card card">
      <h2>您已有待审核的入住申请</h2>
      <p>
        同一房屋（或同一社区在住/在审）无法重复提交。请在「租住与房源 → 入住申请」查看审核进度，
        若不再需要该申请，可在该页撤回后重新提交。
      </p>
      <div class="result-actions">
        <el-button type="primary" @click="goHub('application')">查看我的入住申请</el-button>
        <el-button @click="router.back()">返回</el-button>
      </div>
    </div>

    <!-- 提交成功态：区分自动通过与待审核两条路径，明确后续去哪看结果 -->
    <div v-else-if="submitted" class="result-card card">
      <h2>{{ autoApproved ? '申请已通过，租约已生成' : '申请已提交' }}</h2>
      <p v-if="autoApproved">
        本社区已开启入住申请自动通过，租约已生成。可在「租住与房源 → 租住契约」查看租期、租金与押金，
        并留意管理方发起的租赁协议确认。
      </p>
      <p v-else>
        管理方审核通过后，您将收到通知；审核进度可在「租住与房源 → 入住申请」查看（待审核可撤回），
        通过后可在「租住契约」查看租期与租金。
      </p>
      <div class="result-actions">
        <el-button type="primary" @click="goHub('lease')">查看我的租约</el-button>
        <el-button @click="goHub('application')">查看我的申请</el-button>
        <el-button @click="router.push('/resident/housings')">继续看房源</el-button>
      </div>
    </div>

    <div v-else-if="!loading && !housing" class="result-card card">
      <h2>该房源已下线或暂不可申请</h2>
      <p>房屋可能已被入住申请或租约保留。请查看本人申请进度，或选择其他房源。</p>
      <el-button type="primary" @click="goHub('application')">查看我的入住申请</el-button>
      <el-button @click="router.push('/resident/housings')">返回房源列表</el-button>
    </div>
    <div v-else-if="housing" class="apply-layout">
      <!-- 左：房源摘要 -->
      <aside class="housing-summary card">
        <div class="summary-cover">
          <img
            :src="housing.images.length > 0 ? housing.images[0] : '/housing-placeholder.png'"
            :alt="housing.title"
          />
        </div>
        <h2 class="summary-title">{{ housing.title }}</h2>
        <p class="summary-loc">{{ housing.communityName }} {{ housing.houseLocation }}</p>
        <dl class="summary-meta">
          <div>
            <dt>月租</dt>
            <dd class="mono">¥ {{ housing.monthlyRent.toLocaleString('zh-CN') }}/月</dd>
          </div>
          <div>
            <dt>押金</dt>
            <dd class="mono">{{ housing.deposit != null ? `¥ ${housing.deposit.toLocaleString('zh-CN')}` : '面议' }}</dd>
          </div>
          <div>
            <dt>类型</dt>
            <dd>{{ housingRentTypeLabels[housing.rentType] }}</dd>
          </div>
          <div>
            <dt>户型</dt>
            <dd>{{ housing.layout || '—' }}</dd>
          </div>
        </dl>
        <p v-if="!rentable" class="summary-warn">该房源当前不可申请（已下架或已被预定）</p>
      </aside>

      <!-- 右：申请表单 -->
      <div class="apply-form card">
        <h2 class="form-title">申请信息</h2>
        <el-form label-width="90px">
          <el-form-item label="申请身份" required>
            <el-radio-group v-model="form.relationType" class="relation-group">
              <el-radio v-for="option in relationOptions" :key="option.value" :value="option.value">
                {{ option.label }}
                <span class="relation-hint">{{ option.hint }}</span>
              </el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="备注">
            <el-input
              v-model="form.remark"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="选填，如期望入住时间、随行家人等，便于管理方审核"
            />
          </el-form-item>
        </el-form>

        <div class="form-tip">
          <template v-if="communityAutoApprove">
            <p>· 本社区已开启入住申请自动通过，提交后立即生成待签约租约，不提前建立正式租客关系；</p>
            <p>· 租期按社区默认租期起算，租金与押金取本次申请提交时的挂牌价快照；</p>
            <p>· 租约生成后可在「租住契约」查看，并在管理方发起协议后在线确认租赁协议。</p>
          </template>
          <template v-else>
            <p>· 申请提交后进入「待审核」，管理方审核通过才会生成租约；</p>
            <p>· 审核期间可在「租住与房源 → 入住申请」查看进度或撤回申请，同一房屋重复申请会被拒绝；</p>
            <p>· 租期与租金以审核通过后生成的租约为准，可在「租住契约」中查看与续租。</p>
          </template>
        </div>

        <div class="form-actions">
          <el-button @click="router.back()">返回</el-button>
          <el-button
            type="primary"
            :disabled="!rentable"
            :loading="submitting"
            @click="handleSubmit"
          >
            提交申请
          </el-button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.apply-page {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
  min-height: 320px;
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-sm);
}

.breadcrumb a {
  color: var(--color-text-secondary);
}

.breadcrumb a:hover {
  color: var(--color-primary);
}

.breadcrumb-sep {
  color: var(--color-text-disabled);
}

.breadcrumb-current {
  color: var(--color-text-secondary);
}

.page-head h1 {
  margin: 0;
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

/* 左右布局：窄屏堆叠 */
.apply-layout {
  display: grid;
  grid-template-columns: minmax(280px, 4fr) 6fr;
  gap: var(--spacing-md);
  align-items: start;
}

.summary-cover {
  aspect-ratio: 3 / 2;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-bg-hover);
}

.summary-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.summary-title {
  margin: var(--spacing-md) 0 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.summary-loc {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.summary-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-sm) var(--spacing-md);
  margin: var(--spacing-md) 0 0;
}

.summary-meta dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.summary-meta dd {
  margin: 2px 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.summary-warn {
  margin: var(--spacing-md) 0 0;
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  background: rgba(245, 158, 11, 0.12);
  color: var(--color-warning);
  font-size: var(--font-size-xs);
}

.form-title {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
}

.relation-group {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--spacing-xs);
}

.relation-hint {
  margin-left: var(--spacing-xs);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.form-tip {
  margin: var(--spacing-md) 0 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  line-height: var(--line-height-relaxed);
}

.form-tip p {
  margin: 0;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-md);
}

/* 成功态 */
.result-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  align-items: flex-start;
  padding: var(--spacing-xl);
}

.result-card h2 {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
}

.result-card p {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.result-actions {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-sm);
}

.mono {
  font-family: var(--font-family-mono);
}

@media (max-width: 991px) {
  .apply-layout {
    grid-template-columns: 1fr;
  }
}
</style>
