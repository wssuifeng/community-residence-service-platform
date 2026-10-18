import { reactive } from 'vue'
import { afterEach,beforeEach,describe,expect,it,vi } from 'vitest'
import { flushPromises,mount,type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import FeedbackChat from '@/common/conversation/components/FeedbackChat.vue'
const api=vi.hoisted(()=>({list:vi.fn(),send:vi.fn(),image:vi.fn(),subscribe:vi.fn()}))
vi.mock('@/common/feedback/api/feedback',()=>({listFeedbackMessages:api.list,sendFeedbackMessage:api.send}))
vi.mock('@/common/conversation/api/chat',()=>({sendChatImage:api.image}))
vi.mock('@/common/messaging/utils/websocket',()=>({subscribe:api.subscribe,isWsConnected:()=>false}))
const user=reactive({role:'RESIDENT'})
vi.mock('@/common/auth/store/user',()=>({useUserStore:()=>user}))
const message=(id=1)=>({id,feedbackId:3,senderId:null,senderType:'RESIDENT',senderName:'匿名居民',content:'[图片]',messageType:'IMAGE',image:{fileUrl:'/uploads/real.png',fileName:'照片.png',fileSize:100},createdAt:'2026-10-15T08:00:00'})
let wrapper:VueWrapper|undefined
async function render(closed=false){wrapper=mount(FeedbackChat,{props:{feedbackId:3,closed,initialContent:'反馈描述',initialName:'匿名居民'},global:{plugins:[ElementPlus]},attachTo:document.body});await flushPromises();return wrapper}
beforeEach(()=>{user.role='RESIDENT';api.list.mockResolvedValue([message()]);api.subscribe.mockReturnValue(vi.fn());api.image.mockResolvedValue(message(2));api.send.mockResolvedValue({...message(3),messageType:'TEXT',image:null,content:'回复'})})
afterEach(()=>{wrapper?.unmount();wrapper=undefined;vi.clearAllMocks();document.body.innerHTML=''})
describe('反馈复用聊天底座与图片消息',()=>{
 it('居民端显示消息图片而非侧栏附件',async()=>{await render();expect(wrapper!.find('img[src="/uploads/real.png"]').exists()).toBe(true);expect(wrapper!.text()).toContain('匿名居民');expect(wrapper!.text()).not.toContain('删除')})
 it('管理员端也显示同一真实图片',async()=>{user.role='ADMIN';await render();expect(wrapper!.find('img[src="/uploads/real.png"]').exists()).toBe(true);expect(wrapper!.findAll('.chat-message.mine')).toHaveLength(0)})
 it('文件选择直接发送图片消息，只有一次上传',async()=>{await render();const input=wrapper!.get('input[type=file]');Object.defineProperty(input.element,'files',{value:[new File(['x'],'test.png',{type:'image/png'})]});await input.trigger('change');await flushPromises();expect(api.image).toHaveBeenCalledTimes(1);expect(api.image.mock.calls[0].slice(0,2)).toEqual(['feedbacks',3]);expect(wrapper!.findAll('.chat-image')).toHaveLength(2)})
 it('实时推送图片按ID去重',async()=>{await render();const callback=api.subscribe.mock.calls[0][1];callback({type:'FEEDBACK_MESSAGE',data:message(2)});callback({type:'FEEDBACK_MESSAGE',data:message(2)});await flushPromises();expect(wrapper!.findAll('.chat-image')).toHaveLength(2)})
 it('关闭会话图片只读，禁止新的发送控件',async()=>{await render(true);expect(wrapper!.find('input[type=file]').exists()).toBe(false);expect(wrapper!.find('textarea').exists()).toBe(false);expect(wrapper!.findAll('.chat-image')).toHaveLength(1);expect(api.subscribe).not.toHaveBeenCalled()})
 it('切换反馈清旧消息并读取新会话，不能把老反馈图片带过去',async()=>{await render();api.list.mockResolvedValue([]);await wrapper!.setProps({feedbackId:4});await flushPromises();expect(api.list).toHaveBeenLastCalledWith(4);expect(wrapper!.findAll('.chat-image')).toHaveLength(0)})
})
