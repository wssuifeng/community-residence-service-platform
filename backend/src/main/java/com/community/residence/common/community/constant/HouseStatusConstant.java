package com.community.residence.common.community.constant;

/** 房屋实际状态；申请/待签约临时占用由业务记录独立表达。 */
public final class HouseStatusConstant {
    public static final String VACANT = "VACANT";
    public static final String OWNER_OCCUPIED = "OWNER_OCCUPIED";
    public static final String TENANT_OCCUPIED = "TENANT_OCCUPIED";
    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String INACTIVE = "INACTIVE";
    /** 旧代码兼容常量，生产写入必须使用明确的居住类型。 */
    @Deprecated public static final String OCCUPIED = TENANT_OCCUPIED;
    @Deprecated public static final String RESERVED = VACANT;
    public static boolean isOccupied(String status) {
        return OWNER_OCCUPIED.equals(status) || TENANT_OCCUPIED.equals(status);
    }
    public static boolean isManual(String status) {
        return VACANT.equals(status) || MAINTENANCE.equals(status) || INACTIVE.equals(status);
    }
    private HouseStatusConstant() { }
}
