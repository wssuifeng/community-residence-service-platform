<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '@/common/web/utils/request'
import { useLiveRefresh } from '@/common/web/composables/useLiveRefresh'
const router=useRouter()
const rows=ref<Array<{id:number;leaseId:number;residentId:number;months:number;originalEndDate:string;proposedEndDate:string}>>([])
const error=ref('')
async function load(){try{const result=await http.get<typeof rows.value>('/lease-extensions/pending');if(!Array.isArray(result))throw new Error('待审申请返回格式异常，请重试');rows.value=result;error.value=''}catch(e){error.value=e instanceof Error?e.message:'租期延长待审列表读取失败'}}
onMounted(load);useLiveRefresh(load)
</script>
<template><section v-if="rows.length||error" class="extension-inbox"><h3>待审核租期延长 <span>{{rows.length}}项</span></h3><el-alert v-if="error" :title="error" type="error" :closable="false"/><el-button v-if="error" @click="load">重试读取</el-button><article v-for="row in rows" :key="row.id"><div><strong>租约 #{{row.leaseId}} · 延长{{row.months}}个月</strong><p>{{row.originalEndDate}} → {{row.proposedEndDate}} · 主租客账号 #{{row.residentId}}</p></div><el-button type="primary" plain @click="router.push({path:`/admin/leases/${row.leaseId}`,query:{panel:'terms'}})">查看方案并审核</el-button></article></section></template>
<style scoped>.extension-inbox{border:1px solid var(--el-color-warning-light-5);border-radius:8px;padding:16px;margin:12px 0}.extension-inbox h3{margin:0 0 12px;font-size:16px}.extension-inbox h3 span{font-size:13px;font-weight:400}.extension-inbox article{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}.extension-inbox p{font-size:13px;color:var(--el-text-color-secondary)}</style>
