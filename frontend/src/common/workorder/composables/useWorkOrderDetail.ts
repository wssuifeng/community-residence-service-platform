import { onBeforeUnmount, ref, watch, type Ref } from 'vue'
import { useUserStore } from '@/common/auth/store/user'
import { getWorkOrder, getWorkOrderTimeline, listWorkOrderAttachments } from '@/common/workorder/api/workorder'
import type { IWorkOrder, IWorkOrderAttachment, IWorkOrderProcess } from '@/common/workorder/types/workorder'

/** 主数据成功即可操作，辅助读取独立失败；工单或身份切换后旧响应失效。 */
export function useWorkOrderDetail(id: Readonly<Ref<number>>, onLoaded?: () => void | Promise<void>) {
 const user = useUserStore()
 const order = ref<IWorkOrder | null>(null), timeline = ref<IWorkOrderProcess[]>([]), attachments = ref<IWorkOrderAttachment[]>([])
 const loading = ref(false), timelineLoading = ref(false), attachmentsLoading = ref(false)
 const loadError = ref(''), timelineError = ref(''), attachmentsError = ref('')
 let version = 0, disposed = false, timelineVersion = 0, attachmentsVersion = 0
 const identity = () => `${user.role}:${user.user?.id ?? ''}`
 const current = (v: number, target: number, owner: string) => !disposed && v === version && target === id.value && owner === identity()
 async function fetchTimeline(): Promise<void> {
  const v = version, target = id.value, owner = identity(), attempt = ++timelineVersion
  timelineLoading.value = true; timelineError.value = ''
  try { const result = await getWorkOrderTimeline(target); if (current(v,target,owner) && attempt === timelineVersion) timeline.value = result }
  catch (e) { if (current(v,target,owner) && attempt === timelineVersion) timelineError.value = e instanceof Error ? e.message : '时间线加载失败' }
  finally { if (current(v,target,owner) && attempt === timelineVersion) timelineLoading.value = false }
 }
 async function fetchAttachments(): Promise<void> {
  const v = version, target = id.value, owner = identity(), attempt = ++attachmentsVersion
  attachmentsLoading.value = true; attachmentsError.value = ''
  try { const result = await listWorkOrderAttachments(target); if (current(v,target,owner) && attempt === attachmentsVersion) attachments.value = result }
  catch (e) { if (current(v,target,owner) && attempt === attachmentsVersion) attachmentsError.value = e instanceof Error ? e.message : '附件加载失败' }
  finally { if (current(v,target,owner) && attempt === attachmentsVersion) attachmentsLoading.value = false }
 }
 async function fetchDetail(): Promise<void> {
  const v = ++version, target = id.value, owner = identity()
  order.value = null; timeline.value = []; attachments.value = []
  loading.value = true; loadError.value = ''; timelineError.value = ''; attachmentsError.value = ''
  timelineLoading.value = false; attachmentsLoading.value = false
  if (!Number.isSafeInteger(target) || target <= 0) { loadError.value = '工单编号无效'; loading.value = false; return }
  try {
   const result = await getWorkOrder(target)
   if (!current(v,target,owner)) return
   order.value = result
   void fetchTimeline(); void fetchAttachments()
   void Promise.resolve(onLoaded?.()).catch(() => { /* 各端辅助业务自行显示失败，不影响主详情。 */ })
  } catch (e) { if (current(v,target,owner)) loadError.value = e instanceof Error ? e.message : '工单详情加载失败' }
  finally { if (current(v,target,owner)) loading.value = false }
 }
 watch([id, identity], () => { void fetchDetail() }, { immediate: true })
 onBeforeUnmount(() => { disposed = true; version++ })
 return { order, timeline, attachments, loading, loadError, timelineLoading, timelineError, attachmentsLoading, attachmentsError, fetchDetail, fetchTimeline, fetchAttachments }
}
