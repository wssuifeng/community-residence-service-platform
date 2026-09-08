import { http } from '@/utils/request'

/** 查询配置响应（接口设计.md 9.2.4.1，后端返回 {key, value} 包装） */
export interface IConfigValue {
  key: string
  value: string
}

/** 按配置键查询全局配置（接口设计.md 9.2.4.1，公开，如注册开关 registration.enabled） */
export function getConfig(key: string) {
  return http.get<IConfigValue>(`/configs/${key}`)
}

/** 查询居民注册开关是否开启（接口设计.md 9.2.4.1，键 registration.enabled） */
export async function isRegistrationEnabled() {
  const config = await getConfig('registration.enabled')
  return config.value === 'true'
}
