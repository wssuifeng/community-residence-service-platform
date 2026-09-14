<script setup lang="ts">
/** CSV 批量导入居民对话框（DEF-038，R8 v1.2）：模板下载 + 部分成功结果完整展示 */
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { importResidents } from '@/api/resident'
import type { IResidentImportVO } from '@/types/modules/resident'

/**
 * 后端契约：UTF-8 CSV，表头「社区ID,姓名,手机号,证件号」，证件号可空列；
 * 部分成功语义——结果必须完整展示（成功行账号+初始密码可复制、失败行行号+原因）。
 * 后端无文件大小限制，前端 5MB 防呆（超大 CSV 行数也远超人工核对能力）。
 */

const visible = defineModel<boolean>({ required: true })
/** imported 携带导入结果，父层刷新列表与统计卡（失败行不触发也刷新，成功行已生效） */
const emit = defineEmits<{ imported: [result: IResidentImportVO] }>()

const CSV_HEADERS = '社区ID,姓名,手机号,证件号'
const MAX_FILE_SIZE = 5 * 1024 * 1024

const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const result = ref<IResidentImportVO | null>(null)

const canUpload = computed(() => selectedFile.value !== null && !uploading.value)

function downloadTemplate(): void {
  const content = `${CSV_HEADERS}\n1,张三,13800138000,110101199001011234\n`
  const blob = new Blob(['\ufeff' + content], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '居民批量导入模板.csv'
  link.click()
  URL.revokeObjectURL(url)
}

/* el-upload 仅作受控选择器：自行校验大小，不自动上传 */
function handleFileChange(file: UploadFile): void {
  result.value = null
  if (file.raw && file.raw.size > MAX_FILE_SIZE) {
    selectedFile.value = null
    ElMessage.error('CSV 文件不能超过 5MB，请拆分后分批导入')
    return
  }
  selectedFile.value = file.raw ?? null
}

function handleFileRemove(): void {
  selectedFile.value = null
  result.value = null
}

async function handleUpload(): Promise<void> {
  const file = selectedFile.value
  if (!file) return
  uploading.value = true
  try {
    result.value = await importResidents(file)
    emit('imported', result.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导入失败')
  } finally {
    uploading.value = false
  }
}

async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    /* 剪贴板权限被拒：保留手动选择复制，不视为错误 */
    ElMessage.info('复制失败，请手动选择复制')
  }
}

watch(visible, (value) => {
  if (!value) return
  selectedFile.value = null
  result.value = null
})
</script>

<template>
  <el-dialog
    v-model="visible"
    title="批量导入居民"
    width="640px"
    :close-on-click-modal="false"
  >
    <!-- 上传段：模板下载 + 单文件选择 -->
    <div class="import-toolbar">
      <el-button text type="primary" @click="downloadTemplate">下载导入模板</el-button>
      <span class="toolbar-hint">UTF-8 编码 CSV，表头：{{ CSV_HEADERS }}；证件号可留空</span>
    </div>
    <el-upload
      drag
      :limit="1"
      accept=".csv"
      :auto-upload="false"
      :on-change="handleFileChange"
      :on-remove="handleFileRemove"
    >
      <div class="upload-hint">
        <p>将 CSV 文件拖到此处，或点击选择</p>
        <p class="upload-sub">单个文件，不超过 5MB；初始密码为手机号后 6 位</p>
      </div>
    </el-upload>

    <!-- 结果段：部分成功语义，成功/失败完整展示 -->
    <div v-if="result" class="import-result">
      <div class="result-summary">
        <span>总计 <b>{{ result.total }}</b> 行</span>
        <span class="is-success">成功 {{ result.success }} 行</span>
        <span :class="result.fail > 0 ? 'is-danger' : 'is-muted'">失败 {{ result.fail }} 行</span>
      </div>

      <template v-if="result.successRows.length > 0">
        <p class="result-title">成功明细（初始密码仅本次展示，请及时转交居民）</p>
        <el-table :data="result.successRows" size="small" max-height="220">
          <el-table-column prop="row" label="行号" width="70" />
          <el-table-column prop="username" label="用户名" min-width="140">
            <template #default="{ row }">
              <code class="cell-code">{{ row.username }}</code>
            </template>
          </el-table-column>
          <el-table-column label="初始密码" min-width="140">
            <template #default="{ row }">
              <code class="cell-code">{{ row.initialPassword }}</code>
            </template>
          </el-table-column>
          <el-table-column label="" width="60">
            <template #default="{ row }">
              <el-button
                text
                type="primary"
                size="small"
                @click="copyText(`${row.username} ${row.initialPassword}`)"
              >
                复制
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="result.failRows.length > 0">
        <p class="result-title is-danger">失败明细（可修正后重新导入失败行）</p>
        <el-table :data="result.failRows" size="small" max-height="160">
          <el-table-column prop="row" label="行号" width="70" />
          <el-table-column prop="reason" label="失败原因" min-width="300" />
        </el-table>
      </template>
    </div>

    <template #footer>
      <el-button @click="visible = false">
        {{ result ? '关闭' : '取消' }}
      </el-button>
      <el-button
        v-if="!result"
        type="primary"
        :disabled="!canUpload"
        :loading="uploading"
        @click="handleUpload"
      >
        {{ uploading ? '导入中' : '开始导入' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.import-toolbar {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  margin-bottom: var(--spacing-sm);
}

.toolbar-hint {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
}

.upload-hint {
  padding: var(--spacing-lg) 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  text-align: center;
}

.upload-hint p {
  margin: 0;
}

.upload-sub {
  margin-top: var(--spacing-xs) !important;
  font-size: var(--font-size-xs);
}

.import-result {
  margin-top: var(--spacing-md);
}

.result-summary {
  display: flex;
  align-items: center;
  gap: var(--spacing-lg);
  margin-bottom: var(--spacing-sm);
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
}

.result-summary b {
  color: var(--color-text-primary);
}

.result-summary .is-success {
  color: var(--color-success);
}

.result-summary .is-danger {
  color: var(--color-danger);
}

.result-summary .is-muted {
  color: var(--color-text-disabled);
}

.result-title {
  margin: var(--spacing-sm) 0 var(--spacing-xs);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-secondary);
}

.result-title.is-danger {
  color: var(--color-danger);
}

.cell-code {
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  word-break: break-all;
}
</style>
