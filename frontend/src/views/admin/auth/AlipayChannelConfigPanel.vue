<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { getAlipayChannelConfig, saveAlipayChannelConfig } from '@/api/paymentChannelConfig'
import type { IAlipayChannelConfig } from '@/api/paymentChannelConfig'

const formRef = ref<FormInstance>()
const loading = ref(false)
const saving = ref(false)
const ready = ref(false)
const privateKeyConfigured = ref(false)
const configured = ref(false)
const savedEnabled = ref(false)
const savedEnvironment = ref<IAlipayChannelConfig['environment']>('SANDBOX')
const revision = ref(0)
const form = reactive({
  appId: '', environment: 'SANDBOX' as IAlipayChannelConfig['environment'],
  applicationPublicKey: '', merchantPrivateKey: '', alipayPublicKey: '', clearPrivateKey: false, enabled: false
})
const rules: FormRules = {
  appId: [{ required: true, pattern: /^[0-9]{16,32}$/, message: '请输入16~32位数字 APPID', trigger: 'blur' }],
  applicationPublicKey: [{ required: true, whitespace: true, message: '请填写应用公钥', trigger: 'blur' }],
  alipayPublicKey: [{ required: true, whitespace: true, message: '请填写支付宝公钥', trigger: 'blur' }]
}
function apply(config: IAlipayChannelConfig) {
  form.appId = config.appId ?? ''
  form.environment = config.environment
  form.applicationPublicKey = config.applicationPublicKey ?? ''
  form.alipayPublicKey = config.alipayPublicKey ?? ''
  form.merchantPrivateKey = ''
  form.clearPrivateKey = false
  privateKeyConfigured.value = config.privateKeyConfigured
  configured.value = config.configured
  form.enabled = config.enabled
  savedEnabled.value = config.enabled
  savedEnvironment.value = config.environment
  revision.value = config.revision
}
async function load() {
  if (loading.value || saving.value) return
  loading.value = true
  ready.value = false
  form.merchantPrivateKey = ''
  try {
    apply(await getAlipayChannelConfig())
    ready.value = true
  } catch {
    /* 公共请求层显示错误；加载失败禁止以空快照覆盖服务端配置。 */
  } finally { loading.value = false }
}
async function reload() {
  if (ready.value) {
    try { await ElMessageBox.confirm('重新加载会丢弃尚未保存的修改，是否继续？', '重新加载配置', { type: 'warning' }) }
    catch { return }
  }
  await load()
}
async function save() {
  if (!ready.value || loading.value || saving.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!form.clearPrivateKey && !privateKeyConfigured.value && !form.merchantPrivateKey.trim()) {
    ElMessage.warning('首次配置需要填写应用私钥')
    return
  }
  if (form.clearPrivateKey && form.merchantPrivateKey.trim()) {
    ElMessage.warning('清除私钥与填写新私钥不能同时进行')
    return
  }
  if (form.clearPrivateKey) {
    try { await ElMessageBox.confirm('清除后配置不完整，渠道将停用；再次使用须重新填写应用私钥。是否清除？', '清除已保存私钥', { type: 'warning', confirmButtonText: '确认清除' }) }
    catch { return }
  }
  if (form.enabled && !form.clearPrivateKey && form.environment === 'PRODUCTION' && (!savedEnabled.value || savedEnvironment.value !== 'PRODUCTION')) {
    try { await ElMessageBox.confirm('正式环境可能产生真实资金交易。仅在商户、密钥及环境核对无误后启用；保存本身不会发起交易。', '启用正式支付渠道', { type: 'warning', confirmButtonText: '确认启用正式渠道', cancelButtonText: '取消' }) }
    catch { return }
  }
  saving.value = true
  try {
    apply(await saveAlipayChannelConfig({ ...form, expectedRevision: revision.value }))
    ElMessage.success(savedEnabled.value ? '配置已保存，渠道已启用' : '配置已保存，渠道未启用')
  } catch {
    /* 不自动重试版本冲突；保留非敏感字段，清除敏感输入。 */
  } finally {
    form.merchantPrivateKey = ''
    saving.value = false
  }
}
onMounted(load)
</script>

