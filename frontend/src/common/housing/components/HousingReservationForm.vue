<script setup lang="ts">
import { useHousingReservation } from "@/common/housing/composables/useHousingReservation"
const props = defineProps<{ housingId?: number; conversationId?: number }>()
const emit = defineEmits<{ created: [appointmentId?: number]; invalid: [] }>()
const { housing, profile, STEPS, step, canGoto, gotoStep, WEEKDAYS, todayISO, viewYear, viewMonth, selectedDate, monthLoading, isCurrentMonth, calendarCells, dayState, monthHasOpen, shiftMonth, backToCurrentMonth, pickDate, MAX_CONTINUOUS_MINUTES, daySlots, dayLoading, selectedSlots, slotFull, slotLabel, allDaySlotsFull, mergedStart, mergedEnd, mergedMinutes, isSelected, toggleSlot, goNextFromStep2, form, submitting, weekdayText, handleSubmit } = useHousingReservation(props, id => emit("created", id), () => emit("invalid"))
</script>
<template>
  <section class="housing-reserve">
    <nav class="breadcrumb">
      <router-link to="/resident/housings">房源列表</router-link>
      <template v-if="housing">
        <span class="breadcrumb-sep">/</span>
        <router-link :to="`/resident/housings/${housing.id}`">{{ housing.title }}</router-link>
      </template>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-current">预约看房</span>
    </nav>

    <header class="page-head">
      <h1>预约看房</h1>
      <p class="page-head-sub">
        <template v-if="housing">{{ housing.communityName }} · {{ housing.title }}</template>
        <template v-else>正在加载房源信息…</template>
      </p>
    </header>

    <div class="reserve-layout">
      <!-- 左：步骤轨（已完成可点回跳，未解锁置灰） -->
      <aside class="step-rail">
        <button
          v-for="item in STEPS"
          :key="item.step"
          type="button"
          class="step-item"
          :class="{
            'is-current': step === item.step,
            'is-done': step > item.step,
            'is-locked': !canGoto(item.step) && step < item.step
          }"
          :disabled="!canGoto(item.step)"
          @click="gotoStep(item.step)"
        >
          <span class="step-index">
            <svg v-if="step > item.step" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
              <path d="M20 6 9 17l-5-5" />
            </svg>
            <template v-else>{{ item.step }}</template>
          </span>
          <span class="step-text">
            <span class="step-title">{{ item.title }}</span>
            <span class="step-desc">{{ item.desc }}</span>
          </span>
        </button>
      </aside>

      <!-- 右：单步内容卡 -->
      <div class="step-panel">
        <!-- 第一步：月历 -->
        <div v-show="step === 1" class="panel-body">
          <div class="calendar-head">
            <span class="calendar-title">{{ viewYear }}年 {{ viewMonth + 1 }}月</span>
            <div class="calendar-nav">
              <button type="button" aria-label="上月" @click="shiftMonth(-1)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
              </button>
              <button v-if="!isCurrentMonth" type="button" class="calendar-back" @click="backToCurrentMonth">
                回本月
              </button>
              <button type="button" aria-label="下月" @click="shiftMonth(1)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
              </button>
            </div>
          </div>

          <div class="calendar-weekdays">
            <span v-for="day in WEEKDAYS" :key="day">{{ day }}</span>
          </div>

          <div v-loading="monthLoading" class="calendar-cells">
            <button
              v-for="(cell, index) in calendarCells"
              :key="index"
              type="button"
              class="calendar-cell"
              :class="{
                'is-blank': cell === null,
                'is-today': cell?.iso === todayISO,
                'is-selected': cell !== null && cell.iso === selectedDate,
                'is-off': cell !== null && dayState(cell.iso) !== 'open'
              }"
              :disabled="cell === null || dayState(cell.iso) !== 'open'"
              @click="cell && pickDate(cell.iso)"
            >
              <template v-if="cell">
                <span class="cell-day">{{ cell.day }}</span>
                <span v-if="dayState(cell.iso) === 'open'" class="cell-dot" aria-hidden="true"></span>
                <span v-else-if="dayState(cell.iso) === 'full'" class="cell-full">满</span>
              </template>
            </button>
          </div>

          <p v-if="!monthLoading && !monthHasOpen" class="calendar-empty-hint">
            本月暂无可约时段，可点击「下月」翻看更远日期
          </p>

          <p class="calendar-legend">
            <span class="legend-item"><span class="legend-dot"></span>有可约时段</span>
            <span class="legend-item"><span class="legend-full">满</span>当日时段已约满</span>
            <span class="legend-item"><span class="legend-off"></span>无开放时段 / 已过期</span>
          </p>
        </div>

        <!-- 第二步：时段卡连续多选（R60） -->
        <div v-show="step === 2" class="panel-body">
          <h2 class="panel-title">选择 {{ selectedDate }}（{{ weekdayText }}）的可约时段</h2>
          <p class="panel-hint">
            可多选相邻连续时段合并为一次看房（最长 {{ MAX_CONTINUOUS_MINUTES }} 分钟），不支持跨段选择
          </p>
          <div v-loading="dayLoading" class="slot-grid">
            <button
              v-for="slot in daySlots"
              :key="slot.timeslotId"
              type="button"
              class="slot-card"
              :class="{
                'is-selected': isSelected(slot),
                'is-full': slotFull(slot)
              }"
              :disabled="slotFull(slot)"
              @click="toggleSlot(slot)"
            >
              <span class="slot-card-time">{{ slotLabel(slot) }}</span>
              <span class="slot-card-quota">
                {{ slotFull(slot) ? '已约满' : `余 ${slot.maxBookings - slot.currentBookings}` }}
              </span>
            </button>
          </div>
          <p v-if="!dayLoading && daySlots.length === 0" class="slot-hint">当日暂无开放时段</p>
          <p v-else-if="allDaySlotsFull" class="slot-hint">当日时段已全部约满，请换一天或稍后再试</p>
          <p v-else-if="selectedSlots.length > 0" class="slot-hint is-active">
            已选 {{ selectedSlots.length }} 个时段，合计 {{ mergedMinutes }} 分钟（{{ mergedStart?.slice(0, 5) }} ~ {{ mergedEnd?.slice(0, 5) }}）
          </p>
          <div class="step2-actions">
            <button type="button" class="ghost-btn" @click="gotoStep(1)">重新选日期</button>
            <el-button type="primary" round @click="goNextFromStep2">下一步</el-button>
          </div>
        </div>

        <!-- 第三步：摘要确认 -->
        <div v-show="step === 3" class="panel-body">
          <h2 class="panel-title">确认看房预约</h2>
          <dl class="summary">
            <div class="summary-row">
              <dt>看房房源</dt>
              <dd>{{ housing ? `${housing.communityName} · ${housing.title}` : '—' }}</dd>
            </div>
            <div class="summary-row">
              <dt>预约日期</dt>
              <dd>{{ selectedDate }} {{ selectedDate ? `（${weekdayText}）` : '' }}</dd>
            </div>
            <div class="summary-row">
              <dt>看房时段</dt>
              <dd>
                <template v-if="mergedStart && mergedEnd">
                  {{ mergedStart.slice(0, 5) }} ~ {{ mergedEnd.slice(0, 5) }}（{{ mergedMinutes }} 分钟）
                </template>
                <template v-else>—</template>
              </dd>
            </div>
            <div class="summary-row">
              <dt>联系人</dt>
              <dd>{{ profile?.realName || '当前登录居民' }}</dd>
            </div>
          </dl>

          <div class="supplement">
            <el-form label-width="76px" class="supplement-form" @submit.prevent>
              <el-form-item label="联系电话">
                <el-input
                  v-model="form.contactPhone"
                  placeholder="用于管理员联系（选填）"
                  maxlength="11"
                  style="width: 240px"
                />
              </el-form-item>
              <el-form-item label="备注">
                <el-input
                  v-model="form.remark"
                  type="textarea"
                  :rows="2"
                  placeholder="其他需要说明的事项（选填）"
                  maxlength="500"
                  show-word-limit
                />
              </el-form-item>
            </el-form>
          </div>

          <div class="submit-row">
            <el-button
              type="primary"
              round
              size="large"
              :loading="submitting"
              :disabled="submitting"
              @click="handleSubmit"
            >
              {{ submitting ? '提交中…' : '提交预约' }}
            </el-button>
            <el-button round size="large" :disabled="submitting" @click="gotoStep(2)">
              上一步
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.housing-reserve {
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
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 24em;
}

