import { http } from '@/utils/request'
import type { IUploadResult, UploadFileType } from '@/types/modules/upload'

/**
 * 通用文件上传（接口设计.md §5.1）：图片 ≤5MB（jpg/jpeg/png/gif）、
 * 文档 ≤10MB（pdf/doc/docx/txt）；type 与扩展名不符后端拒绝
 */

/** 上传文件，返回含可访问 fileUrl 的结果（/uploads/**） */
export function uploadFile(file: File, type: UploadFileType): Promise<IUploadResult> {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('type', type)
  return http.post<IUploadResult>('/upload', formData)
}