<template>
  <section class="table-panel alipay-config-panel" aria-labelledby="alipay-config-title" v-loading="loading">
    <header class="panel-heading">
      <div>
        <h3 id="alipay-config-title">支付宝渠道配置</h3>
        <p class="panel-description">APPID 与三份 Key 独立管理，不使用公开的全局键值配置。</p>
      </div>
      <el-tag :type="configured ? 'success' : 'info'">{{ configured ? '配置已保存' : '尚未完整配置' }} · {{ savedEnabled ? '已启用' : '未启用' }}</el-tag>
    </header>
    <el-alert class="payment-paused" title="保存仅进行离线密钥校验，不发起支付、查单或退款。渠道是否开放由下方开关决定，保存后生效。" type="info" :closable="false" show-icon />
    <p v-if="!loading && !ready" class="load-error" role="alert">配置加载失败，保存已禁用。请重新加载，避免覆盖已有配置。</p>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="loading || saving || !ready" class="payment-form" @submit.prevent="save">
      <div class="identity-fields">
        <el-form-item label="APPID" prop="appId">
          <el-input v-model="form.appId" maxlength="32" inputmode="numeric" placeholder="支付宝开放平台的应用 APPID" data-testid="alipay-app-id" />
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="form.environment" aria-label="支付环境" class="environment-select">
            <el-option label="沙箱环境（默认）" value="SANDBOX" />
            <el-option label="正式环境（真实交易）" value="PRODUCTION" />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item label="渠道启用">
        <el-switch v-model="form.enabled" :disabled="form.clearPrivateKey" active-text="启用" inactive-text="停用" aria-label="启用支付宝渠道" data-testid="alipay-enabled" />
        <p class="field-help">启用后允许用户主动发起支付。开关和配置一起保存；清除私钥会自动停用，不需要重新填写已保存的私钥。</p>
      </el-form-item>
      <el-form-item label="应用公钥 · Application Public Key" prop="applicationPublicKey">
        <el-input v-model="form.applicationPublicKey" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" maxlength="8192" placeholder="填写已上传开放平台的应用公钥，支持 PEM 或 Base64" data-testid="alipay-application-public-key" />
        <p class="field-help">用于离线核对应用私钥是否匹配，不是支付宝公钥。</p>
      </el-form-item>
      <el-form-item label="应用私钥 · Application Private Key">
        <el-input v-model="form.merchantPrivateKey" type="password" show-password maxlength="8192" autocomplete="new-password" :placeholder="privateKeyConfigured ? '已保存，不回显；留空保留原私钥' : '首次配置必填，PKCS8 / PEM 或 Base64'" data-testid="alipay-merchant-private-key" />
        <p class="field-help">用于接口加签，加密保存。{{ privateKeyConfigured ? '已保存私钥，重新填写将更换。' : '尚未保存私钥。' }}</p>
        <el-checkbox v-if="privateKeyConfigured" v-model="form.clearPrivateKey" class="clear-private-key">明确清除已保存的应用私钥（保存时再次确认）</el-checkbox>
      </el-form-item>
      <el-form-item label="支付宝公钥 · Alipay Public Key" prop="alipayPublicKey">
        <el-input v-model="form.alipayPublicKey" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" maxlength="8192" placeholder="从支付宝开放平台获取；请勿填入应用公钥" data-testid="alipay-public-key" />
        <p class="field-help">用于支付宝响应验签，必须与当前应用及环境对应。</p>
      </el-form-item>
      <footer class="form-actions">
        <p class="offline-note">离线校验不能证明平台侧 APPID / 公钥归属或交易可用性。版本 {{ revision }}</p>
        <el-button type="primary" :loading="saving" :disabled="!ready || loading" native-type="submit" data-testid="save-alipay-config">保存配置</el-button>
      </footer>
    </el-form>
    <el-button class="reload-button" :disabled="loading || saving" @click="reload">重新加载配置</el-button>
  </section>
</template>

<style scoped>
.alipay-config-panel { margin-bottom: var(--spacing-md); min-width: 0; }
.panel-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--spacing-md); margin-bottom: var(--spacing-md); flex-wrap: wrap; }
.panel-heading h3 { margin: 0; font-size: var(--font-size-md); font-weight: var(--font-weight-medium); }
.panel-description, .field-help, .offline-note { color: var(--color-text-secondary); font-size: var(--font-size-xs); line-height: 1.6; }
.panel-description { margin: 6px 0 0; }
.payment-paused { margin-bottom: var(--spacing-md); }
.payment-form { max-width: 900px; min-width: 0; }
.identity-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: var(--spacing-md); }
.environment-select { width: 100%; }
.field-help { width: 100%; margin: 6px 0 0; overflow-wrap: anywhere; }
.clear-private-key { max-width: 100%; height: auto; margin-top: 8px; }
.clear-private-key :deep(.el-checkbox__label) { white-space: normal; }
.form-actions { display: flex; align-items: center; justify-content: space-between; gap: var(--spacing-md); flex-wrap: wrap; }
.offline-note { margin: 0; flex: 1 1 260px; }
.reload-button { margin-top: var(--spacing-sm); }
.load-error { color: var(--color-danger); font-size: var(--font-size-sm); }
@media (max-width: 600px) { .identity-fields { grid-template-columns: minmax(0, 1fr); gap: 0; } }
</style>