.page-head h1 {
  font-size: var(--font-size-xl);
  font-weight: var(--font-weight-bold);
}

.page-head-sub {
  margin-top: var(--spacing-xs);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
}

/* 左步骤轨 + 右内容卡 */
.reserve-layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: var(--spacing-md);
  align-items: start;
}

.step-rail {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-md);
  position: sticky;
  top: var(--spacing-md);
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm);
  border: none;
  border-radius: var(--radius-md);
  background: none;
  text-align: left;
  cursor: pointer;
}

.step-item:disabled {
  cursor: not-allowed;
}

.step-item.is-current {
  background: var(--color-primary-bg);
}

.step-index {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-circle);
  border: 1.5px solid var(--color-border);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
}

.step-item.is-current .step-index {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: #fff;
}

.step-item.is-done .step-index {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.step-item.is-done .step-index svg {
  width: 13px;
  height: 13px;
}

.step-item.is-locked .step-index {
  opacity: 0.55;
}

.step-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.step-title {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.step-item.is-locked .step-title {
  color: var(--color-text-disabled);
}

.step-desc {
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
}

.step-panel {
  background: #fff;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--spacing-lg);
  box-shadow: var(--shadow-sm);
  min-height: 380px;
}

.panel-body {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-md);
}

.panel-title {
  margin: 0;
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.panel-hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

/* 月历：与资源预约三步页同语言 */
.calendar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.calendar-title {
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-bold);
  color: var(--color-text-primary);
}

.calendar-nav {
  display: flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.calendar-nav button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 26px;
  min-width: 26px;
  padding: 0 var(--spacing-xs);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: #fff;
  color: var(--color-text-secondary);
  cursor: pointer;
}

.calendar-nav button:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

.calendar-nav svg {
  width: 14px;
  height: 14px;
}

.calendar-back {
  font-size: var(--font-size-xs);
}

.calendar-weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  font-size: var(--font-size-xs);
  color: var(--color-text-disabled);
  text-align: center;
}

