<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import HousingReservationForm from '@/components/housing/HousingReservationForm.vue'
const route = useRoute(), router = useRouter()
const housingId = computed(() => Number(route.params.id))
function created(id?: number) { void router.push(id ? '/resident/viewing-appointments/' + id : '/resident/reservations?tab=viewing') }
</script>
<template><HousingReservationForm :key="housingId" :housing-id="housingId" @created="created" @invalid="router.replace('/resident/housings')" /></template>
