export interface NoticeNode { type:string; text?:string; attrs?:Record<string,unknown>; marks?:Array<{type:string}>; content?:NoticeNode[] }
export const emptyNoticeDocument=():NoticeNode=>({type:'doc',content:[{type:'paragraph'}]})
export function legacyNoticeDocument(content:string,images:string[]=[]):NoticeNode {
 return {type:'doc',content:[...content.split('\n').map(text=>({type:'paragraph',...(text?{content:[{type:'text',text}]}:{})})),...images.map(src=>({type:'image',attrs:{src,alt:'',widthPercent:100,textAlign:'left'}}))]}
}
/** 只生成项目白名单字段，Tiptap扩展的内部默认属性不直接成为存储契约。 */
export function canonicalNoticeDocument(node:NoticeNode):NoticeNode {
 const result:NoticeNode={type:node.type}
 if(node.type==='text'){result.text=node.text??'';if(node.marks?.some(m=>m.type==='bold'))result.marks=[{type:'bold'}]}
 if(node.content)result.content=node.content.map(canonicalNoticeDocument)
 if(node.type==='paragraph'||node.type==='heading'){result.attrs={textAlign:node.attrs?.textAlign??null};if(node.type==='heading')result.attrs.level=2}
 if(node.type==='orderedList')result.attrs={start:node.attrs?.start??1}
 if(node.type==='image')result.attrs={src:node.attrs?.src??'',alt:node.attrs?.alt??'',widthPercent:node.attrs?.widthPercent??100,textAlign:node.attrs?.textAlign??'left'}
 return result
}
export function noticeSummary(node:NoticeNode):{text:string;images:string[]} {
 const parts:string[]=[],images:string[]=[]
 function visit(n:NoticeNode){if(n.type==='text')parts.push(n.text??'');if(n.type==='image')images.push(String(n.attrs?.src??''));n.content?.forEach(visit);if(['paragraph','heading','hardBreak','listItem'].includes(n.type))parts.push('\n')}
 visit(node);return {text:parts.join('').trim()||(images.length?'[图片公告]':''),images}
}
