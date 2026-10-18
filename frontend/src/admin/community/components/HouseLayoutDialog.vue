<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateHouseLayouts } from '@/common/community/api/community'
import type { IHouse } from '@/common/community/types/community'
const props = defineProps<{ houses: IHouse[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [unitIds: number[]] }>()
const layout = ref('')
const submitting = ref(false)
const currentLayout = computed(() => {
  const values = [...new Set(props.houses.map(h => h.layout || '未填写'))]
  return values.length === 1 ? values[0] : '多个不同户型'
})
watch(visible, value => { if(value) layout.value = props.houses.every(h => h.layout === props.houses[0]?.layout) ? (props.houses[0]?.layout ?? '') : '' })
async function submit(): Promise<void> {
  if (!props.houses.length || submitting.value) return
  submitting.value = true
  try {
    const count = await updateHouseLayouts(props.houses.map(h => h.id), layout.value.trim())
    ElMessage.success(`已更新${count}套房屋户型`)
    emit('saved', [...new Set(props.houses.map(h => h.unitId))])
    visible.value = false
  } catch(error) { ElMessage.error(error instanceof Error ? error.message : '户型保存失败，请刷新选区后重试') }
  finally { submitting.value = false }
}
</script>
<template>
  <el-dialog v-model="visible" title="配置所选房屋户型" width="520px" :close-on-click-modal="!submitting" :show-close="!submitting" :close-on-press-escape="!submitting">
    <p class="selection-summary">已选 {{ houses.length }} 套：{{ houses.map(h => h.houseNumber).join('、') }}</p>
    <p>当前户型：{{ currentLayout }}</p>
    <el-form label-width="80px"><el-form-item label="新户型"><el-input v-model="layout" maxlength="50" placeholder="如：三室一厅一卫，留空清除" /></el-form-item></el-form>
    <template #footer><el-button :disabled="submitting" @click="visible = false">取消</el-button><el-button type="primary" :loading="submitting" @click="submit">保存</el-button></template>
  </el-dialog>
</template>

<style scoped>
.selection-summary { max-height: 96px; overflow: auto; overflow-wrap: anywhere; }
</style>
