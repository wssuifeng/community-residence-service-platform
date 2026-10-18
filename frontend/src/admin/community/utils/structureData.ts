export { loadAllPages, StaleStructureLoad } from '@/common/web/utils/paged'

/** 每个单元的分页顺序读取，单元之间限制并发，避免大社区请求风暴。 */
export async function mapLimited<T, R>(items: T[], load: (item: T) => Promise<R>, limit = 4): Promise<R[]> {
  const result: R[] = new Array(items.length)
  let next = 0
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const index = next++
      result[index] = await load(items[index]!)
    }
  }))
  return result
}
