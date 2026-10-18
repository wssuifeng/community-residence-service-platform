package com.community.residence.common.security.context;

import com.community.residence.common.messaging.constant.NotificationOwnerType;

import java.security.Principal;

/**
 * WebSocket 会话主体：主体名带身份空间前缀，居民为 {@code R:{residentId}}、
 * 员工/管理员为 {@code U:{sysUserId}}（DEF-067）。
 * 存在理由：Spring 的 {@code convertAndSendToUser} 按 {@code Principal#getName()}
 * 匹配会话（UserDestinationResolver），而 resident.id 与 sys_user.id 可撞号
 * （resident 1 与 sys_user 1）——若主体名同为 "1"，点对点通知会同时投递给
 * 居民与员工两个会话；带前缀后两者目的地天然不同。
 * 同时保留 Long 型 userId，供订阅鉴权拦截器按业务主键比对参与者。
 */
public final class IdentityPrincipal implements Principal {

    /** 员工/管理员主体名前缀（sys_user.id） */
    public static final String PREFIX_USER = "U:";

    /** 居民主体名前缀（resident.id） */
    public static final String PREFIX_RESIDENT = "R:";

    private final String ownerType;
    private final Long userId;
    private final String name;

    private IdentityPrincipal(String ownerType, Long userId) {
        this.ownerType = ownerType;
        this.userId = userId;
        this.name = subjectName(ownerType, userId);
    }

    public static IdentityPrincipal of(String ownerType, Long userId) {
        return new IdentityPrincipal(ownerType, userId);
    }

    /** 由 CONNECT 帧令牌角色构造（RESIDENT → R: 前缀，其余 → U: 前缀） */
    public static IdentityPrincipal ofRole(String role, Long userId) {
        return new IdentityPrincipal(NotificationOwnerType.ofRole(role), userId);
    }

    /** 主体名：与 NotificationService 推送目标、WebSocketSessionService 在线键同源生成 */
    public static String subjectName(String ownerType, Long userId) {
        return (NotificationOwnerType.RESIDENT.equals(ownerType) ? PREFIX_RESIDENT : PREFIX_USER) + userId;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getOwnerType() {
        return ownerType;
    }

    public Long getUserId() {
        return userId;
    }
}
