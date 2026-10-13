<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateHouseAttributes } from '@/api/community'
import type { HouseAttributesPatch } from '@/api/community'
import type { IHouse } from '@/types/modules/community'
const props = defineProps<{ houses: IHouse[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [unitIds: number[]] }>()
/** 当前EP数值控件仅挂载时写aria-disabled，启停变化重建控件保证可访问状态一致。 */
type Field = HouseAttributesPatch['fields'][number]
const selected = ref<Field[]>([])
const submitting = ref(false)
const single = computed(() => props.houses.length === 1)
const form = reactive({ houseNumber: '', floor: undefined as number | undefined, area: undefined as number | undefined, layout: '', orientation: '', description: '' })
const fields: Array<{ key: Field; label: string; max?: number }> = [
  { key: 'houseNumber', label: '房号', max: 20 }, { key: 'floor', label: '楼层' }, { key: 'area', label: '面积（㎡）' },
  { key: 'layout', label: '户型', max: 50 }, { key: 'orientation', label: '朝向', max: 20 }, { key: 'description', label: '描述', max: 1000 }
]
watch(visible, value => {
  if (!value) return
  selected.value = []
  const first = props.houses[0]
  for (const field of fields) {
    const same = props.houses.every(h => h[field.key] === first?.[field.key])
    if (field.key === 'floor' || field.key === 'area') form[field.key] = same ? first?.[field.key] : undefined
    else form[field.key] = same ? (first?.[field.key] ?? '') : ''
  }
})
function setField(key: Field, enabled: boolean): void {
  selected.value = enabled ? [...new Set([...selected.value, key])] : selected.value.filter(value => value !== key)
}
async function submit(): Promise<void> {
  if (!props.houses.length || !selected.value.length || submitting.value) return
  if (selected.value.includes('houseNumber') && !form.houseNumber.trim()) { ElMessage.warning('房号不能为空'); return }
  if (selected.value.includes('floor') && (!Number.isInteger(form.floor) || (form.floor ?? 0) < 1)) { ElMessage.warning('楼层须为正整数，且不能超过各自楼栋层数'); return }
  if (selected.value.includes('area') && (form.area === undefined || form.area < 0)) { ElMessage.warning('请输入非负面积'); return }
  submitting.value = true
  try {
    const data: HouseAttributesPatch = { houseIds: props.houses.map(h => h.id), fields: [...selected.value] }
    for (const key of selected.value) Object.assign(data, { [key]: typeof form[key] === 'string' ? (form[key] as string).trim() : form[key] })
    const count = await updateHouseAttributes(data)
    ElMessage.success(`已更新${count}套房屋数据`)
    emit('saved', [...new Set(props.houses.map(h => h.unitId))])
    visible.value = false
  } catch(error) { ElMessage.error(error instanceof Error ? error.message : '房屋数据保存失败，请刷新选区后重试') }
  finally { submitting.value = false }
}
</script>
<template>
  <el-dialog v-model="visible" title="编辑所选房屋数据" width="min(620px, calc(100vw - 32px))" :close-on-click-modal="!submitting" :show-close="!submitting" :close-on-press-escape="!submitting">
    <p class="selection-summary">已选 {{ houses.length }} 套：{{ houses.map(h => h.houseNumber).join('、') }}</p>
    <el-alert type="info" :closable="false" title="只保存勾选的字段；勾选户型、朝向、描述后留空表示清除。不修改社区/单元归属、生命周期状态及居住关系。楼层按各自楼栋上限校验，任一非法项全部拒绝。" />
    <el-form label-width="0" class="attributes-form">
      <el-form-item v-for="field in fields.filter(f => f.key !== 'houseNumber' || single)" :key="field.key">
        <div class="attribute-row">
        <el-checkbox :model-value="selected.includes(field.key)" :disabled="submitting" @change="(value: boolean | string | number) => setField(field.key, !!value)">{{ field.label }}</el-checkbox>
        <el-input-number :key="`${field.key}-${selected.includes(field.key)}-${submitting}`" v-if="field.key === 'floor' || field.key === 'area'" v-model="form[field.key]" :min="field.key === 'floor' ? 1 : 0" :precision="field.key === 'floor' ? 0 : 2" :disabled="!selected.includes(field.key) || submitting" />
        <el-input v-else v-model="form[field.key]" :maxlength="field.max" :type="field.key === 'description' ? 'textarea' : 'text'" :disabled="!selected.includes(field.key) || submitting" :placeholder="field.key === 'houseNumber' ? '同单元内唯一房号' : '勾选后填写，留空清除'" />
        </div>
      </el-form-item>
    </el-form>
    <template #footer><el-button :disabled="submitting" @click="visible = false">取消</el-button><el-button type="primary" :loading="submitting" :disabled="!selected.length" @click="submit">保存房屋数据</el-button></template>
  </el-dialog>
</template>
<style scoped>
.selection-summary { max-height: 96px; overflow: auto; overflow-wrap: anywhere; }
.attributes-form { margin-top: 20px; }
.attribute-row { display: flex; align-items: center; gap: 12px; width: 100%; }
.attribute-row .el-checkbox { width: 110px; flex-shrink: 0; margin-right: 0; }
.attribute-row .el-input, .attribute-row .el-input-number { flex: 1; min-width: 0; }
</style>
