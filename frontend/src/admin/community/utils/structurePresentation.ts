import { houseStatusLabels, type HouseStatus } from '@/common/community/types/community'
export const ICONS = {
  home: ['M3 11l9-8 9 8', 'M5 9.7V20a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9.7', 'M10 21v-6h4v6'],
  search: ['M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14z', 'M20 20l-4.2-4.2']
}

export const ACTION_ICONS = {
  plus: ['M12 5v14', 'M5 12h14'],
  pen: ['M12 20h9', 'M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z'],
  trash: [
    'M3 6h18',
    'M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2',
    'M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6',
    'M10 11v6',
    'M14 11v6'
  ],
  swap: ['M4 8h13', 'M14 5l3 3-3 3', 'M20 16H7', 'M10 13l-3 3 3 3'],
  layers: ['M12 3l8 4.5-8 4.5-8-4.5L12 3', 'M4 12.5L12 17l8-4.5', 'M4 16.5L12 21l8-4.5'],
  /** 卡片角标「更多」：三个圆点（round linecap 把零长度线段画成点） */
  more: ['M6 12h.01', 'M12 12h.01', 'M18 12h.01'],
  eye: [
    'M2 12s3.6-6 10-6 10 6 10 6-3.6 6-10 6-10-6-10-6z',
    'M12 9.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 0 1 0-5z'
  ]
}

export const HOUSE_BLOCK_CLASS: Record<HouseStatus, string> = {
  OCCUPIED: 'is-occupied',
  VACANT: 'is-vacant',
  RESERVED: 'is-reserved',
  MAINTENANCE: 'is-maintenance'
}

export const LEGEND_ITEMS = (['OCCUPIED', 'VACANT', 'RESERVED', 'MAINTENANCE'] as HouseStatus[]).map(
  (value) => ({ value, label: houseStatusLabels[value] })
)

export function houseStatusTagType(status: HouseStatus): 'info' | 'completed' | 'pending' | 'processing' {
  if (status === 'OCCUPIED') return 'completed'
  if (status === 'RESERVED') return 'pending'
  if (status === 'MAINTENANCE') return 'processing'
  return 'info'
}
