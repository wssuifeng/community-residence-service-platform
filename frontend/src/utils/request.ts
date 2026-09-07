import axios, { AxiosError } from 'axios'
import type { AxiosInstance, AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import type { ApiResult } from '@/types/api'
import { getToken, clearSession } from './auth'

/** 业务错误：统一错误处理路径抛出，页面可 catch 后做定向提示 */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number

  constructor(code: number, message: string, httpStatus: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

const instance: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 15000
})

instance.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  (response) => {
    const result = response.data as ApiResult
    // 业务错误（HTTP 200 + code != 200，接口设计.md §3.1）
    if (result.code !== 200) {
      return Promise.reject(new ApiError(result.code, result.message, response.status))
    }
    return response
  },
  (error: AxiosError<ApiResult>) => {
    // HTTP 层错误：401 令牌失效 → 清理会话跳登录；其余转换 ApiError
    const status = error.response?.status ?? 0
    if (status === 401) {
      clearSession()
      if (!location.pathname.startsWith('/auth/login')) {
        location.href = `/auth/login?redirect=${encodeURIComponent(location.pathname + location.search)}`
      }
      return Promise.reject(new ApiError(401, '令牌已失效，请重新登录', 401))
    }
    const body = error.response?.data
    const message = body?.message ?? httpErrorMessage(status)
    return Promise.reject(new ApiError(body?.code ?? status, message, status))
  }
)

function httpErrorMessage(status: number): string {
  switch (status) {
    case 400:
      return '请求参数不合法'
    case 403:
      return '无权访问'
    case 404:
      return '资源不存在'
    case 0:
      return '网络异常，请检查连接'
    default:
      return `服务异常（${status}）`
  }
}

/** 请求并解包统一响应，直接返回业务 data */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await instance.request<ApiResult<T>>(config)
  return response.data.data
}

export const http = {
  get<T>(url: string, params?: object): Promise<T> {
    return request<T>({ method: 'GET', url, params })
  },
  post<T>(url: string, data?: unknown): Promise<T> {
    return request<T>({ method: 'POST', url, data })
  },
  put<T>(url: string, data?: unknown): Promise<T> {
    return request<T>({ method: 'PUT', url, data })
  },
  patch<T>(url: string, data?: unknown): Promise<T> {
    return request<T>({ method: 'PATCH', url, data })
  },
  delete<T>(url: string): Promise<T> {
    return request<T>({ method: 'DELETE', url })
  }
}

export default instance
