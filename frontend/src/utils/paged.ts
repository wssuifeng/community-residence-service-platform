import type { PageQuery, PageResult } from '@/types/api'

/** 已切换范围的请求不再发下一页，也不能回写新范围。 */
export class StaleStructureLoad extends Error {}

/** 服务端可能收窄 size，必须按返回 total 完整读页，不能把第一页冒充全部。 */
export async function loadAllPages<T>(fetchPage: (query: PageQuery) => Promise<PageResult<T>>, current: () => boolean = () => true): Promise<T[]> {
  const records: T[] = []
  for (let page = 1; ; page++) {
    if (!current()) throw new StaleStructureLoad()
    const result = await fetchPage({ page, size: 100 })
    if (!current()) throw new StaleStructureLoad()
    records.push(...result.records)
    if (records.length >= result.total) return records
    if (result.records.length === 0) throw new Error('结构数据在读取中发生变化，请刷新重试')
  }
}

