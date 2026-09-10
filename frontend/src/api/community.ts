/** C1 社区基础信息管理接口（接口设计.md §9.1） */
import { http } from '@/utils/request'
import type { PageResult } from '@/types/api'
import type {
  IBuilding,
  IBuildingDTO,
  IBuildingQuery,
  ICommunity,
  ICommunityQuery,
  ICreateCommunityDTO,
  ICreateTimeslotDTO,
  IHouse,
  IHouseDTO,
  IHouseQuery,
  IHouseStatusHistory,
  IPublicResource,
  IPublicResourceDTO,
  IPublicResourceQuery,
  IResourceTimeslot,
  ITimeslotQuery,
  IUnit,
  IUnitDTO,
  IUpdateCommunityStatusDTO,
  IUpdateHouseStatusDTO,
  IUpdateTimeslotDTO
} from '@/types/modules/community'

/* ---------------------------------- 9.1.1 社区管理 ---------------------------------- */

/** 创建社区（接口设计.md 9.1.1.1） */
export function createCommunity(data: ICreateCommunityDTO) {
  return http.post<ICommunity>('/communities', data)
}

/** 更新社区（接口设计.md 9.1.1.2） */
export function updateCommunity(id: number, data: ICreateCommunityDTO) {
  return http.put<ICommunity>(`/communities/${id}`, data)
}

/** 查询社区详情（接口设计.md 9.1.1.3） */
export function getCommunity(id: number) {
  return http.get<ICommunity>(`/communities/${id}`)
}

/** 社区分页列表（接口设计.md 9.1.1.4） */
export function getCommunityList(params: ICommunityQuery) {
  return http.get<PageResult<ICommunity>>('/communities', params)
}

/** 更新社区状态（接口设计.md 9.1.1.5） */
export function updateCommunityStatus(id: number, data: IUpdateCommunityStatusDTO) {
  return http.patch<null>(`/communities/${id}/status`, data)
}

/** 删除社区（需求 v1.1 R1/R6：仅超管，级联删除下级结构与关联业务数据；端点由 50 阶段步骤 1b 后端并行交付） */
export function deleteCommunity(id: number) {
  return http.delete<null>(`/communities/${id}`)
}

/* ---------------------------------- 9.1.2 楼栋管理 ---------------------------------- */

/** 创建楼栋（接口设计.md 9.1.2.1） */
export function createBuilding(data: IBuildingDTO) {
  return http.post<IBuilding>('/buildings', data)
}

/** 更新楼栋（接口设计.md 9.1.2.2） */
export function updateBuilding(id: number, data: IBuildingDTO) {
  return http.put<IBuilding>(`/buildings/${id}`, data)
}

/** 删除楼栋（接口设计.md 9.1.2.3） */
export function deleteBuilding(id: number) {
  return http.delete<null>(`/buildings/${id}`)
}

/** 查询楼栋详情（接口设计.md 9.1.2.4） */
export function getBuilding(id: number) {
  return http.get<IBuilding>(`/buildings/${id}`)
}

/** 社区楼栋分页列表（接口设计.md 9.1.2.5） */
export function getBuildingList(communityId: number, params?: IBuildingQuery) {
  return http.get<PageResult<IBuilding>>(`/communities/${communityId}/buildings`, params)
}

/* ---------------------------------- 9.1.3 单元管理 ---------------------------------- */

/** 创建单元（接口设计.md 9.1.3.1） */
export function createUnit(data: IUnitDTO) {
  return http.post<IUnit>('/units', data)
}

/** 更新单元（接口设计.md 9.1.3.2） */
export function updateUnit(id: number, data: IUnitDTO) {
  return http.put<IUnit>(`/units/${id}`, data)
}

/** 删除单元（接口设计.md 9.1.3.3） */
export function deleteUnit(id: number) {
  return http.delete<null>(`/units/${id}`)
}

