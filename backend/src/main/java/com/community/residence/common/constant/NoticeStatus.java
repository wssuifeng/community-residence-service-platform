package com.community.residence.common.constant;

/** 公告状态常量（notice.status，状态机：DRAFT → PUBLISHED → WITHDRAWN） */
public final class NoticeStatus {

    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String WITHDRAWN = "WITHDRAWN";

    private NoticeStatus() {
    }
}
