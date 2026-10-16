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

describe('租金订单实际双截止',()=>{
 it('读取订单真实截止到秒，不把旧单硬编码为新时长',async()=>{
  api.create.mockResolvedValue({paymentNo:'RENT-OFFLINE',status:'PENDING',channel:'WECHAT',months:1,amount:3600,channelExpiresAt:'2026-10-16T18:00:00',expiresAt:'2026-10-16T18:05:00'});
  api.status.mockResolvedValue({paymentNo:'RENT-OFFLINE',status:'PENDING',channel:'WECHAT',months:1,amount:3600,channelExpiresAt:'2026-10-16T18:00:00',expiresAt:'2026-10-16T18:05:00'});
  const w=await open();await w.findAll('button').find(b=>b.text().includes('去支付'))!.trigger('click');await flushPromises();
  expect(w.text()).toContain('付款截止：2026-10-16 18:00:00');expect(w.text()).toContain('系统订单到期：2026-10-16 18:05:00');
  w.unmount();
 });
});
