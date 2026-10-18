package com.community.residence.common.workorder.service;
import java.util.List;
/** 沿用V7既有默认目录，仅新社区/完全空目录初始化；不覆盖自定义或停用类别。 */
public final class DefaultServiceCategories {
 public record Item(String name,String description) { }
 public static final List<Item> ITEMS=List.of(
  new Item("室内维修","门窗、灯具、家具等室内设施维修"),new Item("公共设施报修","社区公共设备设施故障上报"),
  new Item("公共区域卫生","公共区域保洁与垃圾清运"),new Item("水电管道","供水供电与管道问题"),
  new Item("电梯楼道","电梯故障与楼道设施问题"),new Item("安全隐患上报","消防、治安等安全隐患"),new Item("其他","以上类别未覆盖的服务事项"));
 private DefaultServiceCategories() { }
}
