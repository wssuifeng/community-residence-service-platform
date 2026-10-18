import { describe, expect, it } from 'vitest'
import { HOUSING_WEEKDAY_OPTIONS } from '@/admin/housing/utils/housingWeekdayOptions'

describe('HOUSING_WEEKDAY_OPTIONS', () => {
  it('uses the backend ISO weekday range and keeps Sunday representable', () => {
    expect(HOUSING_WEEKDAY_OPTIONS).toEqual([
      { label: '周一', value: 1 },
      { label: '周二', value: 2 },
      { label: '周三', value: 3 },
      { label: '周四', value: 4 },
      { label: '周五', value: 5 },
      { label: '周六', value: 6 },
      { label: '周日', value: 7 }
    ])
    expect(HOUSING_WEEKDAY_OPTIONS.find((option) => option.label === '周日')?.value).toBe(7)
  })
})
