/** 通用选项类型（下拉框/单选框） */
export interface Option<T = string | number> {
  label: string
  value: T
  disabled?: boolean
}

/** 树形节点（服务类别树/社区-楼栋级联） */
export interface TreeNode<T = unknown> {
  id: number
  label: string
  children?: TreeNode<T>[]
  data?: T
}
