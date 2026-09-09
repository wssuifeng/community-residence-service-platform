/**
 * 通用文件上传类型（后端 FileUploadService.UploadResult 契约，
 * 接口设计.md §5.1）
 */

/** 上传文件分类：图片（≤5MB jpg/jpeg/png/gif）/ 文档（≤10MB pdf/doc/docx/txt） */
export type UploadFileType = 'IMAGE' | 'DOCUMENT'

/** 通用上传结果 */
export interface IUploadResult {
  fileId: string
  fileName: string
  /** 可直接访问的相对路径（/uploads/**，生产由后端静态托管） */
  fileUrl: string
  fileSize: number
  fileType: UploadFileType
}
