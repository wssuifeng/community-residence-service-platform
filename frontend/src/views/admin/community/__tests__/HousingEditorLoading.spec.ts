import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import HousesWithHousingView from '../HousesWithHousingView.vue'
import { getHousingDetail, updateHousing, createHousing } from '@/api/housing'
import { getCommunityList } from '@/api/community'
import { http } from '@/utils/request'
vi.mock('vue-router',()=>({useRouter:()=>({push:vi.fn()})}))
vi.mock('@/api/community',()=>({getCommunityList:vi.fn(),getBuildingList:vi.fn(),getUnitList:vi.fn()}))
vi.mock('@/api/housing',()=>({getHousingDetail:vi.fn(),updateHousing:vi.fn(),createHousing:vi.fn(),updateHousingStatus:vi.fn()}))
vi.mock('@/utils/request',()=>({http:{get:vi.fn(),post:vi.fn()}}))
const record={houseId:1,communityId:1,communityName:'合成社区',buildingName:'一栋',unitName:'一单元',houseNumber:'101',floor:1,houseStatus:'VACANT',housing:{id:2,title:'合成挂牌',status:'AVAILABLE',monthlyRent:1000,deposit:2000,rentType:'RENT',occupancy:'FREE'}}
async function open(){const w=mount(HousesWithHousingView,{global:{plugins:[ElementPlus],directives:{permission:()=>{}},stubs:{ElDialog:{props:['modelValue'],template:'<div v-if="modelValue"><slot /><slot name="footer" /></div>'}}}});await flushPromises();await w.findAll('button').find(b=>b.text()==='编辑房源')!.trigger('click');return w}
function save(w:ReturnType<typeof mount>){return w.findAll('button').find(b=>b.text()==='保存')!}
describe('挂牌编辑加载门禁',()=>{
 beforeEach(()=>{vi.clearAllMocks();vi.mocked(getCommunityList).mockResolvedValue({records:[],total:0} as any);vi.mocked(http.get).mockResolvedValue({records:[record],total:1} as any)})
 it('加载完成前禁保存，响应到达后回显真实详情',async()=>{let done!:(v:any)=>void;vi.mocked(getHousingDetail).mockReturnValue(new Promise(resolve=>{done=resolve}));const w=await open();expect(save(w).attributes('disabled')).toBeDefined();done({title:'真实标题',description:'真实描述',monthlyRent:1200,deposit:2400,rentType:'RENT',images:['/uploads/test.png']});await flushPromises();expect(save(w).attributes('disabled')).toBeUndefined();expect(w.get('textarea').element.value).toBe('真实描述');w.unmount()})
 it('加载失败不允许用空值保存，重试后恢复',async()=>{vi.mocked(getHousingDetail).mockRejectedValueOnce(new Error('详情加载失败')).mockResolvedValueOnce({title:'真实',description:'已恢复',monthlyRent:1200,rentType:'RENT',images:[]} as any);const w=await open();await flushPromises();expect(save(w).attributes('disabled')).toBeDefined();expect(updateHousing).not.toHaveBeenCalled();await w.findAll('button').find(b=>b.text()==='重新加载房源')!.trigger('click');await flushPromises();expect(save(w).attributes('disabled')).toBeUndefined();w.unmount()})
 it('关闭后迟到响应不会覆盖重新打开的详情',async()=>{let done!:(v:any)=>void;vi.mocked(getHousingDetail).mockReturnValueOnce(new Promise(resolve=>{done=resolve})).mockResolvedValueOnce({title:'新的',description:'新窗口',monthlyRent:1200,rentType:'RENT',images:[]} as any);const w=await open();await w.findAll('button').find(b=>b.text()==='取消')!.trigger('click');await w.findAll('button').find(b=>b.text()==='编辑房源')!.trigger('click');await flushPromises();done({title:'旧的',description:'旧窗口',monthlyRent:1000,rentType:'RENT',images:[]});await flushPromises();expect(w.get('textarea').element.value).toBe('新窗口');w.unmount()})
})

it('单套挂牌提供与批量相同的看房配置，一次提交创建',async()=>{
 vi.mocked(getCommunityList).mockResolvedValue({records:[],total:0} as any)
 vi.mocked(http.get).mockResolvedValue({records:[{...record,housing:null}],total:1} as any)
 const w=mount(HousesWithHousingView,{global:{plugins:[ElementPlus],directives:{permission:()=>{}},stubs:{ElDialog:{props:['modelValue'],template:'<div v-if="modelValue"><slot /><slot name="footer" /></div>'}}}});await flushPromises()
 await w.findAll('button').find(b=>b.text()==='挂牌')!.trigger('click');await flushPromises()
 expect(w.text()).toContain('同时配置可预约看房时段');expect(w.text()).toContain('周一至周五 09:00～12:00')
 const rent=w.findAllComponents({name:'ElInputNumber'})[0]!;rent.vm.$emit('update:modelValue',1000);await flushPromises()
 await save(w).trigger('click');await flushPromises()
 expect(createHousing).toHaveBeenCalledWith(expect.objectContaining({houseId:1,createTimeslots:true,timeslotMode:'DEFAULT'}));w.unmount()
})
