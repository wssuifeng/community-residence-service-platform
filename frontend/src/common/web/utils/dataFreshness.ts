import { shallowRef } from 'vue'
export type DataDomain = 'community' | 'resident' | 'workorder' | 'lease' | 'housing' | 'feedback' | 'notice' | 'reservation' | 'evaluation' | 'conversation' | 'messaging' | 'statistics' | 'payment' | 'agreement' | 'system'
export type DataScope = DataDomain | { domain: DataDomain; id?: number | string }
interface Invalidation { revision: number; scopes: readonly DataScope[] }
export const dataInvalidation = shallowRef<Invalidation>({ revision: 0, scopes: [] })
const normalized = (scope: DataScope) => typeof scope === 'string' ? { domain: scope } : scope
export function scopesOverlap(a: DataScope, b: DataScope): boolean {
 const x = normalized(a), y = normalized(b)
 return x.domain === y.domain && (x.id == null || y.id == null || String(x.id) === String(y.id))
}
/** 仅传递有归属的失效信息，不保存业务数据或跨身份缓存。 */
export function invalidateData(...scopes: DataScope[]) {
 if (scopes.length) dataInvalidation.value = { revision: dataInvalidation.value.revision + 1, scopes }
}
const resources: Record<string, DataDomain[]> = {
 'work-order-attachments':['workorder'], 'feedback-attachments':['feedback','conversation'], 'housing-timeslots':['housing'], 'structures':['community','housing'], 'lease-agreements':['agreement','lease','conversation'], 'resource-reservations':['reservation','statistics'], 'timeslots':['reservation'],
 'work-orders': ['workorder','statistics','conversation'], 'service-categories':['workorder'], 'staff':['workorder'], 'staff-schedules':['workorder'], 'staff-capabilities':['workorder'],
 'communities':['community','housing','resident'], 'buildings':['community','housing'], 'units':['community','housing'], 'houses':['community','housing'], 'house-images':['community','housing'],
 'residents':['resident'], 'residence-relations':['resident','community'], 'residence-applications':['resident','housing','lease','conversation'],
 'leases':['lease','housing','resident','conversation'], 'lease-extensions':['lease'], 'lease-move-out-applications':['lease','housing','resident'], 'lease-payments':['payment','lease'], 'lease-deposit-refunds':['payment','lease'],
 'agreements':['agreement','lease','conversation'], 'agreement-templates':['agreement'], 'housings':['housing','community'], 'viewing-appointments':['housing','conversation'],
 'feedbacks':['feedback','conversation'], 'notices':['notice'], 'resources':['reservation'], 'public-resources':['reservation'], 'reservations':['reservation','statistics'], 'evaluations':['evaluation','workorder'],
 'conversations':['conversation'], 'conversation-inbox':['conversation'], 'notifications':['messaging'], 'config':['system'], 'configs':['system'], 'sys-users':['system'], 'payment':['payment','lease'], 'payment-channels':['payment']
}
export function mutationScopes(url: string): DataScope[] {
 const path = url.replace(/^.*?\/api\/v1\//,'/').split('?')[0]
 const [resource = '', id, action] = path.split('/').filter(Boolean)
 if (['auth','upload'].includes(resource) || resource === 'notices' && action === 'views') return []
 const domains = resources[resource] ?? ['system']
 return domains.map(domain => ({ domain, id: ['work-orders','conversations','feedbacks','notices','notifications'].includes(resource) && domains[0] === domain && /^\d+$/.test(id ?? '') ? id : undefined }))
}
export function invalidateMutation(url: string) { invalidateData(...mutationScopes(url)) }
export function invalidateNotification(item: { sourceType: string; sourceId: number | null }) {
 const type = item.sourceType ?? ''
 const domain: DataDomain | undefined = type.startsWith('LEASE') ? 'lease' : type.startsWith('VIEWING') ? 'housing' : ({ WORK_ORDER:'workorder', FEEDBACK:'feedback', NOTICE:'notice', RESERVATION:'reservation', RESOURCE_RESERVATION:'reservation', RESIDENCE_APPLICATION:'resident', CONVERSATION:'conversation' } as Record<string,DataDomain>)[type]
 invalidateData('messaging', ...(domain ? [{ domain, id: ['workorder','feedback','notice'].includes(domain) ? item.sourceId ?? undefined : undefined } as DataScope] : []), ...(domain && ['workorder','feedback','lease','housing'].includes(domain) ? ['conversation' as const] : []))
}
