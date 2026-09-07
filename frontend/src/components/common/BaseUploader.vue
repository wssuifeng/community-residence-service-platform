<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/utils/request'

/**
 * 通用上传组件：图片/文件统一入口（P2 文件上传接口就绪前，
 * 默认请求 /upload，可通过 uploadRequest prop 注入自定义实现）
 */
const props = withDefaults(
  defineProps<{
    /** 已上传文件 URL 列表（v-model） */
    modelValue: string[]
    /** 接受的文件类型（input accept） */
    accept?: string
    /** 单文件大小上限（MB） */
    maxSize?: number
    /** 最多文件数 */
    limit?: number
    /** 预览模式：image 缩略图 / file 文件行 / none 仅计数 */
    preview?: 'image' | 'file' | 'none'
  }>(),
  {
    accept: 'image/*',
    maxSize: 5,
    limit: 6,
    preview: 'image'
  }
)

const emit = defineEmits<{
  'update:modelValue': [urls: string[]]
}>()

const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

/** 默认上传实现：multipart 表单提交到通用上传接口（接口设计.md 9.13） */
async function defaultUpload(file: File): Promise<string> {
  const formData = new FormData()
  formData.append('file', file)
  const result = await http.post<{ url: string }>('/upload', formData)
  return result.url
}

function handleClick(): void {
  fileInput.value?.click()
}

async function handleChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''

  if (files.length === 0) return
  if (props.modelValue.length + files.length > props.limit) {
    ElMessage.warning(`最多上传 ${props.limit} 个文件`)
    return
  }

  const oversized = files.find((file) => file.size > props.maxSize * 1024 * 1024)
  if (oversized) {
    ElMessage.warning(`单个文件不能超过 ${props.maxSize}MB`)
    return
  }

  uploading.value = true
  try {
    const urls: string[] = []
    for (const file of files) {
      urls.push(await defaultUpload(file))
    }
    emit('update:modelValue', [...props.modelValue, ...urls])
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上传失败')
  } finally {
    uploading.value = false
  }
}

function handleRemove(url: string): void {
  emit(
    'update:modelValue',
    props.modelValue.filter((item) => item !== url)
  )
}

function fileName(url: string): string {
  return url.split('/').pop() ?? url
}
</script>

<template>
  <div class="base-uploader">
    <input ref="fileInput" type="file" hidden :accept="accept" multiple @change="handleChange" />

    <div v-if="preview === 'image'" class="base-uploader-list image-list">
      <div v-for="url in modelValue" :key="url" class="image-item">
        <img :src="url" :alt="fileName(url)" />
        <button type="button" class="image-remove" @click="handleRemove(url)">×</button>
      </div>
      <el-button v-if="modelValue.length < limit" :loading="uploading" @click="handleClick">
        {{ uploading ? '上传中…' : '+ 上传' }}
      </el-button>
    </div>

    <template v-else-if="preview === 'file'">
      <ul class="base-uploader-list file-list">
        <li v-for="url in modelValue" :key="url">
          <a :href="url" target="_blank" rel="noopener">{{ fileName(url) }}</a>
          <el-button link type="danger" size="small" @click="handleRemove(url)">删除</el-button>
        </li>
      </ul>
      <el-button v-if="modelValue.length < limit" :loading="uploading" @click="handleClick">
        {{ uploading ? '上传中…' : '+ 上传附件' }}
      </el-button>
    </template>

    <el-button v-else :loading="uploading" @click="handleClick">
      {{ uploading ? '上传中…' : `+ 上传（${modelValue.length}/${limit}）` }}
    </el-button>
  </div>
</template>

<style scoped>
.base-uploader-list.image-list {
  display: flex;
  flex-wrap: wrap;
  gap: var(--spacing-sm);
  align-items: flex-start;
}

.image-item {
  position: relative;
  width: 96px;
  height: 96px;
  border-radius: var(--radius-md);
  overflow: hidden;
  border: 1px solid var(--color-border);
}

.image-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.image-remove {
  position: absolute;
  top: 0;
  right: 0;
  width: 20px;
  height: 20px;
  border: none;
  cursor: pointer;
  color: #fff;
  background-color: rgba(0, 0, 0, 0.5);
  border-radius: 0 0 0 var(--radius-sm);
  line-height: 1;
}

.file-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-xs);
  margin-bottom: var(--spacing-sm);
}

.file-list li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}
</style>
