package com.community.residence.reservation.service;

import com.community.residence.community.entity.PublicResource;

import java.time.LocalTime;
import java.util.Set;

/**
 * Slot Grid 栅格工具（08 §3.7 设计定案）：预约最小单位对齐与栅格遍历。
 * capacity 语义为「资源级·每时段可承载人数上限」，日容量 = 时段数 × capacity 派生计算。
 */
public final class SlotGrids {

    /** slot_unit 合法取值（15/30/60 分钟，默认 30） */
    public static final Set<Integer> ALLOWED_SLOT_UNITS = Set.of(15, 30, 60);

    public static final int DEFAULT_SLOT_UNIT = 30;

    private SlotGrids() {
    }

    /** 资源栅格粒度（缺省/非法值回落 30 分钟） */
    public static int slotUnitOf(PublicResource resource) {
        Integer unit = resource == null ? null : resource.getSlotUnit();
        return unit != null && unit > 0 ? unit : DEFAULT_SLOT_UNIT;
    }

    /** 资源每时段容量（缺省 1） */
    public static int capacityOf(PublicResource resource) {
        Integer capacity = resource == null ? null : resource.getCapacity();
        return capacity != null && capacity > 0 ? capacity : 1;
    }

    /** 时刻对齐判定：无秒级分量且分钟数为 slot_unit 整数倍 */
    public static boolean isAligned(LocalTime time, int slotUnit) {
        return time.getSecond() == 0 && time.getNano() == 0
                && time.getMinute() % slotUnit == 0;
    }

    /** 向上对齐到最近的栅格边界（存量非对齐模板宽容处理，展示层用） */
    public static LocalTime alignUp(LocalTime time, int slotUnit) {
        LocalTime normalized = time.withSecond(0).withNano(0);
        int minuteOfDay = normalized.getHour() * 60 + normalized.getMinute();
        int remainder = minuteOfDay % slotUnit;
        return remainder == 0 ? normalized : normalized.plusMinutes(slotUnit - remainder);
    }

    /** 栅格标识：模板ID × 10000 + 当日分钟数（栅格槽无独立主键，前端选择键须唯一） */
    public static long gridId(Long templateId, LocalTime gridStart) {
        return templateId * 10000L + gridStart.getHour() * 60L + gridStart.getMinute();
    }
}
