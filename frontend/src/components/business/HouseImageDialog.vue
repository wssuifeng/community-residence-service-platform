<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listHouseImages, uploadHouseImage, saveHouseImages, removeHouseImage, type HouseImage } from '@/api/houseImages'
const props = defineProps<{ modelValue: boolean; houseId: number | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>()
const images = ref<HouseImage[]>([])
const busy = ref(false)
const error = ref('')
const kind = ref<HouseImage['kind']>('PHOTO')
let loadEpoch = 0
async function load() {
 const epoch = ++loadEpoch
 const houseId = props.houseId
 images.value = []; error.value = ''; busy.value = !!houseId
 if (!houseId) return
 try {
  const rows = await listHouseImages(houseId)
  if (epoch === loadEpoch && props.modelValue && houseId === props.houseId) images.value = rows
 } catch(e) {
  if (epoch === loadEpoch) error.value = e instanceof Error ? e.message : '相册加载失败，请重试'
 } finally { if (epoch === loadEpoch) busy.value = false }
}
watch(() => [props.modelValue, props.houseId], () => {
 if (props.modelValue) void load()
 else { loadEpoch++; images.value = []; error.value = ''; busy.value = false }
}, { immediate: true })
async function upload(event: Event) {
 const input = event.target as HTMLInputElement
 const file = input.files?.[0]; input.value = ''
 if(!file || !props.houseId || busy.value) return
 if(file.size > 5 * 1024 * 1024 || !/\.(jpe?g|png|gif)$/i.test(file.name)) { ElMessage.error('仅支持5MB以内的JPG/PNG/GIF图片'); return }
 busy.value = true
 try { await uploadHouseImage(props.houseId, file, kind.value); await load(); emit('saved'); ElMessage.success('图片已上传并保存') }
 catch(e) { ElMessage.error(e instanceof Error ? e.message : '上传失败，请重试') }
 finally { busy.value = false }
}
function move(index: number, step: number) {
 const target=index+step
 if(target<0 || target>=images.value.length) return
 const copy=[...images.value]; const row=copy.splice(index,1)[0]!
 copy.splice(target,0,row); images.value=copy
}
function cover(index: number) { const copy=[...images.value]; const row=copy.splice(index,1)[0]!; copy.unshift(row); images.value=copy }
async function save() {
 if(!props.houseId || busy.value || error.value) return
 busy.value=true
 try { images.value=await saveHouseImages(props.houseId,images.value); emit('saved');ElMessage.success('分类、说明和顺序已保存') }
 catch(e) { ElMessage.error(e instanceof Error ? e.message : '保存失败') } finally { busy.value=false }
}
async function remove(row: HouseImage) {
 if(!props.houseId || busy.value) return
 try { await ElMessageBox.confirm('移除该房屋图片？不会删除其他业务引用的文件。','移除图片',{type:'warning'}) } catch { return }
 busy.value=true
 try { await removeHouseImage(props.houseId,row.id);await load();emit('saved') }
 catch(e) { ElMessage.error(e instanceof Error ? e.message : '移除失败') } finally { busy.value=false }
}
</script>
<template>
 <el-dialog :model-value="modelValue" title="管理房屋图片" width="min(760px, 94vw)" @update:model-value="emit('update:modelValue', $event)">
  <p>最多10张，每张不超过5MB。实拍图优先作为封面；没有实拍图时使用第一张平面图。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-button v-if="error" @click="load">重新加载</el-button>
  <div v-else v-loading="busy">
   <div class="album-toolbar"><el-select v-model="kind" aria-label="上传图片类型"><el-option label="实拍图" value="PHOTO"/><el-option label="户型平面图" value="FLOOR_PLAN"/></el-select><input type="file" accept=".jpg,.jpeg,.png,.gif" :disabled="busy || images.length >= 10" aria-label="上传房屋图片" @change="upload"/></div>
   <el-empty v-if="!images.length" description="尚未上传房屋图片" />
   <article v-for="(row,index) in images" :key="row.id" class="album-row">
    <el-image :src="row.storageKey" :preview-src-list="images.map(i=>i.storageKey)" fit="cover" class="album-image" />
    <div class="album-fields"><el-select v-model="row.kind" aria-label="图片类型"><el-option label="实拍图" value="PHOTO"/><el-option label="户型平面图" value="FLOOR_PLAN"/></el-select><el-input v-model="row.caption" maxlength="200" placeholder="图片说明"/><div><el-button :disabled="busy || index===0" @click="move(index,-1)">前移</el-button><el-button :disabled="busy || index===images.length-1" @click="move(index,1)">后移</el-button><el-button :disabled="busy" @click="cover(index)">设为封面</el-button><el-button :disabled="busy" type="danger" @click="remove(row)">移除</el-button></div></div>
   </article>
  </div>
  <template #footer><el-button @click="emit('update:modelValue',false)">关闭</el-button><el-button type="primary" :loading="busy" :disabled="!!error" @click="save">保存分类与顺序</el-button></template>
 </el-dialog>
</template>
<style scoped>
.album-toolbar,.album-row{display:flex;gap:12px;margin:14px 0;flex-wrap:wrap}.album-image{width:140px;height:100px;flex-shrink:0}.album-fields{display:grid;gap:8px;flex:1;min-width:200px}.album-fields>div:last-child{display:flex;flex-wrap:wrap;gap:6px}.album-fields .el-button+.el-button{margin-left:0}
</style>
