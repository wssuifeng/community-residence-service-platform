<script setup lang="ts">
defineProps<{ cards: { key:string; tone:string; label:string; value:number; hint:string; tab:string }[] }>()
const emit=defineEmits<{open:[tab:string]}>()
const STAT_ICONS: Record<string, string[]> = {
  todo: ['M7 3h10a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z', 'M9 8h6M9 12h6M9 16h3'],
  doing: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z', 'M12 7v5l3 2'],
  done: ['M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z', 'M8 12.5l2.5 2.5L16 9.5'],
  urgent: ['M12 4l9 16H3z', 'M12 10v4M12 17h.01']
}
</script>
<template>
    <section class="panel overview-panel">
      <h2 class="panel-title">当前工单</h2>

      <div class="stat-cards">
        <button
          v-for="card in cards"
          :key="card.key"
          class="stat-card"
          :class="`is-${card.tone}`"
          type="button"
          :title="card.hint"
          @click="emit('open',card.tab)"
        >
          <span class="stat-top">
            <span class="stat-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path v-for="(d, i) in STAT_ICONS[card.key]" :key="i" :d="d" />
              </svg>
            </span>
            <span class="stat-label">{{ card.label }}</span>
          </span>

          <span class="stat-main">
            <span class="stat-figures">
              <span class="stat-value">{{ card.value }}</span>
              <span class="stat-delta">当前状态</span>
            </span>
          </span>
        </button>
      </div>
    </section>

</template>
