<script setup lang="ts">
defineProps<{count:number;deleting:boolean}>()
defineEmits<{edit:[];remove:[];clear:[]}>()
</script>
<template>
<div v-if="count > 0" class="selection-bar" role="toolbar" aria-label="房屋批量操作">
              <span class="selection-count">已选 {{ count }} 套</span>
              <span class="selection-actions">
                <el-button size="small" @click="$emit('edit')">编辑房屋数据</el-button>
                <el-button
                  v-permission="['ADMIN', 'SUPER_ADMIN']"
                  size="small"
                  type="danger"
                  :loading="deleting"
                  @click="$emit('remove')"
                >
                  批量删除
                </el-button>
                <el-button size="small" @click="$emit('clear')">取消选择</el-button>
              </span>
            </div>
</template>
