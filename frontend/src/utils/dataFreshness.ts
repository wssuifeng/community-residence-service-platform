import { ref } from 'vue'
/** 只传失效信号，不复制业务数据；列表仍按自身权限和筛选读后端。 */
export const dataRevision = ref(0)
export function invalidateData() { dataRevision.value++ }
