import { http } from '@/common/web/utils/request'

export interface IAlipayChannelConfig {
  appId: string | null
  applicationPublicKey: string | null
  alipayPublicKey: string | null
  environment: 'SANDBOX' | 'PRODUCTION'
  privateKeyConfigured: boolean
  configured: boolean
  enabled: boolean
  revision: number
  updatedAt: string | null
}

export interface IUpdateAlipayChannelConfig {
  appId: string
  applicationPublicKey: string
  merchantPrivateKey: string
  alipayPublicKey: string
  environment: IAlipayChannelConfig['environment']
  enabled: boolean
  expectedRevision: number
  clearPrivateKey: boolean
}

/** 独立超管接口；响应没有私钥或密文，保存不发起渠道请求。 */
export function getAlipayChannelConfig() {
  return http.get<IAlipayChannelConfig>('/payment/channel-config/alipay')
}
export function saveAlipayChannelConfig(data: IUpdateAlipayChannelConfig) {
  return http.put<IAlipayChannelConfig>('/payment/channel-config/alipay', data)
}
