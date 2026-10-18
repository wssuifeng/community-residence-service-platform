import { mount,flushPromises } from '@vue/test-utils'
import { describe,it,expect,vi } from 'vitest'
import NoticeEditor from '@/common/notice/components/NoticeEditor.vue'
import NoticeBody from '@/common/notice/components/NoticeBody.vue'
import { EditorContent } from '@tiptap/vue-3'
import { legacyNoticeDocument } from '@/common/notice/utils/noticeDocument'
vi.mock('@/common/upload/api/upload',()=>({uploadFile:vi.fn().mockResolvedValue({fileUrl:'/uploads/editor-photo.png'})}))
const doc={type:'doc',content:[{type:'paragraph',content:[{type:'text',text:'社区通知'}]}]}
describe('公告正文主编辑器与共用渲染',()=>{
 it('以真实可编辑文档替代纯文本输入，保留加粗/居中/列表结构',async()=>{
  const w=mount(NoticeEditor,{props:{modelValue:JSON.stringify(doc)},attachTo:document.body});await flushPromises()
  expect(w.find('[contenteditable="true"]').exists()).toBe(true);expect(w.find('textarea').exists()).toBe(false)
  const editor=w.getComponent(EditorContent).props('editor')!;editor.commands.setTextSelection({from:1,to:5});editor.chain().toggleBold().setTextAlign('center').run();await flushPromises()
  const result=String(w.emitted('update:modelValue')?.at(-1)?.[0]);expect(result).toContain('bold');expect(result).toContain('center')
  const body=mount(NoticeBody,{props:{bodyJson:result}});expect(body.find('strong').text()).toBe('社区通知');expect(body.find('p').attributes('style')).toContain('center');body.unmount();w.unmount()
 })
 it('安全渲染拒绝脚本节点，文字不作为HTML执行，旧纯文本图片仍兼容',()=>{
  const w=mount(NoticeBody,{props:{bodyJson:JSON.stringify({type:'doc',content:[{type:'script',text:'evil'},{type:'paragraph',content:[{type:'text',text:'<img onerror="evil">'}]},{type:'image',attrs:{src:'javascript:evil',widthPercent:100}}]})}})
  expect(w.find('script').exists()).toBe(false);expect(w.find('img').exists()).toBe(false);expect(w.text()).toContain('<img onerror="evil">');w.unmount()
  const legacy=mount(NoticeBody,{props:{content:'旧正文\n第二段',images:['/uploads/legacy.png']}});expect(legacy.findAll('p')).toHaveLength(2);expect(legacy.find('img').attributes('src')).toBe('/uploads/legacy.png');legacy.unmount()
 })
 it('本地图片进入正文JSON，半宽居中按结构渲染而不是独立URL表单',()=>{
  const image={type:'image',attrs:{src:'/uploads/a.png',alt:'活动现场',widthPercent:50,textAlign:'center'}}
  const w=mount(NoticeBody,{props:{bodyJson:JSON.stringify({type:'doc',content:[image]})}});expect(w.find('img').attributes('style')).toContain('50%');expect(w.find('figure').attributes('style')).toContain('center');expect(w.find('figcaption').text()).toBe('活动现场');w.unmount()
 })
 it('旧公告进入编辑器保留现有图片引用，不静默删图',()=>{const legacy=legacyNoticeDocument('旧文',['/uploads/a.png']);expect(legacy.content?.at(-1)?.attrs?.src).toBe('/uploads/a.png')})
})
