<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import StatusTag from '@/common/web/components/StatusTag.vue'
import { getHouseStatusHistory } from '@/common/community/api/community'
import { houseStatusLabels, type IHouseStatusHistory } from '@/common/community/types/community'
import { getHouseResidentList, getResident } from '@/common/resident/api/resident'
import { relationTypeLabels, residenceRelationStatusLabels, type IHouseResident, type IResident } from '@/common/resident/types/resident'
import { formatDateTime } from '@/common/web/utils/date'
import type { HouseEntry } from '@/admin/community/types/structure'
import { houseStatusTagType } from '@/admin/community/utils/structurePresentation'
const props = defineProps<{ entry: HouseEntry | null }>()
const visible = defineModel<boolean>({ required:true })
const emit = defineEmits<{ album: []; edit: []; status: []; remove: [] }>()
const houseInfoEntry = computed(() => props.entry)
const houseHistory = ref<IHouseStatusHistory[]>([])

const houseHistoryLoading = ref(false)

let houseHistorySeq = 0

async function loadHouseHistory(houseId: number): Promise<void> {
  const seq = ++houseHistorySeq
  houseHistoryLoading.value = true
  houseHistory.value = []
  try {
    const result = await getHouseStatusHistory(houseId, { page: 1, size: 20 })
    if (seq !== houseHistorySeq) return
    houseHistory.value = result.records
  } catch {
    if (seq === houseHistorySeq) houseHistory.value = []
  } finally {
    if (seq === houseHistorySeq) houseHistoryLoading.value = false
  }
}

const houseResidents = ref<IHouseResident[]>([])

const houseResidentProfiles = ref<Record<number, IResident>>({})

const houseResidentsLoading = ref(false)

const houseResidentsError = ref('')

let houseResidentsSeq = 0

const RELATION_ORDER: Record<string, number> = { TENANT: 0, OWNER: 1, FAMILY: 2 }

const sortedHouseResidents = computed(() =>
  [...houseResidents.value].sort(
    (a, b) =>
      (a.status === 'ACTIVE' ? 0 : 1) - (b.status === 'ACTIVE' ? 0 : 1) ||
      (RELATION_ORDER[a.relationType ?? ''] ?? 9) - (RELATION_ORDER[b.relationType ?? ''] ?? 9) ||
      a.residentId - b.residentId
  )
)

const activeResidentCount = computed(
  () => houseResidents.value.filter((item) => item.status === 'ACTIVE').length
)

function residentTypeLabel(type: IHouseResident['relationType']): string {
  return type ? relationTypeLabels[type] : '居住人'
}

