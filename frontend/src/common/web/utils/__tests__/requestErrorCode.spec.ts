import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import instance, { ApiError, http } from '@/common/web/utils/request'
import type { ApiResult } from '@/common/web/types/api'

/**
 * DEF-099 前端适配回归（接口设计.md §3.3 标准错误码）。
 *
 * 背景：标准错误码落地后，框架级请求错误（405 方法不支持 / 415 内容类型不支持 /
 * 400 请求体不可读 / 404 路由不存在 / 403 越权）不再被后端包成 500，而是返回
 * **语义正确的 HTTP 状态码 + 统一 ApiResponse 响应体**。
 * 本组用例锁死 axios 拦截器的三条适配契约：
 * ① 非 200 响应也能读出**标准业务码**（405/415/400/403/4001）与可读 message；
 * ② 提示语不得退化为「网络异常」（仅 status === 0 才是真网络异常）；
 * ③ 401 仍走既有清会话跳登录语义（安全行为不回退）。
 *
 * 实现方式：直接替换 axios 实例的 adapter（不发真实请求、不引新依赖），
 * 由用例给出状态码与响应体，走完整响应拦截器链路。
 */
let nextResponse: { status: number; data?: ApiResult | string } | null = null
let networkError = false

const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
  if (networkError) {
    // axios 真网络故障：无 response，status 记为 0
    const error = new Error('Network Error') as Error & { config?: unknown; request?: unknown }
    error.config = config
    throw error
  }
  const status = nextResponse?.status ?? 200
  const response: AxiosResponse = {
    data: nextResponse?.data,
    status,
    statusText: String(status),
    headers: {},
    config
  }
  if (status >= 400) {
    // axios 对非 2xx 一律 reject（settle 语义），此处复刻以便命中错误拦截器
    const error = new Error(`Request failed with status code ${status}`) as Error & {
      response?: AxiosResponse
      config?: unknown
      isAxiosError?: boolean
    }
    error.response = response
    error.config = config
    error.isAxiosError = true
    throw error
  }
  return response
}

function payload(code: number, message: string): ApiResult {
  return { code, message, data: null, timestamp: '2026-09-23T10:00:00' }
}

function reply(status: number, body?: ApiResult | string) {
  nextResponse = { status, data: body }
}

describe('框架级请求错误的前端适配（DEF-099 / 标准错误码）', () => {
  beforeEach(() => {
    nextResponse = null
    networkError = false
    instance.defaults.adapter = adapter
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('405 方法不支持：读出标准码 405 与后端 message，不退化为网络异常', async () => {
    reply(405, payload(405, '请求方法不被支持'))

    const error = await http.get('/service-categories').catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    const apiError = error as ApiError
    expect(apiError.httpStatus).toBe(405)
    expect(apiError.code).toBe(405)
    expect(apiError.message).toBe('请求方法不被支持')
    expect(apiError.message).not.toContain('网络异常')
  })

  it('415 内容类型不支持：读出标准码 415 与后端 message', async () => {
    reply(415, payload(415, '请求内容类型不被支持'))

    const error = await http.post('/service-categories', 'plain-text').catch((e: unknown) => e)

    expect((error as ApiError).httpStatus).toBe(415)
    expect((error as ApiError).code).toBe(415)
    expect((error as ApiError).message).toBe('请求内容类型不被支持')
  })

  it('400 请求体不可读：读出 400 与可行动提示', async () => {
    reply(400, payload(400, '请求体缺失或格式不正确，请提交合法的 JSON 请求体'))

    const error = await http.post('/service-categories', '{ bad json').catch((e: unknown) => e)

    expect((error as ApiError).httpStatus).toBe(400)
    expect((error as ApiError).code).toBe(400)
    expect((error as ApiError).message).toContain('合法的 JSON')
  })

  it('404 路由不存在：读出标准码 4001（而非仅 HTTP 404）', async () => {
    reply(404, payload(4001, '接口不存在'))

    const error = await http.get('/not-exists').catch((e: unknown) => e)

    expect((error as ApiError).httpStatus).toBe(404)
    expect((error as ApiError).code).toBe(4001)
    expect((error as ApiError).message).toBe('接口不存在')
  })

  it('403 越权未被改坏：仍为 403 且提示无权访问', async () => {
    reply(403, payload(403, '无权限访问'))

    const error = await http.get('/admin-only').catch((e: unknown) => e)

    expect((error as ApiError).httpStatus).toBe(403)
    expect((error as ApiError).code).toBe(403)
    expect((error as ApiError).message).toBe('无权限访问')
  })

  it('响应体不可读时退回本地语义文案：405/415 不再是「服务异常」笼统提示', async () => {
    reply(405)

    const error = await http.get('/service-categories').catch((e: unknown) => e)

    expect((error as ApiError).httpStatus).toBe(405)
    expect((error as ApiError).code).toBe(405)
    expect((error as ApiError).message).toBe('请求方法不被支持')
  })

  it('真网络故障（status = 0）仍提示网络异常——不与框架级错误混淆', async () => {
    networkError = true

    const error = await http.get('/network-down').catch((e: unknown) => e)

    expect((error as ApiError).httpStatus).toBe(0)
    expect((error as ApiError).message).toBe('网络异常，请检查连接')
  })

  it('401 仍清理会话并跳转登录（安全行为不回退）', async () => {
    const locationStub = { pathname: '/resident/work-orders', search: '', href: '' }
    vi.stubGlobal('location', locationStub)
    reply(401, payload(401, '未认证或令牌已失效'))

    const error = await http.get('/secure').catch((e: unknown) => e)

    expect((error as ApiError).httpStatus).toBe(401)
    expect((error as ApiError).message).toBe('令牌已失效，请重新登录')
    expect(locationStub.href).toContain('/auth/login')
  })
})
