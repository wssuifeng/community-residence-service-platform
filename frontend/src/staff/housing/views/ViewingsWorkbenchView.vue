<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatusTag from '@/common/web/components/StatusTag.vue'
import Pagination from '@/common/web/components/Pagination.vue'
import ConversationChat from '@/common/conversation/components/ConversationChat.vue'
import { getConversation } from '@/common/conversation/api/conversation'
import {
  listViewingAppointments,
  completeViewingAppointment
} from '@/common/housing/api/housing'
import type { IViewingAppointment, ViewingAppointmentStatus } from '@/common/housing/types/housing'
import { viewingAppointmentStatusLabels } from '@/common/housing/types/housing'
import { formatDateTime } from '@/common/web/utils/date'

/**
 * 我的带看工作台（R59，需求规格 v1.3）：服务人员视角被分配为带看人的看房预约。
 * 列表口径（assigned to me）由后端本批实现，前端直接调用 listViewingAppointments；
 * 主从布局：点击行右侧展示预约详情 + R63 看房群聊（服务人员作为 assigned_staff
 * 入群后复用预约 conversationId，会话组件内部处理 WS 推送与轮询兜底）。
 * 操作对齐 R55 状态机：带看人可对已预约的看房登记完成；确认/违约/冻结归属管理方，不展示。
 */

const tagTypeMap: Record<ViewingAppointmentStatus, 'pending' | 'processing' | 'completed' | 'rejected' | 'canceled'> = {
  TO_CONFIRM: 'pending',
  RESERVED: 'processing',
  COMPLETED: 'completed',
  CANCELLED: 'canceled',
  VIOLATED: 'rejected'
}

const query = ref({ page: 1, size: 10 })
const total = ref(0)
const records = ref<IViewingAppointment[]>([])
const loading = ref(false)
const selectedId = ref<number | null>(null)

const selected = ref<IViewingAppointment | null>(null)
const selectedConversationClosed = ref(false)

function rowClassName({ row }: { row: IViewingAppointment }): string {
  return row.id === selectedId.value ? 'is-selected' : ''
}

async function selectRow(row: IViewingAppointment): Promise<void> {
  selectedId.value = row.id
  selected.value = row
  selectedConversationClosed.value = false
  if (row.conversationId == null) return
  try {
    const conversation = await getConversation(row.conversationId)
    if (selected.value?.id === row.id) {
      selectedConversationClosed.value = conversation.status === 'CLOSED'
    }
  } catch (error) {
    /* 会话详情失败时仍让组件自行按参与者权限降级，避免把预约详情一并变成空白 */
    console.error('加载看房群聊状态失败', error)
  }
}

async function loadList(): Promise<void> {
  loading.value = true
  try {
    const result = await listViewingAppointments({ page: query.value.page, size: query.value.size })
    records.value = result.records
    total.value = result.total
    /* 动作后重载：按 id 重新对齐选中行（行内状态已变化），不在当前页则清空选中 */
    const current = selectedId.value === null ? null : result.records.find((row) => row.id === selectedId.value)
    selected.value = current ?? null
    if (!current) {
      selectedId.value = null
      selectedConversationClosed.value = false
    } else if (current.conversationId != null) {
      void selectRow(current)
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '带看预约列表加载失败')
  } finally {
    loading.value = false
  }
}

/* ------------------------------ 带看登记操作（R55 状态机：RESERVED → COMPLETED） ------------------------------ */