async function loadHouseResidents(houseId: number): Promise<void> {
  const seq = ++houseResidentsSeq
  houseResidentsLoading.value = true
  houseResidentsError.value = ''
  houseResidents.value = []
  houseResidentProfiles.value = {}
  try {
    const result = await getHouseResidentList(houseId, { page: 1, size: 20 })
    if (seq !== houseResidentsSeq) return
    houseResidents.value = result.records
    const profiles = await Promise.all(
      result.records.map(async (relation) => {
        try {
          return [relation.residentId, await getResident(relation.residentId)] as const
        } catch {
          return null
        }
      })
    )
    if (seq !== houseResidentsSeq) return
    const map: Record<number, IResident> = {}
    profiles.forEach((item) => {
      if (item) map[item[0]] = item[1]
    })
    houseResidentProfiles.value = map
  } catch (error) {
    if (seq !== houseResidentsSeq) return
    houseResidentsError.value = error instanceof Error ? error.message : '加载在住人员失败'
  } finally {
    if (seq === houseResidentsSeq) houseResidentsLoading.value = false
  }
}
watch([visible, () => props.entry?.house.id], () => { houseHistorySeq++; houseResidentsSeq++; if (visible.value && props.entry) { void loadHouseHistory(props.entry.house.id); void loadHouseResidents(props.entry.house.id) } }, { immediate:true })
onBeforeUnmount(() => { houseHistorySeq++; houseResidentsSeq++ })
</script>
<template>
<el-dialog v-model="visible" title="房屋信息" width="640px">
      <el-button v-if="houseInfoEntry" @click="emit('album')">管理房屋图片</el-button>
      <el-descriptions v-if="houseInfoEntry" :column="2" border>
        <el-descriptions-item label="门牌号">{{ houseInfoEntry.house.houseNumber }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag
            :label="houseStatusLabels[houseInfoEntry.house.status]"
            :type="houseStatusTagType(houseInfoEntry.house.status)"
          />
        </el-descriptions-item>
        <el-descriptions-item label="楼栋">{{ houseInfoEntry.buildingName }}</el-descriptions-item>
        <el-descriptions-item label="单元">{{ houseInfoEntry.unitName }}</el-descriptions-item>
        <el-descriptions-item label="所在楼层">{{ houseInfoEntry.house.floor }} 层</el-descriptions-item>
        <el-descriptions-item label="建筑面积">
          {{ houseInfoEntry.house.area != null ? `${houseInfoEntry.house.area} ㎡` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="户型">{{ houseInfoEntry.house.layout || '-' }}</el-descriptions-item>
        <el-descriptions-item label="朝向">{{ houseInfoEntry.house.orientation || '-' }}</el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">
          {{ houseInfoEntry.house.description || '-' }}
        </el-descriptions-item>
      </el-descriptions>

      <!-- 在住人员 / 租客账号：关系（谁住着）+ 居民档案（账号、账号状态） -->
      <div v-loading="houseResidentsLoading" class="info-residents">
        <h4 class="info-residents-title">
          在住人员 / 租客账号
          <span class="info-residents-count">
            居住中 {{ activeResidentCount }} / 记录 {{ houseResidents.length }}
          </span>
        </h4>

        <p v-if="houseResidentsError" class="info-residents-error">
          {{ houseResidentsError }}
        </p>

        <div v-else-if="sortedHouseResidents.length > 0" class="resident-table">
          <div class="resident-row is-head" aria-hidden="true">
            <span>姓名</span>
            <span>登录账号</span>
            <span>身份</span>
            <span>手机号</span>
            <span>入住 / 迁出</span>
            <span>状态</span>
          </div>
          <div
            v-for="item in sortedHouseResidents"
            :key="item.id"
            class="resident-row"
            :class="{ 'is-moved-out': item.status !== 'ACTIVE' }"
          >
            <span class="resident-name" :title="item.residentName">
              {{ item.residentName || `居民 #${item.residentId}` }}
            </span>
            <span class="resident-account" :title="houseResidentProfiles[item.residentId]?.username ?? '-'">
              {{ houseResidentProfiles[item.residentId]?.username ?? '-' }}
            </span>
            <span>
              <StatusTag
                :label="residentTypeLabel(item.relationType)"
                :type="item.relationType === 'TENANT' ? 'processing' : 'info'"
              />
            </span>
            <span class="resident-phone">
              {{ item.residentPhone || houseResidentProfiles[item.residentId]?.phone || '-' }}
            </span>
            <span class="resident-date">
              {{ item.moveInDate || '-' }}
              <template v-if="item.moveOutDate"> → {{ item.moveOutDate }}</template>
            </span>
            <span>
              <StatusTag
                :label="residenceRelationStatusLabels[item.status]"
                :type="item.status === 'ACTIVE' ? 'completed' : 'canceled'"
              />
            </span>
          </div>
        </div>

        <p v-else-if="!houseResidentsLoading" class="info-residents-empty">
          该房屋暂无居住关系登记（业主 / 租客 / 家属均未登记）。
        </p>
      </div>

      <div v-loading="houseHistoryLoading" class="info-history">
        <h4 class="info-history-title">状态变更历史</h4>
        <ul v-if="houseHistory.length > 0" class="info-history-list">
          <li v-for="item in houseHistory" :key="item.id" class="info-history-item">
            <span class="info-history-status">
              {{ item.oldStatus ? houseStatusLabels[item.oldStatus] : '初始' }}
              →
              {{ houseStatusLabels[item.newStatus] }}
            </span>
            <span class="info-history-meta">
              {{ formatDateTime(item.createdAt) }} · {{ item.operatorName }}
              <template v-if="item.remark"> · {{ item.remark }}</template>
            </span>
          </li>
        </ul>
        <p v-else-if="!houseHistoryLoading" class="info-history-empty">暂无状态变更记录</p>
      </div>

      <template #footer>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="primary" plain @click="emit('edit')">
          编辑
        </el-button>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="warning" plain @click="emit('status')">
          状态变更
        </el-button>
        <el-button v-permission="['ADMIN', 'SUPER_ADMIN']" type="danger" plain @click="emit('remove')">
          删除
        </el-button>
        <el-button @click="visible = false">关闭</el-button>
      </template>
    </el-dialog>
</template>
<style scoped>
.info-residents {
  margin-top: var(--spacing-md);
  min-height: 48px;
}
.info-residents-title {
  display: flex;
  align-items: baseline;
  gap: var(--spacing-sm);
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}
.info-residents-count {
  font-family: var(--font-family-mono);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-normal);
  color: var(--color-text-secondary);
}
.info-residents-empty {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
.info-residents-error {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-danger);
}
.info-history {
  margin-top: var(--spacing-md);
  min-height: 48px;
}
.info-history-title {
  margin: 0 0 var(--spacing-sm);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}
.info-history-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  margin: 0;
  padding: 0;
  max-height: 200px;
  overflow: auto;
  list-style: none;
}
.info-history-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-left: var(--spacing-sm);
  border-left: 2px solid var(--color-border-light, var(--color-border));
}
.info-history-status {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}
.info-history-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
.info-history-empty {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}
</style>
