<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getHousingDetail } from '@/api/housing'
import { createResidenceApplication } from '@/api/resident'
import type { IHousing } from '@/types/modules/housing'
import { housingRentTypeLabels } from '@/types/modules/housing'
import type { RelationType } from '@/types/modules/resident'
import { relationTypeLabels } from '@/types/modules/resident'

/**
 * 申请租住（R62 补：居民端此前缺少直接的租住申请入口）。
 * 流程：选择身份（租客 / 业主 / 家属）→ 提交入住申请 → 管理方审核通过后
 * 生成居住关系与租约（租期从审核通过起算）。本页只发起申请，不即时入驻。
 */

const route = useRoute()
const router = useRouter()

const housingId = Number(route.params.id)
const housing = ref<IHousing | null>(null)
const loading = ref(true)
const submitting = ref(false)
const submitted = ref(false)

const form = reactive({
  relationType: 'TENANT' as RelationType,
  remark: ''
})

const relationOptions: Array<{ value: RelationType; label: string; hint: string }> = [
  { value: 'TENANT', label: relationTypeLabels.TENANT, hint: '租赁入住，审核通过后生成租约' },
  { value: 'OWNER', label: relationTypeLabels.OWNER, hint: '房屋产权人登记（自住）' },
  { value: 'FAMILY', label: relationTypeLabels.FAMILY, hint: '作为在住亲属登记' }
]

/** 仅可租房源可申请租住（与后端房屋状态校验同口径） */
const rentable = computed(() => housing.value?.status === 'AVAILABLE')

async function loadHousing(): Promise<void> {
  loading.value = true
  try {
    housing.value = await getHousingDetail(housingId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载房源失败')
  } finally {
    loading.value = false
  }
}

async function handleSubmit(): Promise<void> {
  if (!housing.value || submitting.value) return
  submitting.value = true
  try {
    await createResidenceApplication({
      houseId: housing.value.houseId,
      relationType: form.relationType,
      remark: form.remark.trim() === '' ? undefined : form.remark.trim()
    })
    submitted.value = true
    ElMessage.success('申请已提交，等待管理方审核')
  } catch (error) {
    /* 后端业务错误（已在住/存在在审申请/房屋不可租）直接展示其 message */
    ElMessage.error(error instanceof Error ? error.message : '申请提交失败')
  } finally {
    submitting.value = false
  }
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
      <p class="page-head-sub">提交后由社区管理方审核，审核通过即生成居住关系与租约</p>
    </header>

    <!-- 提交成功态：明确后续路径 -->
    <div v-if="submitted" class="result-card card">
      <h2>申请已提交</h2>
      <p>管理方审核通过后，您将收到通知，并可在「我的租约」中查看租期与租金。</p>
      <div class="result-actions">
        <el-button type="primary" @click="router.push('/resident/housings')">继续看房源</el-button>
        <el-button @click="router.push('/resident/notifications')">查看消息</el-button>
      </div>
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
          <p>· 申请提交后进入「待审核」，管理方审核通过才会生成租约；</p>
          <p>· 审核期间可在「我的消息」中查看进度，同一房屋重复申请会被拒绝；</p>
          <p>· 租期与租金以审核通过后生成的租约为准，可在「我的租约」中查看与续租。</p>
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
