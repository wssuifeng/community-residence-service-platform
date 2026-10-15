import { http } from '@/utils/request'
export interface HouseImage { id: number; houseId: number; storageKey: string; kind: 'PHOTO' | 'FLOOR_PLAN'; caption: string; sortOrder: number }
export const listHouseImages = (id: number) => http.get<HouseImage[]>(`/houses/${id}/images`)
export function uploadHouseImage(id: number, file: File, kind: HouseImage['kind']) {
 const data = new FormData(); data.append('file', file); data.append('kind', kind)
 return http.post<HouseImage>(`/houses/${id}/images`, data)
}
export const saveHouseImages = (id: number, images: HouseImage[]) => http.patch<HouseImage[]>(`/houses/${id}/images`, images.map(({id,kind,caption})=>({id,kind,caption})))
export const removeHouseImage = (id: number, imageId: number) => http.delete<void>(`/houses/${id}/images/${imageId}`)
