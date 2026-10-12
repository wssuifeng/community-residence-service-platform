<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadFile } from '@/api/upload'
import type { UploadFileType } from '@/types/modules/upload'

/**
 * 通用上传组件：图片/文件统一入口，经 POST /upload 上传
 * （ImageUploader/FileUploader 分别以 IMAGE/DOCUMENT 类型接入）
 */
const props = withDefaults(
  defineProps<{
    /** 已上传文件 URL 列表（v-model） */
    modelValue: string[]
    /** 上传分类（后端按类型校验扩展名与大小上限） */
    type?: UploadFileType
    /** 接受的文件类型（input accept） */
    accept?: string
    /** 单文件大小上限（MB） */
    maxSize?: number
    /** 最多文件数 */
    limit?: number
    /** 预览模式：image 缩略图 / file 文件行 / none 仅计数 */
    preview?: 'image' | 'file' | 'none'
    disabled?: boolean
  }>(),
  {
    type: 'IMAGE',
    accept: 'image/*',
    maxSize: 5,
    limit: 6,
    preview: 'image',
    disabled: false
  }
)

const emit = defineEmits<{
  'update:modelValue': [urls: string[]]
  'busy-change': [busy: boolean]
}>()

const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const failures = ref<{ name: string; message: string }[]>([])
const previewUrl = ref('')
const previewVisible = ref(false)
let mounted = true
onBeforeUnmount(() => { mounted = false })

/** 上传实现：multipart 表单提交到通用上传接口，返回可访问 fileUrl */
async function doUpload(file: File): Promise<string> {
  const result = await uploadFile(file, props.type)
  return result.fileUrl
}

function handleClick(): void {
  if (!uploading.value && !props.disabled) fileInput.value?.click()
}

async function handleChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''

  if (files.length === 0 || uploading.value || props.disabled) return
  failures.value = []
  if (props.modelValue.length + files.length > props.limit) {
    ElMessage.warning(`最多上传 ${props.limit} 个文件`)
    return
  }
  uploading.value = true
  emit('busy-change', true)
  const urls = [...props.modelValue]
  try {
    for (const file of files) {
      if (!mounted) break
      try {
        if (file.size > props.maxSize * 1024 * 1024) throw new Error(`单个文件不能超过 ${props.maxSize}MB`)
        const url = await doUpload(file)
        if (!mounted) break
        urls.push(url)
        /* 逐张回写：后一张失败不撤销前面已经成功的文件。 */
        emit('update:modelValue', [...urls])
      } catch (error) {
        if (!mounted) break
        const message = error instanceof Error ? error.message : '上传失败'
        failures.value.push({ name: file.name, message })
      }
    }
    if (mounted && failures.value.length) ElMessage.error('部分文件上传失败，已保留成功项；请重新选择失败文件重试')
  } finally {
    if (mounted) {
      uploading.value = false
      emit('busy-change', false)
    }
  }
}

function handleRemove(url: string): void {
  if (uploading.value || props.disabled) return
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
    <input ref="fileInput" type="file" hidden :accept="accept" :disabled="disabled || uploading" multiple @change="handleChange" />

    <div v-if="preview === 'image'" class="base-uploader-list image-list">
      <div v-for="url in modelValue" :key="url" class="image-item">
        <button type="button" class="image-preview" :aria-label="`预览图片 ${fileName(url)}`" @click="previewUrl = url; previewVisible = true"><img :src="url" :alt="fileName(url)" /></button>
        <button type="button" class="image-remove" :aria-label="`移除图片 ${fileName(url)}`" :disabled="disabled || uploading" @click="handleRemove(url)">×</button>
      </div>
      <el-button v-if="modelValue.length < limit" :loading="uploading" :disabled="disabled || uploading" @click="handleClick">
        {{ uploading ? '上传中…' : '+ 上传' }}
      </el-button>
    </div>

    <template v-else-if="preview === 'file'">
      <ul class="base-uploader-list file-list">
        <li v-for="url in modelValue" :key="url">
          <a :href="url" target="_blank" rel="noopener">{{ fileName(url) }}</a>
          <el-button link type="danger" size="small" :disabled="disabled || uploading" @click="handleRemove(url)">删除</el-button>
        </li>
      </ul>
      <el-button v-if="modelValue.length < limit" :loading="uploading" :disabled="disabled || uploading" @click="handleClick">
        {{ uploading ? '上传中…' : '+ 上传附件' }}
      </el-button>
    </template>

    <el-button v-else :loading="uploading" :disabled="disabled || uploading" @click="handleClick">
      {{ uploading ? '上传中…' : `+ 上传（${modelValue.length}/${limit}）` }}
    </el-button>
    <ul v-if="failures.length" class="upload-failures" role="status"><li v-for="(item, index) in failures" :key="index">{{ item.name }}：{{ item.message }}，请重新选择重试。</li></ul>
    <el-dialog v-model="previewVisible" title="图片预览" width="min(800px, calc(100vw - 32px))" append-to-body>
      <img v-if="previewVisible" :src="previewUrl" alt="图片大图预览" class="preview-image" />
    </el-dialog>
  </div>
</template>

<style scoped>
.base-uploader { min-width: 0; width: 100%; }
.image-preview { border: 0; padding: 0; width: 100%; height: 100%; cursor: zoom-in; }
.preview-image { display: block; max-width: 100%; max-height: 70vh; margin: 0 auto; object-fit: contain; }
.upload-failures { margin: 8px 0 0; padding-left: 18px; color: var(--color-danger, #b91c1c); overflow-wrap: anywhere; }
.image-remove:disabled { cursor: not-allowed; opacity: .5; }
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
