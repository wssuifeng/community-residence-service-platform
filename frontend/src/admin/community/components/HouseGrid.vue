<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import type { IUnit } from '@/common/community/types/community'
import { houseStatusLabels } from '@/common/community/types/community'
import type { GridGroup, HouseEntry } from '@/admin/community/types/structure'
import type { GridMarquee } from '@/common/web/composables/useGridSelection'
import { ACTION_ICONS, HOUSE_BLOCK_CLASS } from '@/admin/community/utils/structurePresentation'
defineProps<{ structureLoading:boolean; structureLoaded:boolean; scopeLoading:boolean; structureError:string; gridGroups:GridGroup[]; gridZoom:number; gridMarquee:GridMarquee; houseCount:number; isHouseGridSelected:(id:number)=>boolean }>()
const emit=defineEmits<{ element:[HTMLElement|null]; pointerDown:[PointerEvent]; pointerMove:[PointerEvent]; pointerUp:[PointerEvent]; pointerCancel:[PointerEvent]; retry:[]; clearFilters:[]; create:[IUnit]; batchCreate:[{buildingId:number;unitId:number}]; select:[MouseEvent,HouseEntry]; context:[MouseEvent,HouseEntry]; info:[HouseEntry]; command:[unknown,HouseEntry] }>()
const element=ref<HTMLElement|null>(null)
onMounted(()=>emit('element',element.value));onBeforeUnmount(()=>emit('element',null))
</script>
<template>
<div
            ref="element"
            class="grid-body"
            :class="{ 'is-marquee': gridMarquee.active }"
            @pointerdown="emit('pointerDown', $event)"
            @pointermove="emit('pointerMove', $event)"
            @pointerup="emit('pointerUp', $event)"
            @pointercancel="emit('pointerCancel', $event)"
          >
            <div v-if="(structureLoading && !structureLoaded) || scopeLoading" class="grid-skeleton">
              <el-skeleton :rows="4" animated />
            </div>

            <!-- 结构加载失败：不落在「还没有房屋」的空态里（否则误导为"没数据"） -->
            <div v-else-if="structureError" class="grid-state">
              <p class="state-title">结构与房屋加载失败</p>
              <p class="state-text">{{ structureError }}</p>
              <el-button size="small" @click="emit('retry')">重试</el-button>
            </div>

            <div v-else-if="gridGroups.length === 0" class="grid-state">
              <template v-if="houseCount === 0">
                <p class="state-title">该小区还没有房屋</p>
                <p class="state-text">
                  用上方「批量建房」一次生成 楼栋 → 单元 → 房屋；也可在单元列头点「＋」单独添加。
                </p>
              </template>
              <template v-else>
                <p class="state-title">当前筛选下没有房屋</p>
                <p class="state-text">放宽楼层 / 状态筛选，或清空房号关键字后再看。</p>
                <el-button size="small" @click="emit('clearFilters')">清除筛选</el-button>
              </template>
            </div>

            <!-- 单元平行列：一个单元一列，列多时横向滚动；缩放作用在此层，
                 框选矩形仍定位在未缩放的 .grid-body 上，选中坐标不受影响 -->
            <div v-else class="grid-groups" :style="{ zoom: gridZoom }">
              <div v-for="group in gridGroups" :key="group.key" class="grid-group">
                <h4 class="grid-group-title">
                  <span class="grid-group-name" :title="group.title">{{ group.title }}</span>
                  <span class="grid-group-count">{{ group.entries.length }} 套 · 空 {{ group.vacant }}</span>
                  <span class="grid-group-actions">
                    <button
                      v-permission="['ADMIN', 'SUPER_ADMIN']"
                      type="button"
                      class="icon-btn"
                      title="添加房屋"
                      :aria-label="`在${group.title}添加房屋`"
                      @click="emit('create',group.unit)"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path v-for="(d, i) in ACTION_ICONS.plus" :key="i" :d="d" />
                      </svg>
                    </button>
                    <button
                      v-permission="['ADMIN', 'SUPER_ADMIN']"
                      type="button"
                      class="icon-btn"
                      title="该单元批量建房"
                      :aria-label="`在${group.title}批量建房`"
                      @click="emit('batchCreate',{ buildingId: group.buildingId, unitId: group.unit.id })"
                    >
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <path v-for="(d, i) in ACTION_ICONS.layers" :key="i" :d="d" />
                      </svg>
                    </button>
                  </span>
                </h4>
                <div class="grid-rows">
                  <div
                    v-for="floor in [...new Set(group.entries.map((entry) => entry.house.floor))].sort((a, b) => b - a)"
                    :key="floor"
                    class="grid-row"
                  >
                    <span class="grid-floor">{{ floor }}层</span>
                    <div class="grid-cells">
                      <div
                        v-for="entry in group.entries.filter((item) => item.house.floor === floor)"
                        :key="entry.house.id"
                        class="grid-block"
                        :class="[
                          HOUSE_BLOCK_CLASS[entry.house.status],
                          { 'is-selected': isHouseGridSelected(entry.house.id) }
                        ]"
                        role="button"
                        tabindex="0"
                        :data-house-id="entry.house.id"
                        :title="`${entry.house.houseNumber} · ${houseStatusLabels[entry.house.status]}`"
                        @click="emit('select',$event,entry)"
                        @contextmenu.prevent.stop="emit('context',$event,entry)"
                        @keydown.enter.prevent="emit('info',entry)"
                        @keydown.space.prevent="emit('info',entry)"
                      >
                        <span class="block-number">{{ entry.house.houseNumber }}</span>
                        <span v-if="isHouseGridSelected(entry.house.id)" class="block-check" aria-hidden="true">
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M5 12.5l4.5 4.5L19 7.5" />
                          </svg>
                        </span>
                        <!-- 操作区不入卡片内容区：角标「⋯」展开菜单，卡片主体永远可点 -->
                        <el-dropdown
                          v-permission="['ADMIN', 'SUPER_ADMIN']"
                          class="block-menu"
                          trigger="click"
                          placement="bottom-end"
                          @command="emit('command',$event,entry)"
                        >
                          <button
                            type="button"
                            class="block-menu-btn"
                            :aria-label="`${entry.house.houseNumber} 更多操作`"
                            @click.stop
                            @keydown.enter.stop
                            @keydown.space.stop.prevent
                          >
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                              <path v-for="(d, i) in ACTION_ICONS.more" :key="i" :d="d" />
                            </svg>
                          </button>
                          <template #dropdown>
                            <el-dropdown-menu>
                              <el-dropdown-item command="info">
                                <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                  <path v-for="(d, i) in ACTION_ICONS.eye" :key="i" :d="d" />
                                </svg>
                                查看详情 / 在住人员
                              </el-dropdown-item>
                              <el-dropdown-item command="edit" divided>
                                <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                  <path v-for="(d, i) in ACTION_ICONS.pen" :key="i" :d="d" />
                                </svg>
                                编辑房屋
                              </el-dropdown-item>
                              <el-dropdown-item command="status">
                                <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                  <path v-for="(d, i) in ACTION_ICONS.swap" :key="i" :d="d" />
                                </svg>
                                状态变更
                              </el-dropdown-item>
                              <el-dropdown-item command="delete">
                                <svg class="menu-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                  <path v-for="(d, i) in ACTION_ICONS.trash" :key="i" :d="d" />
                                </svg>
                                删除房屋
                              </el-dropdown-item>
                            </el-dropdown-menu>
                          </template>
                        </el-dropdown>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <div
              v-if="gridMarquee.active"
              class="marquee-rect"
              :style="{
                left: `${gridMarquee.left}px`,
                top: `${gridMarquee.top}px`,
                width: `${gridMarquee.width}px`,
                height: `${gridMarquee.height}px`
              }"
              aria-hidden="true"
            />
          </div>
</template>
