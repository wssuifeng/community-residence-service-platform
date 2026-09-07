package com.community.residence.common.constant;

/**
 * 系统角色常量（接口设计.md §7.1 五角色定义）。
 * Spring Security 的 hasRole 断言会自动加 ROLE_ 前缀，GrantedAuthority 统一为 ROLE_{code}。
 */
public final class RoleConstants {

    public static final String GUEST = "GUEST";
    public static final String RESIDENT = "RESIDENT";
    public static final String STAFF = "STAFF";
    public static final String ADMIN = "ADMIN";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    /** sys_user 表 role 字段合法值（SUPER_ADMIN/ADMIN/STAFF） */
    public static final String[] SYSTEM_ROLES = {SUPER_ADMIN, ADMIN, STAFF};

    private RoleConstants() {
    }
}
