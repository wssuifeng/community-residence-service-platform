export interface ChatImage {fileUrl:string;fileName:string;fileSize:number}
export interface ChatMessage {
 id:number;content:string;createdAt:string;senderLabel:string;mine:boolean;
 image?:ChatImage|null;messageType?:'TEXT'|'IMAGE'|'LEASE_AGREEMENT';agreementId?:number|null;senderRole?:string
}