.calendar-cells {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  row-gap: var(--spacing-xs);
  min-height: 216px;
}

.calendar-cell {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: var(--spacing-xs) 0;
  border: none;
  background: none;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  min-height: 44px;
}

.calendar-cell.is-blank {
  cursor: default;
}

/* 禁选：过去/无时段/约满 置灰 */
.calendar-cell.is-off .cell-day {
  color: var(--color-text-disabled);
  opacity: 0.55;
}

.calendar-cell.is-off {
  cursor: not-allowed;
}

.cell-day {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-circle);
}

.calendar-cell.is-today .cell-day {
  border: 1.5px solid var(--color-primary);
  color: var(--color-primary);
}

.calendar-cell.is-selected .cell-day {
  background: var(--color-primary);
  border: 1.5px solid var(--color-primary);
  color: #fff;
}

.calendar-cell:not(.is-blank):not(.is-off):hover .cell-day {
  background: var(--color-primary-bg);
}

.calendar-cell.is-selected:hover .cell-day {
  background: var(--color-primary);
}

/* 有可约时段的日期：主色小点 */
.cell-dot {
  width: 5px;
  height: 5px;
  border-radius: var(--radius-circle);
  background: var(--color-primary);
}

/* 约满日期：灰色「满」角标 */
.cell-full {
  font-size: 10px;
  line-height: 1;
  color: var(--color-text-disabled);
}

.calendar-empty-hint {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  text-align: center;
}

.calendar-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-md);
  margin: 0;
  padding-top: var(--spacing-sm);
  border-top: 1px dashed var(--color-border);
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
}

.legend-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-circle);
  background: var(--color-primary);
}

.legend-off {
  width: 12px;
  height: 12px;
  border-radius: var(--radius-sm);
  background: var(--color-bg-hover);
}

.legend-full {
  font-size: 10px;
  color: var(--color-text-disabled);
}

/* 第二步：时段卡网格（多选） */
.slot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: var(--spacing-sm);
  min-height: 120px;
}

.slot-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1.5px solid var(--color-border);
  border-radius: var(--radius-md);
  background: #fff;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, box-shadow 0.15s ease;
}

.slot-card:not(.is-full):hover {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
}

.slot-card.is-selected {
  border-color: var(--color-primary);
  background-color: var(--color-primary-bg);
  box-shadow: var(--shadow-md);
}

.slot-card.is-full {
  background-color: var(--color-bg);
  cursor: not-allowed;
  opacity: 0.55;
}

.slot-card-time {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  font-family: var(--font-family-mono);
  color: var(--color-text-primary);
}

.slot-card-quota {
  font-size: var(--font-size-xs);
  color: var(--color-success);
}

.slot-card.is-full .slot-card-quota {
  color: var(--color-text-disabled);
}

.slot-card.is-selected .slot-card-time,
.slot-card.is-selected .slot-card-quota {
  color: var(--color-primary);
}

.slot-hint {
  margin: 0;
  color: var(--color-text-disabled);
  font-size: var(--font-size-sm);
}

.slot-hint.is-active {
  color: var(--color-primary);
}

.step2-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
}

.ghost-btn {
  padding: var(--spacing-xs) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: #fff;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  cursor: pointer;
}

.ghost-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary);
}

/* 第三步：摘要 + 补充信息 */
.summary {
  margin: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.summary-row {
  display: flex;
  align-items: baseline;
}

.summary-row + .summary-row {
  border-top: 1px dashed var(--color-border);
}

.summary-row dt {
  flex-shrink: 0;
  width: 96px;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--color-bg);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.summary-row dd {
  margin: 0;
  padding: var(--spacing-sm) var(--spacing-md);
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  font-family: var(--font-family-mono);
}

.supplement {
  border-top: 1px dashed var(--color-border);
  padding-top: var(--spacing-md);
}

.supplement-form {
  max-width: 560px;
}

.submit-row {
  display: flex;
  gap: var(--spacing-sm);
}

@media (max-width: 900px) {
  .reserve-layout {
    grid-template-columns: 1fr;
  }

  .step-rail {
    position: static;
    flex-direction: row;
    flex-wrap: wrap;
  }

  .step-item {
    flex: 1;
    min-width: 140px;
  }
}
</style>