/** 查询单元详情（接口设计.md 9.1.3.4） */
export function getUnit(id: number) {
  return http.get<IUnit>(`/units/${id}`)
}

/** 楼栋单元分页列表（接口设计.md 9.1.3.5） */
export function getUnitList(buildingId: number, params?: { page?: number; size?: number }) {
  return http.get<PageResult<IUnit>>(`/buildings/${buildingId}/units`, params)
}

/* ---------------------------------- 9.1.4 房屋管理 ---------------------------------- */

/** 创建房屋（接口设计.md 9.1.4.1） */
export function createHouse(data: IHouseDTO) {
  return http.post<IHouse>('/houses', data)
}

/** 更新房屋（接口设计.md 9.1.4.2） */
export function updateHouse(id: number, data: IHouseDTO) {
  return http.put<IHouse>(`/houses/${id}`, data)
}

/** 删除房屋（接口设计.md 9.1.4.3） */
export function deleteHouse(id: number) {
  return http.delete<null>(`/houses/${id}`)
}

/** 查询房屋详情（接口设计.md 9.1.4.4） */
export function getHouse(id: number) {
  return http.get<IHouse>(`/houses/${id}`)
}

/** 单元房屋分页列表（接口设计.md 9.1.4.5） */
export function getHouseList(unitId: number, params?: IHouseQuery) {
  return http.get<PageResult<IHouse>>(`/units/${unitId}/houses`, params)
}

/** 更新房屋状态（接口设计.md 9.1.4.6） */
export function updateHouseStatus(id: number, data: IUpdateHouseStatusDTO) {
  return http.patch<null>(`/houses/${id}/status`, data)
}

/** 房屋状态变更历史（接口设计.md 9.1.4.7） */
export function getHouseStatusHistory(id: number, params?: { page?: number; size?: number }) {
  return http.get<PageResult<IHouseStatusHistory>>(`/houses/${id}/status-history`, params)
}

/* ---------------------------------- 9.1.5 公共资源管理 ---------------------------------- */

/** 创建公共资源（接口设计.md 9.1.5.1） */
export function createResource(data: IPublicResourceDTO) {
  return http.post<IPublicResource>('/resources', data)
}

/** 更新公共资源（接口设计.md 9.1.5.2） */
export function updateResource(id: number, data: IPublicResourceDTO) {
  return http.put<IPublicResource>(`/resources/${id}`, data)
}

/** 删除公共资源（接口设计.md 9.1.5.3） */
export function deleteResource(id: number) {
  return http.delete<null>(`/resources/${id}`)
}

/** 查询公共资源详情（接口设计.md 9.1.5.4） */
export function getResource(id: number) {
  return http.get<IPublicResource>(`/resources/${id}`)
}

/** 社区公共资源分页列表（接口设计.md 9.1.5.5） */
export function getResourceList(communityId: number, params?: IPublicResourceQuery) {
  return http.get<PageResult<IPublicResource>>(`/communities/${communityId}/resources`, params)
}

/* ---------------------------------- 9.1.6 资源时段管理 ---------------------------------- */

/** 创建资源时段（接口设计.md 9.1.6.1） */
export function createTimeslot(resourceId: number, data: ICreateTimeslotDTO) {
  return http.post<IResourceTimeslot>(`/resources/${resourceId}/timeslots`, data)
}

/** 更新资源时段（接口设计.md 9.1.6.2） */
export function updateTimeslot(id: number, data: IUpdateTimeslotDTO) {
  return http.put<IResourceTimeslot>(`/timeslots/${id}`, data)
}

/** 删除资源时段（接口设计.md 9.1.6.3） */
export function deleteTimeslot(id: number) {
  return http.delete<null>(`/timeslots/${id}`)
}

/** 资源时段分页列表（接口设计.md 9.1.6.4） */
export function getTimeslotList(resourceId: number, params?: ITimeslotQuery) {
  return http.get<PageResult<IResourceTimeslot>>(`/resources/${resourceId}/timeslots`, params)
}
