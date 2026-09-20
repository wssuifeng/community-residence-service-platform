package com.community.residence.common.constant;

/**
 * 会话类型常量（conversation.type，R63 多方会话）。
 * VIEWING_GROUP 挂看房预约（related_id=viewing_appointment.id），
 * DIRECT 为居民-社区管理员直通（related_id 空，幂等由应用层按参与者+社区判定）。
 */
public final class ConversationType {

    public static final String VIEWING_GROUP = "VIEWING_GROUP";
    public static final String DIRECT = "DIRECT";

    private ConversationType() {
    }
}
