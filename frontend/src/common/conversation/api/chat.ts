import { request } from '@/common/web/utils/request'
/** 三个业务空间沿各自授权端点发送，文件只上传一次，不再回捕URL后另存附件。 */
export function sendChatImage<T>(scope:'feedbacks'|'conversations'|'viewing-appointments',id:number,file:File):Promise<T>{
 const data=new FormData();data.append('file',file)
 return request<T>({method:'POST',url:`/${scope}/${id}/messages/image`,data})
}
