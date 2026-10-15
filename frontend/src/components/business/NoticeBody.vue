<script setup lang="ts">
import { computed, h, type VNode } from 'vue'
import type { NoticeNode } from '@/utils/noticeDocument'
const props=withDefaults(defineProps<{bodyJson?:string|null;content?:string;images?:string[]}>(),{content:'',images:()=>[]})
const doc=computed<NoticeNode|null>(()=>{try{return props.bodyJson?JSON.parse(props.bodyJson):null}catch{return null}})
const safeImage=(src:unknown)=>typeof src==='string'&&/^\/uploads\/[A-Za-z0-9_./-]+$/.test(src)&&!src.includes('..')&&!src.includes('//')
function render(n:NoticeNode,depth=0):VNode|string {
 if(depth>10)return ''
 if(n.type==='text')return n.marks?.some(m=>m.type==='bold')?h('strong',n.text??''):n.text??''
 if(n.type==='image'){const a=n.attrs??{};if(!safeImage(a.src))return '';const width=Math.min(100,Math.max(25,Number(a.widthPercent)||100));return h('figure',{style:{textAlign:['left','center','right'].includes(String(a.textAlign))?String(a.textAlign):'left'}},[h('img',{src:String(a.src),alt:String(a.alt??''),loading:'lazy',style:{width:`${width}%`,height:'auto'}}),a.alt?h('figcaption',String(a.alt)):null])}
 const tags:Record<string,string>={doc:'div',paragraph:'p',heading:'h2',hardBreak:'br',bulletList:'ul',orderedList:'ol',listItem:'li'}
 const tag=tags[n.type];if(!tag)return ''
 const attrs:Record<string,unknown>={}
 if(['paragraph','heading'].includes(n.type)&&['left','center','right'].includes(String(n.attrs?.textAlign)))attrs.style={textAlign:n.attrs!.textAlign}
 if(n.type==='orderedList')attrs.start=Math.max(1,Math.min(9999,Number(n.attrs?.start)||1))
 return h(tag,attrs,(n.content??[]).slice(0,1000).map(child=>render(child,depth+1)))
}
const renderDocument=()=>doc.value?render(doc.value):null
</script>
<template><div class="notice-rich-body"><component :is="renderDocument" v-if="doc"/><template v-else><div v-if="images.length" class="legacy-notice-images"><img v-for="src in images" :key="src" :src="src" alt="公告配图" loading="lazy"/></div><p v-for="(text,index) in content.split('\n')" :key="index">{{text}}</p></template></div></template>
<style scoped>.notice-rich-body{line-height:1.9;overflow-wrap:anywhere;min-width:0;color:inherit}.notice-rich-body :deep(p){white-space:pre-wrap;margin:0 0 1em}.notice-rich-body :deep(h2){font-size:1.35em;line-height:1.5;margin:1.3em 0 .6em}.notice-rich-body :deep(img){max-width:100%;border-radius:4px;object-fit:contain}.notice-rich-body :deep(figure){margin:1.2em 0}.notice-rich-body :deep(figcaption){font-size:.85em;color:var(--el-text-color-secondary);line-height:1.6}.legacy-notice-images{display:grid;gap:12px;margin-bottom:20px}</style>
