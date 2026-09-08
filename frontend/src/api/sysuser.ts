import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IChangePasswordDTO,
  ICreateSysUserDTO,
  ISysUser,
  ISysUserQuery,
  IUpdateSysUserDTO,
  IUpdateSysUserStatusDTO,
  IUserCommunityBinding,
  IUserCommunityBindingResult
} from '@/types/modules/auth'

/** C10 系统用户管理接口（接口设计.md §9.10.1） */

/** 9.10.1.3 创建系统用户 */
export function createSysUser(data: ICreateSysUserDTO) {
  return http.post<ISysUser>('/sys-users', data)
}

/** 9.10.1.4 更新系统用户 */
export function updateSysUser(id: number, data: IUpdateSysUserDTO) {
  return http.put<ISysUser>(`/sys-users/${id}`, data)
}

/** 9.10.1.5 查询系统用户 */
export function getSysUser(id: number) {
  return http.get<ISysUser>(`/sys-users/${id}`)
}

/** 9.10.1.6 系统用户列表（分页） */
export function getSysUserList(params: ISysUserQuery) {
  return http.get<PageResult<ISysUser>>('/sys-users', params)
}

/** 9.10.1.7 冻结/解冻账号 */
export function updateSysUserStatus(id: number, data: IUpdateSysUserStatusDTO) {
  return http.patch<null>(`/sys-users/${id}/status`, data)
}

/** 9.10.1.8 修改密码 */
export function changePassword(data: IChangePasswordDTO) {
  return http.patch<null>('/sys-users/password', data)
}

/** 9.10.2.1 绑定社区 */
export function bindCommunity(userId: number, communityId: number) {
  return http.post<IUserCommunityBindingResult>(`/sys-users/${userId}/communities`, { communityId })
}

/** 9.10.2.2 解绑社区 */
export function unbindCommunity(userId: number, communityId: number) {
  return http.delete<null>(`/sys-users/${userId}/communities/${communityId}`)
}

/** 9.10.2.3 管理员绑定社区列表 */
export function getUserCommunities(userId: number) {
  return http.get<IUserCommunityBinding[]>(`/sys-users/${userId}/communities`)
}
