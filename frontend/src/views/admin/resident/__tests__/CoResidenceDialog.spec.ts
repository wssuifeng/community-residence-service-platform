import { mount,flushPromises } from '@vue/test-utils'
import { describe,it,expect,vi,beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import Dialog from '../ResidentCoResidenceDialog.vue'
import { findCoResidentCandidates,getResidenceDependents,createResidenceRelation,adminCreateResident } from '@/api/resident'
vi.mock('@/api/resident',()=>({findCoResidentCandidates:vi.fn(),getResidenceDependents:vi.fn(),createResidenceRelation:vi.fn(),adminCreateResident:vi.fn()}))
const anchor={id:10,residentId:1,residentName:'主住户',communityId:5,houseId:100,houseLocation:'1栋1单元101',relationType:'OWNER',moveInDate:'2026-10-01',moveOutDate:null,status:'ACTIVE',createdAt:''} as any
async function open(){const w=mount(Dialog,{props:{modelValue:false,anchor},global:{plugins:[ElementPlus],stubs:{ElDialog:{template:'<div><slot/><slot name="footer"/></div>'}}}});await w.setProps({modelValue:true});await flushPromises();return w}
function save(w:ReturnType<typeof mount>){return w.findAll('button').find(b=>b.text()==='登记同住关系')!}
describe('管理员登记独立账号的依赖居住关系',()=>{
 beforeEach(()=>{vi.clearAllMocks();vi.mocked(getResidenceDependents).mockResolvedValue([]);vi.mocked(findCoResidentCandidates).mockResolvedValue([{id:2,username:'成员',realName:'成员'}])})
 it('已有账号明确绑定本户主关系而不创建账号或租约',async()=>{const w=await open();const selectors=w.findAllComponents({name:'ElSelect'});selectors[0]!.vm.$emit('update:modelValue',2);await flushPromises();await save(w).trigger('click');await flushPromises();expect(createResidenceRelation).toHaveBeenCalledWith(expect.objectContaining({residentId:2,houseId:100,anchorRelationId:10,relationType:'FAMILY'}));expect(adminCreateResident).not.toHaveBeenCalled();expect(w.emitted('saved')).toHaveLength(1);w.unmount()})
 it('主关系核对失败阻止提交并保留错误',async()=>{vi.mocked(getResidenceDependents).mockRejectedValue(new Error('权限拒绝'));const w=await open();expect(w.text()).toContain('权限拒绝');expect(save(w).attributes('disabled')).toBeDefined();expect(createResidenceRelation).not.toHaveBeenCalled();w.unmount()})
 it('界面明确账号独立且主关系结束依赖关系同步结束',async()=>{const w=await open();expect(w.text()).toContain('代建独立账号');expect(w.text()).toContain('主居住关系结束时，同住关系同步结束');w.unmount()})
})
