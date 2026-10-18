<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { updateHouseAttributes } from '@/common/community/api/community'
import type { HouseAttributesPatch } from '@/common/community/api/community'
import type { IHouse } from '@/common/community/types/community'
const props = defineProps<{ houses: IHouse[] }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [unitIds: number[]] }>()
/** 当前EP数值控件仅挂载时写aria-disabled，启停变化重建控件保证可访问状态一致。 */
type Field = HouseAttributesPatch['fields'][number]
const selected = ref<Field[]>([])
const submitting = ref(false)
const clearFields = ref<Array<'layout' | 'orientation' | 'description'>>([])
const single = computed(() => props.houses.length === 1)
const form = reactive({ houseNumber: '', area: undefined as number | undefined, layout: '', orientation: '', description: '' })
const fields: Array<{ key: Field; label: string; max?: number }> = [
  { key: 'houseNumber', label: '房号', max: 20 }, { key: 'area', label: '面积（㎡）' },
  { key: 'layout', label: '户型', max: 50 }, { key: 'orientation', label: '朝向', max: 20 }, { key: 'description', label: '描述', max: 1000 }
]
watch(visible, value => {
  if (!value) return
  selected.value = []
  clearFields.value = []
  const first = props.houses[0]
  for (const field of fields) {
    const same = props.houses.every(h => h[field.key] === first?.[field.key])
    if (field.key === 'area') form[field.key] = same ? first?.[field.key] : undefined
    else form[field.key] = same ? (first?.[field.key] ?? '') : ''
  }
}, { immediate: true })
function setField(key: Field, enabled: boolean): void {
  selected.value = enabled ? [...new Set([...selected.value, key])] : selected.value.filter(value => value !== key)
  if (!enabled) clearFields.value = clearFields.value.filter(value => value !== key)
}
async function submit(): Promise<void> {
  if (!props.houses.length || !selected.value.length || submitting.value) return
  if (selected.value.includes('houseNumber') && !form.houseNumber.trim()) { ElMessage.warning('房号不能为空'); return }
  if (selected.value.includes('area') && (form.area === undefined || form.area < 0)) { ElMessage.warning('请输入非负面积'); return }
  for (const key of ['layout', 'orientation', 'description'] as const) {
    if (selected.value.includes(key) && !clearFields.value.includes(key) && !form[key].trim()) { ElMessage.warning('空白不表示清除，请填写内容或明确选择清除'); return }
  }
  submitting.value = true
  try {
    const data: HouseAttributesPatch = { houseIds: props.houses.map(h => h.id), fields: [...selected.value] }
    for (const key of selected.value) Object.assign(data, { [key]: typeof form[key] === 'string' ? (form[key] as string).trim() : form[key] })
    if (clearFields.value.length) data.clearFields = [...clearFields.value]
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
    <p class="selection-summary">所在楼层（只读）：{{ [...new Set(houses.map(h => h.floor))].join('、') }} 层</p>
    <el-alert type="info" :closable="false" title="未勾选的字段保持原值；空白不自动清除，清除需明确勾选。已创建房屋楼层只读，不修改结构归属、状态或居住关系。" />
    <el-form label-width="0" class="attributes-form">
      <el-form-item v-for="field in fields.filter(f => f.key !== 'houseNumber' || single)" :key="field.key">
        <div class="attribute-row">
        <el-checkbox :model-value="selected.includes(field.key)" :disabled="submitting" @change="(value: boolean | string | number) => setField(field.key, !!value)">{{ field.label }}</el-checkbox>
        <el-input-number :key="`${field.key}-${selected.includes(field.key)}-${submitting}`" v-if="field.key === 'area'" v-model="form[field.key]" :min="0" :precision="2" :disabled="!selected.includes(field.key) || submitting" />
        <el-input v-else v-model="form[field.key]" :maxlength="field.max" :type="field.key === 'description' ? 'textarea' : 'text'" :disabled="!selected.includes(field.key) || submitting || clearFields.includes(field.key as 'layout' | 'orientation' | 'description')" :placeholder="field.key === 'houseNumber' ? '同单元内唯一房号' : '勾选后填写，空白不自动清除'" />
        <el-checkbox v-if="['layout', 'orientation', 'description'].includes(field.key) && selected.includes(field.key)" :model-value="clearFields.includes(field.key as 'layout' | 'orientation' | 'description')" :disabled="submitting" @change="(value: boolean | string | number) => { const key = field.key as 'layout' | 'orientation' | 'description'; clearFields = value ? [...clearFields.filter(k => k !== key), key] : clearFields.filter(k => k !== key) }">清除{{ field.label }}</el-checkbox>
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
