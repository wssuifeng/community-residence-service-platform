<script setup lang="ts">
import { computed, provide } from 'vue'
import { livePanelActive } from '@/common/web/composables/useLiveRefresh'
const props=defineProps<{ active: boolean }>()
provide(livePanelActive,computed(()=>props.active))
</script>
<template><div v-show="active"><slot /></div></template>
