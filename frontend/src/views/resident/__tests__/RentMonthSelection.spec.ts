import { mount,flushPromises } from '@vue/test-utils'
import { describe,it,expect,vi,beforeEach } from 'vitest'
import ElementPlus from 'element-plus'
import LeaseRenewDialog from '../LeaseRenewDialog.vue'
const api=vi.hoisted(()=>({quote:vi.fn(),channels:vi.fn(),create:vi.fn(),close:vi.fn(),status:vi.fn()}))
vi.mock('@/api/payment',()=>({getRentQuote:api.quote,listPaymentChannels:api.channels,createRentPayment:api.create,createRenewPayment:api.create,closePayment:api.close,getPaymentStatus:api.status}))
const lease={id:1,status:'ACTIVE',startDate:'2026-10-15',endDate:'2027-10-14',monthlyRent:3600} as any
beforeEach(()=>{vi.clearAllMocks();api.channels.mockResolvedValue([{channel:'ALIPAY',enabled:true}]);api.quote.mockImplementation((_id,_purpose,months)=>Promise.resolve({months,remainingMonths:11,quoteVersion:'q'+months,amount:months*3600,periodStart:'2026-11-15',periodEnd:'2027-10-14'}))})
async function open(){const w=mount(LeaseRenewDialog,{props:{modelValue:false,lease,purpose:'RENT'},global:{plugins:[ElementPlus],stubs:{ElDialog:{props:['modelValue'],template:'<section v-if="modelValue"><slot/><slot name="footer"/></section>'}}}});await w.setProps({modelValue:true});await flushPromises();return w}
describe('自主缴租月份',()=>{
 it('上限读后端余月，修改月份重新报价',async()=>{const w=await open();const input=w.findComponent({name:'ElInputNumber'});expect(input.props('max')).toBe(11);input.vm.$emit('update:modelValue',4);input.vm.$emit('change',4);await flushPromises();expect(api.quote).toHaveBeenLastCalledWith(1,'RENT',4);expect(w.text()).toContain('本次4个月');w.unmount()})
 it('有效旧单不能修改月数，明确继续原单，不暗中重复下单',async()=>{api.quote.mockResolvedValue({status:'PENDING',months:3,paymentNo:'LOCAL-TEST',amount:10800});const w=await open();expect(w.findComponent({name:'ElInputNumber'}).props('disabled')).toBe(true);expect(w.text()).toContain('已有进行中支付单');expect(api.create).not.toHaveBeenCalled();w.unmount()})
})
