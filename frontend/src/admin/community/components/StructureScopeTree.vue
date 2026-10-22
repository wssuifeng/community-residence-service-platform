<script setup lang="ts">
import type { HouseStatus } from '@/common/community/types/community'
import type { BuildingNode } from '@/admin/community/types/structure'
defineProps<{ buildings:BuildingNode[]; activeBuildingId:number|null; activeUnitId:number|null; activeBuildingName:string; activeUnitName:string; structureLoading:boolean; scopeSummary:{total:number;occupied:number;vacant:number}; communityMetrics:{houses:number}; filtersActive:boolean; scopeUnitOptions:{value:number;label:string}[]; floorOptions:number[]; statusOptions:{value:HouseStatus;label:string;count:number}[] }>()
const houseKeyword=defineModel<string>('houseKeyword',{required:true})
const activeFloor=defineModel<number|null>('activeFloor',{required:true})
const activeStatus=defineModel<HouseStatus|null>('activeStatus',{required:true})
const emit=defineEmits<{building:[unknown];unit:[unknown];clear:[]}>()
</script>
<template>
<section class="filter-panel" aria-label="房屋筛选">
            <div class="filter-head">
              <span class="filter-title">筛选</span>
              <span v-if="activeBuildingId !== null" class="filter-scope-chip">
                {{ activeBuildingName }}<template v-if="activeUnitId !== null"> / {{ activeUnitName }}</template>
              </span>
              <span class="filter-result">
                共 {{ scopeSummary.total }} 套 · 已入住 {{ scopeSummary.occupied }} · 空置
                {{ scopeSummary.vacant }}
                <template v-if="!structureLoading"> · 小区共 {{ communityMetrics.houses }} 套</template>
              </span>
              <el-button
                v-if="filtersActive"
                size="small"
                link
                type="primary"
                class="filter-reset"
                @click="emit('clear')"
              >
                清除筛选
              </el-button>
            </div>

            <div class="filter-line">
              <span class="filter-label">范围</span>
              <el-select
                :model-value="activeBuildingId"
                class="filter-select"
                size="small"
                placeholder="全部楼栋"
                clearable
                @change="emit('building',$event)"
              >
                <el-option
                  v-for="node in buildings"
                  :key="node.building.id"
                  :label="node.building.name"
                  :value="node.building.id"
                />
              </el-select>
              <el-select
                :model-value="activeUnitId"
                class="filter-select"
                size="small"
                placeholder="全部单元"
                clearable
                :disabled="scopeUnitOptions.length === 0"
                @change="emit('unit',$event)"
              >
                <el-option
                  v-for="item in scopeUnitOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
              <el-input
                v-model="houseKeyword"
                class="filter-keyword"
                size="small"
                placeholder="搜索房号，如 101 / 12"
                clearable
              />
            </div>

            <div class="filter-line">
              <span class="filter-label">楼层</span>
              <div v-if="floorOptions.length > 0" class="chips" role="group" aria-label="按楼层筛选">
                <button
                  type="button"
                  class="chip-btn"
                  :class="{ active: activeFloor === null }"
                  @click="activeFloor = null"
                >
                  全部楼层
                </button>
                <button
                  v-for="floor in floorOptions"
                  :key="floor"
                  type="button"
                  class="chip-btn"
                  :class="{ active: activeFloor === floor }"
                  @click="activeFloor = activeFloor === floor ? null : floor"
                >
                  {{ floor }}层
                </button>
              </div>
              <span v-else class="filter-empty">当前范围暂无楼层</span>
            </div>

            <div class="filter-line">
              <span class="filter-label">状态</span>
              <div class="chips" role="group" aria-label="按房屋状态筛选">
                <button
                  type="button"
                  class="chip-btn"
                  :class="{ active: activeStatus === null }"
                  @click="activeStatus = null"
                >
                  全部状态
                </button>
                <button
                  v-for="item in statusOptions"
                  :key="item.value"
                  type="button"
                  class="chip-btn"
                  :class="{ active: activeStatus === item.value }"
                  @click="activeStatus = activeStatus === item.value ? null : item.value"
                >
                  {{ item.label }}
                  <span class="chip-count">{{ item.count }}</span>
                </button>
              </div>
            </div>
          </section>
</template>
