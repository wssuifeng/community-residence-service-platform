package com.community.residence.common.conversation.constant;

/**
 * 会话类型常量（conversation.type，R63 多方会话 + R68 租约群聊 + R74 工单临时群聊）。
 * VIEWING_GROUP 挂看房预约（related_id=viewing_appointment.id），
 * LEASE_GROUP 挂租约（related_id=lease_record.id，R68），
 * WORK_ORDER_GROUP 挂工单（related_id=work_order.id，R74），
 * DIRECT 为居民-社区管理员直通（related_id 空，幂等由应用层按参与者+社区判定）。
 */
public final class ConversationType {

    public static final String VIEWING_GROUP = "VIEWING_GROUP";

    /** 租约群聊（R68）：审核通过自动建群，申请取消或租约归档即关闭 */
    public static final String LEASE_GROUP = "LEASE_GROUP";

    /** 工单临时群聊（R74）：管理端受理后建群，派单后服务人员入群，终审关闭即回收（只读留痕） */
    public static final String WORK_ORDER_GROUP = "WORK_ORDER_GROUP";

    public static final String DIRECT = "DIRECT";

    private ConversationType() {
    }
}
