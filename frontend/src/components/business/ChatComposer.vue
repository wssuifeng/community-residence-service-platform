<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
defineProps<{modelValue:string;busy?:boolean;closed?:boolean;readOnly?:boolean;closedHint?:string}>()
const emit=defineEmits<{'update:modelValue':[value:string];send:[];image:[file:File]}>()
const picker=ref<HTMLInputElement|null>(null)
function onEnter(event: KeyboardEvent) { if (event.isComposing || event.keyCode === 229 || event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) return; event.preventDefault(); emit('send') }
function select(event:Event){const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file)return
 if(!/\.(png|jpe?g|gif)$/i.test(file.name)||file.size>5*1024*1024){ElMessage.error('请选择不超过5MB的JPG/PNG/GIF图片');input.value='';return}
 emit('image',file);input.value=''
}
</script>
<template>
 <div class="chat-composer">
  <p v-if="closed" class="chat-closed">{{closedHint??'会话已关闭，历史消息只读留档'}}</p>
  <p v-else-if="readOnly">仅会话参与者可参与沟通</p>
  <template v-else>
   <div class="chat-tools"><slot name="actions"/><button type="button" :disabled="busy" aria-label="发送图片" @click="picker?.click()">图片</button><input ref="picker" type="file" accept="image/png,image/jpeg,image/gif" hidden @change="select"/><span v-if="busy">发送中…</span></div>
   <div class="chat-input-row chat-input"><textarea :value="modelValue" maxlength="500" rows="2" aria-label="消息内容" placeholder="输入消息，Enter发送，Shift+Enter换行" :disabled="busy" @input="emit('update:modelValue',($event.target as HTMLTextAreaElement).value)" @keydown.enter="onEnter"/><button type="button" class="chat-send" :disabled="busy||!modelValue.trim()" @click="emit('send')">发送</button></div>
  </template>
 </div>
</template>
<style scoped>
.chat-composer{padding:14px 18px;border-top:1px solid var(--color-border,#e5e7eb);background:var(--color-surface,#fff)}.chat-tools{display:flex;gap:12px;align-items:center;margin-bottom:10px}.chat-tools button,.chat-send{padding:8px 12px;border:1px solid var(--color-border,#e5e7eb);border-radius:8px;background:transparent;color:inherit;cursor:pointer}.chat-input-row{display:flex;gap:12px;align-items:flex-end}.chat-input-row textarea{flex:1;min-width:0;resize:vertical;border:1px solid var(--color-border,#e5e7eb);border-radius:8px;padding:10px;font:inherit}.chat-send{background:var(--color-primary,#356aff);color:#fff}.chat-closed{color:var(--color-text-secondary,#667085)}button:disabled{opacity:.5;cursor:not-allowed}
</style>
