package com.community.residence.common.constant;

/**
 * 通知归属主体类型（notification.owner_type，DEF-067 身份空间分离）。
 * resident 表与 sys_user 表是两套独立身份空间且主键可撞号，单靠 user_id
 * 无法确定收件人，故由 (ownerType, userId) 二元组共同标识收件人身份。
 * 该常量同时决定 WebSocket 用户目的地主体名前缀（见 IdentityPrincipal），
 * 保证居民与员工永不落到同一 destination。
 */
public final class NotificationOwnerType {

    /** sys_user 主体（STAFF / ADMIN / SUPER_ADMIN 均为员工或管理侧身份空间） */
    public static final String USER = "USER";

    /** resident 主体（居民身份空间） */
    public static final String RESIDENT = "RESIDENT";

    private NotificationOwnerType() {
    }

    /**
     * 由角色推断所属身份空间：RESIDENT 角色主体取自 resident 表，其余角色
     * （STAFF/ADMIN/SUPER_ADMIN）均取自 sys_user 表；未知/空角色归入 USER，
     * 与 HTTP 侧 JwtAuthenticationFilter 的令牌吊销分支口径一致
     * （仅 RESIDENT 走居民吊销表，其余走后台账号吊销表）。
     */
    public static String ofRole(String role) {
        return RoleConstants.RESIDENT.equals(role) ? RESIDENT : USER;
    }
}
