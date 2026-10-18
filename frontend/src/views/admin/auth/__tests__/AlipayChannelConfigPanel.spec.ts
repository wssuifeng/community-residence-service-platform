import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElMessageBox } from 'element-plus'
import AlipayChannelConfigPanel from '../AlipayChannelConfigPanel.vue'
import type { IAlipayChannelConfig } from '@/common/payment/api/paymentChannelConfig'

const api = vi.hoisted(() => ({ getAlipayChannelConfig: vi.fn(), saveAlipayChannelConfig: vi.fn() }))
vi.mock('@/common/payment/api/paymentChannelConfig', () => api)
let wrapper: VueWrapper | undefined
const empty = (): IAlipayChannelConfig => ({ appId: null, applicationPublicKey: null, alipayPublicKey: null, environment: 'SANDBOX', privateKeyConfigured: false, configured: false, enabled: false, revision: 0, updatedAt: null })
const saved = (): IAlipayChannelConfig => ({ ...empty(), appId: '9021000000000000', applicationPublicKey: 'APP_PUBLIC_TEST', alipayPublicKey: 'ALIPAY_PUBLIC_TEST', privateKeyConfigured: true, configured: true, revision: 1 })
async function render() { wrapper = mount(AlipayChannelConfigPanel, { global: { plugins: [ElementPlus] } }); await flushPromises(); return wrapper }
async function fill(w: VueWrapper, secret = 'PRIVATE_TEST_ONLY') {
  await w.get('[data-testid="alipay-app-id"]').setValue('9021000000000000')
  await w.get('[data-testid="alipay-application-public-key"]').setValue('APP_PUBLIC_TEST')
  await w.get('[data-testid="alipay-public-key"]').setValue('ALIPAY_PUBLIC_TEST')
  if (secret) await w.get('[data-testid="alipay-merchant-private-key"]').setValue(secret)
}
async function submit(w: VueWrapper) { await w.get('form').trigger('submit'); await flushPromises() }
beforeEach(() => { api.getAlipayChannelConfig.mockReset().mockResolvedValue(empty()); api.saveAlipayChannelConfig.mockReset().mockResolvedValue(saved()) })
afterEach(() => { wrapper?.unmount(); wrapper=undefined; vi.restoreAllMocks() })

describe('支付宝独立配置表单', () => {
  it('完整显示 APPID 与三份 Key，默认沙箱，启用由超管开关控制', async () => {
    const w=await render(); expect(w.text()).toContain('Application Public Key'); expect(w.text()).toContain('Application Private Key'); expect(w.text()).toContain('Alipay Public Key');
    expect(w.text()).toContain('未启用'); expect(w.findAll('textarea')).toHaveLength(2); expect(w.get('[data-testid="alipay-merchant-private-key"]').attributes('type')).toBe('password');
    expect(w.text()).toContain('不能证明平台侧');
  })
  it('加载已保存配置不回显私钥，留空保留', async () => {
    api.getAlipayChannelConfig.mockResolvedValue(saved()); const w=await render();
    expect((w.get('[data-testid="alipay-merchant-private-key"]').element as HTMLInputElement).value).toBe('');
    expect(w.text()).toContain('已保存私钥'); await submit(w);
    expect(api.saveAlipayChannelConfig).toHaveBeenCalledWith(expect.objectContaining({ merchantPrivateKey: '', expectedRevision: 1 }));
  })
  it('首次保存必须提供私钥', async () => {
    const w=await render(); await fill(w,''); await submit(w); expect(api.saveAlipayChannelConfig).not.toHaveBeenCalled();
  })
  it('只提交四项配置及版本，成功后清空敏感输入', async () => {
    const w=await render(); await fill(w); await submit(w);
    expect(api.saveAlipayChannelConfig).toHaveBeenCalledWith({ appId:'9021000000000000', applicationPublicKey:'APP_PUBLIC_TEST', merchantPrivateKey:'PRIVATE_TEST_ONLY', alipayPublicKey:'ALIPAY_PUBLIC_TEST', environment:'SANDBOX', expectedRevision:0, clearPrivateKey:false, enabled:false });
    expect((w.get('[data-testid="alipay-merchant-private-key"]').element as HTMLInputElement).value).toBe('');
  })
  it('加载失败禁用保存并提供重试', async () => {
    api.getAlipayChannelConfig.mockRejectedValue(new Error('offline')); const w=await render();
    expect(w.text()).toContain('配置加载失败'); expect(w.get('[data-testid="save-alipay-config"]').attributes()).toHaveProperty('disabled');
    await submit(w); expect(api.saveAlipayChannelConfig).not.toHaveBeenCalled();
  })
  it('保存失败保留公钥和 APPID，但清空私钥且不自动重试', async () => {
    api.saveAlipayChannelConfig.mockRejectedValue(new Error('revision conflict')); const w=await render(); await fill(w); await submit(w);
    expect(api.saveAlipayChannelConfig).toHaveBeenCalledTimes(1); expect((w.get('[data-testid="alipay-merchant-private-key"]').element as HTMLInputElement).value).toBe('');
    expect((w.get('[data-testid="alipay-app-id"]').element as HTMLInputElement).value).toBe('9021000000000000');
  })
  it('清除私钥须显式选择与二次确认', async () => {
    api.getAlipayChannelConfig.mockResolvedValue(saved()); vi.spyOn(ElMessageBox,'confirm').mockResolvedValue('confirm' as never); const w=await render();
    await w.get('.clear-private-key input[type="checkbox"]').setValue(true); await submit(w);
    expect(ElMessageBox.confirm).toHaveBeenCalled(); expect(api.saveAlipayChannelConfig).toHaveBeenCalledWith(expect.objectContaining({ clearPrivateKey:true,merchantPrivateKey:'' }));
  })
  it('清除确认取消时不提交', async () => {
    api.getAlipayChannelConfig.mockResolvedValue(saved()); vi.spyOn(ElMessageBox,'confirm').mockRejectedValue('cancel'); const w=await render();
    await w.get('.clear-private-key input[type="checkbox"]').setValue(true); await submit(w); expect(api.saveAlipayChannelConfig).not.toHaveBeenCalled();
  })
})


it('已配置仍停用时显示未启用，开关与配置一起保存且不重填私钥', async () => {
  api.getAlipayChannelConfig.mockResolvedValue(saved())
  api.saveAlipayChannelConfig.mockResolvedValue({ ...saved(), enabled: true, revision: 2 })
  const w = await render()
  expect(w.text()).toContain('配置已保存'); expect(w.text()).toContain('未启用')
  expect(w.text()).not.toContain('交易部分暂缓')
  await w.get('[data-testid="alipay-enabled"]').trigger('click'); await submit(w)
  expect(api.saveAlipayChannelConfig).toHaveBeenCalledWith(expect.objectContaining({ enabled: true, merchantPrivateKey: '' }))
  expect(w.text()).toContain('已启用')
})
