package com.community.residence.common.constant;

/**
 * 会话生命周期状态常量（conversation.status，R68 租约群聊）。
 * ACTIVE 进行中（停收停发仅在 CLOSED）；CLOSED 已关闭（只读留档，发消息被拒 5604）。
 */
public final class ConversationStatus {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";

    private ConversationStatus() {
    }
}
