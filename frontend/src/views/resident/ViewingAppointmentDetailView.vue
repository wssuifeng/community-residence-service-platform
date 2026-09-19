<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/components/common/StatusTag.vue'
import AppointmentChat from '@/components/business/AppointmentChat.vue'
import { getViewingAppointmentDetail, cancelViewingAppointment } from '@/api/housing'
import type { IViewingAppointment, ViewingAppointmentStatus } from '@/types/modules/housing'
import { viewingAppointmentStatusLabels } from '@/types/modules/housing'
import { formatDateTime } from '@/utils/date'

/**
 * 看房预约详情（R59，v1.3）：预约信息卡 + 带看沟通会话 + 取消入口。
 * 会话直接复用 AppointmentChat（WS 推送 + 轮询兜底 + 403 只读降级，组件内自处理，
 * 未分配带看人时降级只读提示「无会话对象」）；可取消口径与原列表页一致（待确认/已预约）。
 */

const route = useRoute()
const router = useRouter()

const appointmentId = Number(route.params.id)
/** 非法直达（旧书签/坏链）→ 回我的预约看房 tab */
const invalidEntry = !Number.isInteger(appointmentId) || appointmentId <= 0

const detail = ref<IViewingAppointment | null>(null)
const loading = ref(true)
const cancelling = ref(false)

const tagTypeMap: Record<ViewingAppointmentStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  TO_CONFIRM: 'pending',
  RESERVED: 'processing',
  COMPLETED: 'completed',
  CANCELLED: 'canceled',
  VIOLATED: 'rejected'
}

/** 可取消口径与原独立列表页保持一致：TO_CONFIRM / RESERVED（后端状态机兜底） */
const canCancel = computed(
  () => detail.value?.status === 'TO_CONFIRM' || detail.value?.status === 'RESERVED'
)

async function loadDetail(): Promise<void> {
  loading.value = true
  try {
    detail.value = await getViewingAppointmentDetail(appointmentId)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加载看房预约详情失败')
  } finally {
    loading.value = false
  }
}

/** 取消需填写原因（ViewingAppointmentReasonDTO.reason 必填）；成功回看房 tab 便于继续查看 */
async function handleCancel(): Promise<void> {
  if (!detail.value || cancelling.value) return
  try {
    const { value } = await ElMessageBox.prompt('请填写取消原因', `取消看房预约 #${detail.value.id}`, {
      confirmButtonText: '确认取消预约',
      cancelButtonText: '再想想',
      inputPlaceholder: '例如：时间冲突，改期再看',
      inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写取消原因'),
      type: 'warning'
    })
    cancelling.value = true
    await cancelViewingAppointment(appointmentId, { reason: value.trim() })
    ElMessage.success('看房预约已取消')
    router.push('/resident/reservations?tab=viewing')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '取消看房预约失败')
  } finally {
    cancelling.value = false
  }
}

onMounted(() => {
  if (invalidEntry) {
    router.replace('/resident/reservations?tab=viewing')
    return
  }
  void loadDetail()
})
</script>

<template>
  <section v-loading="loading" class="viewing-detail">
    <nav class="breadcrumb">
      <router-link to="/resident/reservations?tab=viewing">我的预约</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">看房预约详情</span>
    </nav>

    <template v-if="detail">
      <div class="detail-layout">
        <!-- 左：预约信息卡 -->
        <div class="info-card card">
          <div class="info-head">
            <h1 class="info-title">{{ detail.housingTitle }}</h1>
            <StatusTag
              :label="viewingAppointmentStatusLabels[detail.status]"
              :type="tagTypeMap[detail.status]"
            />
          </div>

          <dl class="info-grid">
            <div class="info-item">
              <dt>预约日期</dt>
              <dd class="mono">{{ detail.appointmentDate }}</dd>
            </div>
            <div class="info-item">
              <dt>看房时段</dt>
              <dd class="mono">{{ detail.startTime.slice(0, 5) }} ~ {{ detail.endTime.slice(0, 5) }}</dd>
            </div>
            <div class="info-item">
              <dt>带看人</dt>
              <dd>{{ detail.assigneeName || '待分配' }}</dd>
            </div>
            <div class="info-item">
              <dt>联系人</dt>
              <dd>{{ detail.visitorName }}</dd>
            </div>
            <div class="info-item">
              <dt>联系电话</dt>
              <dd class="mono">{{ detail.contactPhone }}</dd>
            </div>
            <div class="info-item">
              <dt>提交时间</dt>
              <dd class="mono">{{ formatDateTime(detail.createdAt) }}</dd>
            </div>
          </dl>

          <dl class="info-remark">
            <dt>备注</dt>
            <dd>{{ detail.remark?.trim() || '无' }}</dd>
          </dl>

          <div v-if="canCancel" class="info-actions">
            <el-button type="danger" plain round :loading="cancelling" @click="handleCancel">
              取消预约
            </el-button>
          </div>
        </div>

        <!-- 右：带看沟通会话（R59；组件内自处理 WS/轮询/403 只读，未分配时为只读提示） -->
        <div class="chat-wrap">
          <AppointmentChat :appointment-id="appointmentId" />
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.viewing-detail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
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

/* 左信息卡 + 右会话：宽屏并排，窄屏堆叠 */
.detail-layout {
  display: grid;
  grid-template-columns: minmax(320px, 5fr) 7fr;
  gap: var(--spacing-md);
  align-items: start;
}

.card {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
}

.info-card {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.info-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.info-title {
  margin: 0;
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
}

.info-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--spacing-md);
  margin: 0;
}

.info-item dt,
.info-remark dt {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  margin-bottom: var(--spacing-xs);
}

.info-item dd,
.info-remark dd {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
}

.info-remark dt {
  margin-bottom: var(--spacing-xs);
}

.info-remark {
  padding-top: var(--spacing-md);
  border-top: 1px dashed var(--color-border);
}

.info-remark dd {
  line-height: var(--line-height-relaxed);
  white-space: pre-wrap;
  word-break: break-word;
}

.mono {
  font-family: var(--font-family-mono);
}

.info-actions {
  padding-top: var(--spacing-sm);
  border-top: 1px dashed var(--color-border);
  display: flex;
  justify-content: flex-end;
}

.chat-wrap {
  min-width: 0;
}

@media (max-width: 991px) {
  .detail-layout {
    grid-template-columns: 1fr;
  }
}
</style>
