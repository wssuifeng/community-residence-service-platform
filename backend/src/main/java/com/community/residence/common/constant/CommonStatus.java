package com.community.residence.common.constant;

/**
 * 通用生命周期状态常量（多表共用列 status 的取值，
 * 各模块特殊状态见本包内对应常量类）。
 */
public final class CommonStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String INACTIVE = "INACTIVE";
    public static final String FROZEN = "FROZEN";

    private CommonStatus() {
    }
}
