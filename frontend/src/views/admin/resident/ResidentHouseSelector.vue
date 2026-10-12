<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getBuildingList, getUnitList, getHouseList } from '@/api/community'
import { loadAllPages, StaleStructureLoad } from '@/utils/paged'
import type { IBuilding, IUnit, IHouse } from '@/types/modules/community'
const props = defineProps<{ communityId?: number }>()
const houseId = defineModel<number | undefined>()
const buildingId = ref<number>()
const unitId = ref<number>()
const buildings = ref<IBuilding[]>([])
const units = ref<IUnit[]>([])
const houses = ref<IHouse[]>([])
const loading = ref({ buildings: false, units: false, houses: false })
const error = ref('')
let buildingRequest = 0, unitRequest = 0, houseRequest = 0
async function loadBuildings() {
  const version = ++buildingRequest, community = props.communityId
  buildings.value = []; buildingId.value = undefined; units.value = []; unitId.value = undefined; houses.value = []; houseId.value = undefined
  error.value = ''
  loading.value.buildings = false
  if (!community) return
  loading.value.buildings = true
  try { buildings.value = await loadAllPages(q => getBuildingList(community, q), () => version === buildingRequest) }
  catch (e) { if (!(e instanceof StaleStructureLoad) && version === buildingRequest) error.value = e instanceof Error ? e.message : '楼栋加载失败' }
  finally { if (version === buildingRequest) loading.value.buildings = false }
}
watch(() => props.communityId, loadBuildings, { immediate: true })
watch(buildingId, async id => {
  const version = ++unitRequest
  units.value = []; unitId.value = undefined; houses.value = []; houseId.value = undefined
  error.value = ''
  loading.value.units = false
  if (!id) return
  loading.value.units = true
  try { units.value = await loadAllPages(q => getUnitList(id, q), () => version === unitRequest) }
  catch (e) { if (!(e instanceof StaleStructureLoad) && version === unitRequest) error.value = e instanceof Error ? e.message : '单元加载失败' }
  finally { if (version === unitRequest) loading.value.units = false }
})
watch(unitId, async id => {
  const version = ++houseRequest
  houses.value = []; houseId.value = undefined
  error.value = ''
  loading.value.houses = false
  if (!id) return
  loading.value.houses = true
  try { houses.value = await loadAllPages(q => getHouseList(id, { ...q, status: 'VACANT' }), () => version === houseRequest) }
  catch (e) { if (!(e instanceof StaleStructureLoad) && version === houseRequest) error.value = e instanceof Error ? e.message : '房屋加载失败' }
  finally { if (version === houseRequest) loading.value.houses = false }
})
onBeforeUnmount(() => { buildingRequest++; unitRequest++; houseRequest++ })
</script>
<template>
  <el-form-item label="楼栋"><el-select v-model="buildingId" placeholder="请选择楼栋" filterable :disabled="!communityId" :loading="loading.buildings" style="width:100%"><el-option v-for="b in buildings" :key="b.id" :value="b.id" :label="b.name" /></el-select></el-form-item>
  <el-form-item label="单元"><el-select v-model="unitId" placeholder="请选择单元" filterable :disabled="!buildingId" :loading="loading.units" style="width:100%"><el-option v-for="u in units" :key="u.id" :value="u.id" :label="u.name" /></el-select></el-form-item>
  <el-form-item label="房屋" prop="houseId"><el-select v-model="houseId" placeholder="请选择空置房屋" filterable :disabled="!unitId" :loading="loading.houses" style="width:100%"><el-option v-for="h in houses" :key="h.id" :value="h.id" :label="`${h.houseNumber} · ${h.floor}层 · ${h.layout || '户型未填写'}`" /></el-select><small>仅可选空置房屋；系统房屋ID不是房号。</small></el-form-item>
  <el-alert v-if="error" :title="error" type="error" :closable="false" /><el-button v-if="error" text @click="loadBuildings">重新加载房屋范围</el-button>
</template>
