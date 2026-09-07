import { http } from '@/utils/request'

/** 全局配置（接口设计.md 9.2.4.1 响应 data） */
export interface ISystemConfig {
  key: string
  value: string
  description: string
}

/** 按配置键查询全局配置（接口设计.md 9.2.4.1，公开，如注册开关 registration.enabled） */
export function getConfig(key: string) {
  return http.get<ISystemConfig>(`/configs/${key}`)
}

/** 查询居民注册开关是否开启（接口设计.md 9.2.4.1，键 registration.enabled） */
export async function isRegistrationEnabled() {
  const config = await getConfig('registration.enabled')
  return config.value === 'true'
}