/** 登记完成；后端 ReservationActionDTO 要求 reason 必填，对齐管理端既有调用 */
async function handleComplete(row: IViewingAppointment): Promise<void> {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认「${row.housingTitle}」${row.appointmentDate} 的看房已完成？`,
      '完成看房',
      {
        confirmButtonText: '确认完成',
        cancelButtonText: '取消',
        inputPlaceholder: '完成备注（必填）',
        inputValidator: (input: string) => (input.trim().length > 0 ? true : '请填写完成备注'),
        type: 'success'
      }
    )
    await completeViewingAppointment(row.id, { reason: value.trim() })
    ElMessage.success('已标记完成')
    loadList()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : '完成操作失败')
  }
}



onMounted(loadList)
</script>

<template>
  <section class="viewings-workbench">
    <header class="page-header">
      <h1 class="page-title">我的带看</h1>
      <p class="page-sub">我被分配为带看人的看房预约，可查看详情、与预约居民沟通并登记结果</p>
    </header>

    <div class="workbench-body">
      <div class="list-pane" v-loading="loading">
        <el-table
          :data="records"
          :row-class-name="rowClassName"
          stripe
          empty-text="暂无被分配的带看预约"
          @row-click="selectRow"
        >
          <el-table-column prop="housingTitle" label="房源标题" min-width="160" show-overflow-tooltip />
          <el-table-column prop="appointmentDate" label="预约日期" width="110" />
          <el-table-column label="时段" width="120">
            <template #default="{ row }">{{ row.startTime }} ~ {{ row.endTime }}</template>
          </el-table-column>
          <el-table-column prop="visitorName" label="预约人" min-width="90" />
          <el-table-column prop="contactPhone" label="联系电话" min-width="120" />
          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <StatusTag
                :label="viewingAppointmentStatusLabels[row.status as ViewingAppointmentStatus]"
                :type="tagTypeMap[row.status as ViewingAppointmentStatus]"
              />
            </template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="140">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>

        <Pagination
          v-model:page="query.page"
          v-model:size="query.size"
          :total="total"
          @update:page="loadList"
          @update:size="loadList"
        />
      </div>

      <aside class="detail-pane">
        <template v-if="selected">
          <div class="detail-card">
            <h2 class="detail-title">{{ selected.housingTitle }}</h2>
            <dl class="detail-info">
              <div class="info-row">
                <dt>看房时间</dt>
                <dd>{{ selected.appointmentDate }} {{ selected.startTime }} ~ {{ selected.endTime }}</dd>
              </div>
              <div class="info-row">
                <dt>预约人</dt>
                <dd>{{ selected.visitorName }}</dd>
              </div>
              <div class="info-row">
                <dt>联系电话</dt>
                <dd>{{ selected.contactPhone }}</dd>
              </div>
              <div class="info-row">
                <dt>状态</dt>
                <dd>
                  <StatusTag
                    :label="viewingAppointmentStatusLabels[selected.status as ViewingAppointmentStatus]"
                    :type="tagTypeMap[selected.status as ViewingAppointmentStatus]"
                  />
                </dd>
              </div>
              <div class="info-row">
                <dt>提交时间</dt>
                <dd>{{ formatDateTime(selected.createdAt) }}</dd>
              </div>
              <div v-if="selected.remark" class="info-row">
                <dt>备注</dt>
                <dd>{{ selected.remark }}</dd>
              </div>
            </dl>
            <div v-if="selected.status === 'RESERVED'" class="detail-actions">
              <button type="button" class="action-btn primary" @click="handleComplete(selected)">登记完成</button>
            </div>
          </div>

          <!-- R63：服务人员与居民/管理员共用预约关联的多方看房群聊。 -->
          <ConversationChat
            v-if="selected.conversationId != null"
            :key="selected.conversationId"
            :conversation-id="selected.conversationId"
            :closed="selectedConversationClosed"
            title="看房群聊"
          />
          <div v-else class="chat-placeholder">该预约暂无可用沟通群</div>
        </template>
        <el-empty v-else class="detail-empty" description="点击左侧预约查看详情与带看沟通" :image-size="80" />
      </aside>
    </div>
  </section>
</template>

<style scoped>
.page-header {
  margin-bottom: var(--spacing-md);
}

.page-title {
  margin: 0;
  font-size: var(--font-size-xxl);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.page-sub {
  margin: var(--spacing-xs) 0 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.workbench-body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(320px, 380px);
  gap: var(--spacing-lg);
  align-items: start;
}

.list-pane {
  background: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-md);
  min-width: 0;
}

/* 选中行高亮：主从联动视觉锚点 */
.list-pane :deep(.el-table .is-selected > td) {
  background-color: var(--color-primary-bg) !important;
}

.list-pane :deep(.el-table__row) {
  cursor: pointer;
}

.detail-pane {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
  min-width: 0;
}

.detail-card {
  background: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-lg);
}

.detail-title {
  margin: 0 0 var(--spacing-md);
  font-size: var(--font-size-lg);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.detail-info {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin: 0;
}

.info-row {
  display: flex;
  gap: var(--spacing-md);
  font-size: var(--font-size-sm);
  line-height: var(--line-height-normal);
}

.info-row dt {
  flex-shrink: 0;
  width: 4.5em;
  color: var(--color-text-disabled);
}

.info-row dd {
  margin: 0;
  color: var(--color-text-primary);
  word-break: break-word;
}

.detail-actions {
  display: flex;
  gap: var(--spacing-sm);
  margin-top: var(--spacing-lg);
}

.action-btn {
  flex: 1;
  padding: 10px 0;
  border: none;
  border-radius: var(--radius-md);
  font-family: inherit;
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
  transition: opacity 0.2s ease;
}

.action-btn:hover {
  opacity: 0.85;
}

.action-btn.primary {
  background-color: var(--color-primary);
  color: #fff;
}

.action-btn.danger {
  background-color: var(--status-rejected);
  color: #fff;
}

.chat-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  padding: var(--spacing-lg);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.detail-empty {
  background: #fff;
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: var(--spacing-xxl) 0;
}

@media (max-width: 1023px) {
  .workbench-body {
    grid-template-columns: 1fr;
  }
}
</style>
