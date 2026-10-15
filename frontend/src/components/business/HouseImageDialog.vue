<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listHouseImages, uploadHouseImage, saveHouseImages, removeHouseImage } from '@/api/houseImages'
import type { HouseImage } from '@/api/houseImages'

const props = defineProps<{ modelValue: boolean; houseId: number | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()
const images = ref<HouseImage[]>([])
const busy = ref(false)
const uploading = ref(false)
const error = ref('')
const kind = ref<HouseImage['kind']>('PHOTO')
type UploadItem = { file: File; state: 'waiting' | 'uploading' | 'success' | 'failed'; error: string; valid: boolean }
const queue = ref<UploadItem[]>([])
const uploadedCount = computed(() => queue.value.filter(i => i.state === 'success').length)
const baseline = ref('[]')
const dirty = computed(() => JSON.stringify(images.value) !== baseline.value)
let loadEpoch = 0
let batchHouse: number | null = null
let batchKind: HouseImage['kind'] = 'PHOTO'
function acceptRows(rows: HouseImage[]) { images.value = rows; baseline.value = JSON.stringify(rows) }
async function load() {
 const epoch = ++loadEpoch
 const houseId = props.houseId
 acceptRows([]); error.value = ''; busy.value = !!houseId
 if (!houseId) return
 try {
  const rows = await listHouseImages(houseId)
  if (epoch === loadEpoch && props.modelValue && houseId === props.houseId) acceptRows(rows)
 } catch(e) {
  if (epoch === loadEpoch) error.value = e instanceof Error ? e.message : '相册加载失败，请重试'
 } finally { if (epoch === loadEpoch) busy.value = false }
}
watch(() => [props.modelValue, props.houseId], () => {
 queue.value = []
 if (props.modelValue) void load()
 else { loadEpoch++; acceptRows([]); error.value = ''; busy.value = false }
}, { immediate: true })
async function save(): Promise<boolean> {
 if (!props.houseId || busy.value || uploading.value || error.value) return false
 busy.value = true
 try { acceptRows(await saveHouseImages(props.houseId, images.value)); emit('saved'); ElMessage.success('分类、说明和顺序已保存'); return true }
 catch(e) { ElMessage.error(e instanceof Error ? e.message : '保存失败'); return false }
 finally { busy.value = false }
}
async function settleEdits() {
 if (!dirty.value) return true
 try {
  await ElMessageBox.confirm('已有未保存的分类、说明或顺序。请先保存，再继续上传或移除图片。', '保留相册修改', { confirmButtonText: '保存并继续', cancelButtonText: '取消', type: 'warning' })
  return await save()
 } catch { return false }
}
async function runQueue(items: UploadItem[]) {
 const houseId = batchHouse
 const uploadKind = batchKind
 if (!houseId || uploading.value) return
 uploading.value = true
 try {
  for (const item of items) {
   if (houseId !== props.houseId || !props.modelValue) break
   if (!item.valid) continue
   item.state = 'uploading'; item.error = ''
   try { await uploadHouseImage(houseId, item.file, uploadKind); item.state = 'success'; emit('saved') }
   catch(e) { item.state = 'failed'; item.error = e instanceof Error ? e.message : '上传失败，请重试' }
  }
  if (houseId === props.houseId && props.modelValue) {
   try { acceptRows(await listHouseImages(houseId)); error.value = '' }
   catch { error.value = '图片上传结果已记录，相册刷新失败，请重新加载后继续操作' }
  }
 } finally { uploading.value = false }
}
async function upload(event: Event) {
 const input = event.target as HTMLInputElement
 const files = Array.from(input.files ?? []); input.value = ''
 if (!files.length || !props.houseId || busy.value || uploading.value) return
 if (files.length + images.value.length > 10) { ElMessage.error(`本次选择${files.length}张，当前还可上传${10-images.value.length}张，请重新选择`); return }
 if (!(await settleEdits())) return
 batchHouse = props.houseId; batchKind = kind.value
 queue.value = files.map(file => {
  const valid = file.size <= 5*1024*1024 && /\.(jpe?g|png|gif)$/i.test(file.name)
  return { file, valid, state: valid ? 'waiting' : 'failed', error: valid ? '' : '仅支持5MB以内的JPG/PNG/GIF图片' }
 })
 await runQueue(queue.value)
}
async function retry(item: UploadItem) {
 if (busy.value || uploading.value || !item.valid || batchHouse !== props.houseId) return
 if (images.value.length >= 10) { ElMessage.error('相册已满，请先移除不需要的图片'); return }
 if (await settleEdits()) await runQueue([item])
}
function move(index: number, step: number) {
 const target = index + step
 if (busy.value || uploading.value || target < 0 || target >= images.value.length) return
 const copy = [...images.value]; const row = copy.splice(index,1)[0]!
 copy.splice(target,0,row); images.value = copy
}
function cover(index: number) { if (busy.value || uploading.value) return; const copy=[...images.value]; const row=copy.splice(index,1)[0]!; copy.unshift(row); images.value=copy }
async function remove(row: HouseImage) {
 if (!props.houseId || busy.value || uploading.value || !(await settleEdits())) return
 try { await ElMessageBox.confirm('移除该房屋图片？不会删除其他业务引用的文件。','移除图片',{type:'warning'}) } catch { return }
 busy.value=true
 try { await removeHouseImage(props.houseId,row.id); await load(); emit('saved') }
 catch(e) { ElMessage.error(e instanceof Error ? e.message : '移除失败') } finally { busy.value=false }
}
async function close() {
 if (busy.value || uploading.value) return
 if (dirty.value) {
  try { await ElMessageBox.confirm('尚有未保存的分类、说明或顺序，确认放弃修改并关闭？','未保存的修改',{type:'warning',confirmButtonText:'放弃修改并关闭'}) } catch { return }
 }
 emit('update:modelValue', false)
}
</script>
<template>
 <el-dialog :model-value="modelValue" title="管理房屋图片" width="min(760px, 94vw)" :close-on-click-modal="!uploading && !busy" :close-on-press-escape="!uploading && !busy" :before-close="close">
  <p>最多10张，每张不超过5MB。实拍图优先作为封面；没有实拍图时使用第一张平面图。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-button v-if="error" @click="load">重新加载</el-button>
  <div v-else v-loading="busy">
   <div class="album-toolbar"><el-select :disabled="uploading || busy" v-model="kind" aria-label="上传图片类型"><el-option label="实拍图" value="PHOTO"/><el-option label="户型平面图" value="FLOOR_PLAN"/></el-select><input type="file" multiple accept=".jpg,.jpeg,.png,.gif" :disabled="busy || uploading || images.length >= 10" aria-label="上传房屋图片" @change="upload"/></div>
   <p>选择图片（可多选），还可上传{{ 10-images.length }}张。</p>
   <section v-if="queue.length" class="upload-queue" aria-live="polite"><strong>已成功{{ uploadedCount }}/{{ queue.length }}</strong><ul><li v-for="(item,index) in queue" :key="index"><span>{{ item.file.name }}</span><span>{{ ({waiting:'等待上传',uploading:'上传中',success:'已保存',failed:'失败'})[item.state] }}{{ item.error ? `：${item.error}` : '' }}</span><el-button v-if="item.state==='failed' && item.valid" :disabled="busy || uploading" @click="retry(item)">重试此图</el-button></li></ul></section>
   <el-empty v-if="!images.length" description="尚未上传房屋图片" />
   <article v-for="(row,index) in images" :key="row.id" class="album-row">
    <el-image :src="row.storageKey" :preview-src-list="images.map(i=>i.storageKey)" fit="cover" class="album-image" />
    <div class="album-fields"><el-select :disabled="busy || uploading" v-model="row.kind" aria-label="图片类型"><el-option label="实拍图" value="PHOTO"/><el-option label="户型平面图" value="FLOOR_PLAN"/></el-select><el-input :disabled="busy || uploading" v-model="row.caption" maxlength="200" placeholder="图片说明"/><div><el-button :disabled="busy || uploading || index===0" @click="move(index,-1)">前移</el-button><el-button :disabled="busy || uploading || index===images.length-1" @click="move(index,1)">后移</el-button><el-button :disabled="busy || uploading" @click="cover(index)">设为封面</el-button><el-button :disabled="busy || uploading" type="danger" @click="remove(row)">移除</el-button></div></div>
   </article>
  </div>
  <template #footer><el-button :disabled="busy || uploading" @click="close">关闭</el-button><el-button type="primary" :loading="busy" :disabled="!!error || uploading" @click="save">保存分类与顺序</el-button></template>
 </el-dialog>
</template>
<style scoped>
.album-toolbar,.album-row{display:flex;gap:12px;margin:14px 0;flex-wrap:wrap}.album-image{width:140px;height:100px;flex-shrink:0}.album-fields{display:grid;gap:8px;flex:1;min-width:200px}.album-fields>div:last-child{display:flex;flex-wrap:wrap;gap:6px}.album-fields .el-button+.el-button{margin-left:0}
.upload-queue{padding:12px;background:var(--admin-card-bg,#f5f7fa);border:1px solid #dce4e9;border-radius:8px}.upload-queue ul{padding:0;list-style:none}.upload-queue li{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:8px}.upload-queue li>span:first-child{overflow-wrap:anywhere}
</style>

