-- =============================================================================
-- Flyway Migration V1: Create All Tables
-- =============================================================================
-- Description: 社区居住服务管理系统数据库初始化脚本
-- Author: AI (Claude Code)
-- Date: 2026-09-06
-- Dependencies: 数据库设计.md v1.0
-- Database: MySQL 8.x / 9.x
-- Character Set: utf8mb4 / Collation: utf8mb4_unicode_ci
-- =============================================================================

-- =============================================================================
-- C1: 社区基础信息管理（7 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. community - 社区信息表
-- -----------------------------------------------------------------------------
CREATE TABLE community (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '社区ID',
    name VARCHAR(100) NOT NULL COMMENT '社区名称',
    address VARCHAR(255) NOT NULL COMMENT '详细地址',
    contact_phone VARCHAR(20) NULL COMMENT '联系电话',
    contact_person VARCHAR(50) NULL COMMENT '联系人',
    description TEXT NULL COMMENT '社区简介',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-运营中, INACTIVE-已停用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区信息表';

-- -----------------------------------------------------------------------------
-- 2. building - 楼栋信息表
-- -----------------------------------------------------------------------------
CREATE TABLE building (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '楼栋ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    name VARCHAR(50) NOT NULL COMMENT '楼栋名称',
    floors INT NOT NULL COMMENT '楼层数',
    description TEXT NULL COMMENT '楼栋描述',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0-未删除, 1-已删除',
    deleted_at DATETIME NULL COMMENT '删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_community_id (community_id),
    INDEX idx_is_deleted (is_deleted, community_id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='楼栋信息表';

-- -----------------------------------------------------------------------------
-- 3. unit - 单元信息表
-- -----------------------------------------------------------------------------
CREATE TABLE unit (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '单元ID',
    building_id BIGINT UNSIGNED NOT NULL COMMENT '所属楼栋ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    name VARCHAR(50) NOT NULL COMMENT '单元名称',
    description TEXT NULL COMMENT '单元描述',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0-未删除, 1-已删除',
    deleted_at DATETIME NULL COMMENT '删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_building_id (building_id),
    INDEX idx_community_id (community_id),
    INDEX idx_is_deleted (is_deleted, community_id),
    FOREIGN KEY (building_id) REFERENCES building(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='单元信息表';

-- -----------------------------------------------------------------------------
-- 4. house - 房屋信息表
-- -----------------------------------------------------------------------------
CREATE TABLE house (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '房屋ID',
    unit_id BIGINT UNSIGNED NOT NULL COMMENT '所属单元ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    house_number VARCHAR(20) NOT NULL COMMENT '房号',
    floor INT NOT NULL COMMENT '楼层',
    area DECIMAL(10, 2) NULL COMMENT '建筑面积（平方米）',
    room_count INT NULL COMMENT '房间数',
    status VARCHAR(20) NOT NULL DEFAULT 'VACANT' COMMENT '状态：VACANT-空置, OCCUPIED-已入住, RESERVED-预留, MAINTENANCE-维护中',
    description TEXT NULL COMMENT '房屋描述',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0-未删除, 1-已删除',
    deleted_at DATETIME NULL COMMENT '删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_unit_id (unit_id),
    INDEX idx_community_id (community_id),
    INDEX idx_status (status),
    INDEX idx_is_deleted (is_deleted, community_id),
    FOREIGN KEY (unit_id) REFERENCES unit(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房屋信息表';

-- -----------------------------------------------------------------------------
-- 5. house_status_history - 房屋状态变更历史表
-- -----------------------------------------------------------------------------
CREATE TABLE house_status_history (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    house_id BIGINT UNSIGNED NOT NULL COMMENT '房屋ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    old_status VARCHAR(20) NULL COMMENT '变更前状态',
    new_status VARCHAR(20) NOT NULL COMMENT '变更后状态',
    operator_id BIGINT UNSIGNED NOT NULL COMMENT '操作人ID',
    remark TEXT NULL COMMENT '变更备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    INDEX idx_house_id (house_id),
    INDEX idx_community_id (community_id),
    FOREIGN KEY (house_id) REFERENCES house(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房屋状态变更历史表';

-- -----------------------------------------------------------------------------
-- 6. public_resource - 公共资源表
-- -----------------------------------------------------------------------------
CREATE TABLE public_resource (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '资源ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    name VARCHAR(100) NOT NULL COMMENT '资源名称',
    type VARCHAR(50) NOT NULL COMMENT '资源类型：MEETING_ROOM-会议室, GYM-健身房, PARKING-停车位',
    location VARCHAR(255) NULL COMMENT '位置描述',
    capacity INT NULL COMMENT '容纳人数/车位数',
    description TEXT NULL COMMENT '资源描述',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0-未删除, 1-已删除',
    deleted_at DATETIME NULL COMMENT '删除时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_community_id (community_id),
    INDEX idx_type (type),
    INDEX idx_is_deleted (is_deleted, community_id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公共资源表';

-- -----------------------------------------------------------------------------
-- 7. resource_timeslot - 资源可预约时段表
-- -----------------------------------------------------------------------------
CREATE TABLE resource_timeslot (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '时段ID',
    resource_id BIGINT UNSIGNED NOT NULL COMMENT '资源ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    day_of_week TINYINT NOT NULL COMMENT '星期几：1-周一, 7-周日',
    start_time TIME NOT NULL COMMENT '开始时间',
    end_time TIME NOT NULL COMMENT '结束时间',
    is_available TINYINT NOT NULL DEFAULT 1 COMMENT '是否可预约：0-不可预约, 1-可预约',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_resource_id (resource_id),
    INDEX idx_community_id (community_id),
    FOREIGN KEY (resource_id) REFERENCES public_resource(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源可预约时段表';

-- =============================================================================
-- C2: 居民与居住关系管理（4 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 8. resident - 居民账号表
-- -----------------------------------------------------------------------------
CREATE TABLE resident (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '居民ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希（BCrypt）',
    real_name VARCHAR(50) NOT NULL COMMENT '真实姓名',
    phone VARCHAR(20) NOT NULL COMMENT '手机号',
    id_card VARCHAR(18) NULL COMMENT '身份证号（脱敏存储）',
    email VARCHAR(100) NULL COMMENT '邮箱',
    avatar_url VARCHAR(255) NULL COMMENT '头像URL',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-正常, FROZEN-冻结',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='居民账号表';

-- -----------------------------------------------------------------------------
-- 9. residence_application - 入住申请表
-- -----------------------------------------------------------------------------
CREATE TABLE residence_application (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '申请ID',
    resident_id BIGINT UNSIGNED NOT NULL COMMENT '申请人ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '申请社区ID',
    house_id BIGINT UNSIGNED NOT NULL COMMENT '申请房屋ID',
    relation_type VARCHAR(20) NOT NULL COMMENT '关系类型：OWNER-业主, TENANT-租客, FAMILY-家属',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待审核, APPROVED-已通过, REJECTED-已拒绝',
    remark TEXT NULL COMMENT '申请说明',
    reviewer_id BIGINT UNSIGNED NULL COMMENT '审核人ID',
    review_time DATETIME NULL COMMENT '审核时间',
    review_remark TEXT NULL COMMENT '审核意见',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_resident_id (resident_id),
    INDEX idx_community_id (community_id),
    INDEX idx_house_id (house_id),
    INDEX idx_status (status),
    FOREIGN KEY (resident_id) REFERENCES resident(id),
    FOREIGN KEY (community_id) REFERENCES community(id),
    FOREIGN KEY (house_id) REFERENCES house(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='入住申请表';

-- -----------------------------------------------------------------------------
-- 10. residence_relation - 居住关系表（M:N 关联表）
-- -----------------------------------------------------------------------------
CREATE TABLE residence_relation (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '关系ID',
    resident_id BIGINT UNSIGNED NOT NULL COMMENT '居民ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    house_id BIGINT UNSIGNED NOT NULL COMMENT '房屋ID',
    relation_type VARCHAR(20) NOT NULL COMMENT '关系类型：OWNER-业主, TENANT-租客, FAMILY-家属',
    move_in_date DATE NOT NULL COMMENT '入住日期',
    move_out_date DATE NULL COMMENT '迁出日期',
    is_primary TINYINT NOT NULL DEFAULT 0 COMMENT '是否主要居住地：0-否, 1-是',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_resident_id (resident_id),
    INDEX idx_community_id (community_id),
    INDEX idx_house_id (house_id),
    FOREIGN KEY (resident_id) REFERENCES resident(id),
    FOREIGN KEY (community_id) REFERENCES community(id),
    FOREIGN KEY (house_id) REFERENCES house(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='居住关系表';

-- -----------------------------------------------------------------------------
-- 11. sys_config - 全局配置表
-- -----------------------------------------------------------------------------
CREATE TABLE sys_config (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '配置ID',
    config_key VARCHAR(100) NOT NULL COMMENT '配置键',
    config_value TEXT NOT NULL COMMENT '配置值',
    description VARCHAR(255) NULL COMMENT '配置说明',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='全局配置表';

-- =============================================================================
-- C3: 租住管理（2 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 12. lease_record - 租住记录表
-- -----------------------------------------------------------------------------
CREATE TABLE lease_record (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '租住记录ID',
    tenant_id BIGINT UNSIGNED NOT NULL COMMENT '租客ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    house_id BIGINT UNSIGNED NOT NULL COMMENT '房屋ID',
    start_date DATE NOT NULL COMMENT '租期开始日期',
    end_date DATE NOT NULL COMMENT '租期结束日期',
    monthly_rent DECIMAL(10, 2) NOT NULL COMMENT '月租金',
    deposit DECIMAL(10, 2) NULL COMMENT '押金',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待生效, ACTIVE-租住中, MOVED_OUT-已迁出, ARCHIVED-已归档, REJECTED-已拒绝',
    contract_url VARCHAR(255) NULL COMMENT '合同附件URL',
    remark TEXT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_tenant_id (tenant_id),
    INDEX idx_community_id (community_id),
    INDEX idx_house_id (house_id),
    INDEX idx_status_end_date (status, end_date),
    FOREIGN KEY (tenant_id) REFERENCES resident(id),
    FOREIGN KEY (community_id) REFERENCES community(id),
    FOREIGN KEY (house_id) REFERENCES house(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租住记录表';

-- -----------------------------------------------------------------------------
-- 13. lease_reminder - 租期到期提醒去重表
-- -----------------------------------------------------------------------------
CREATE TABLE lease_reminder (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '提醒ID',
    lease_id BIGINT UNSIGNED NOT NULL COMMENT '租住记录ID',
    status VARCHAR(20) NOT NULL COMMENT '租住状态（与 lease_id 组成唯一键）',
    remind_time DATETIME NOT NULL COMMENT '提醒时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_lease_status (lease_id, status),
    FOREIGN KEY (lease_id) REFERENCES lease_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租期到期提醒去重表';

-- =============================================================================
-- C4: 服务申请与工单管理（5 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 14. service_category - 服务类别表
-- -----------------------------------------------------------------------------
CREATE TABLE service_category (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '类别ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    name VARCHAR(50) NOT NULL COMMENT '类别名称',
    description TEXT NULL COMMENT '类别描述',
    parent_id BIGINT UNSIGNED NULL COMMENT '父类别ID（支持二级分类）',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    is_active TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0-禁用, 1-启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_community_id (community_id),
    INDEX idx_parent_id (parent_id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务类别表';

-- -----------------------------------------------------------------------------
-- 15. work_order - 工单表
-- -----------------------------------------------------------------------------
CREATE TABLE work_order (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '工单ID',
    order_no VARCHAR(50) NOT NULL COMMENT '工单编号',
    resident_id BIGINT UNSIGNED NOT NULL COMMENT '提交人ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    category_id BIGINT UNSIGNED NOT NULL COMMENT '服务类别ID',
    title VARCHAR(100) NOT NULL COMMENT '工单标题',
    content TEXT NOT NULL COMMENT '工单内容',
    contact_phone VARCHAR(20) NOT NULL COMMENT '联系电话',
    address VARCHAR(255) NULL COMMENT '服务地址',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待处理, TO_ASSIGN-待派单, ASSIGNED-已派单, ACCEPTED-已接单, IN_PROGRESS-处理中, TO_CONFIRM-待确认, COMPLETED-已完成, CLOSED-已关闭, REJECTED-已拒绝, CANCELLED-已取消',
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL' COMMENT '优先级：LOW-低, NORMAL-普通, HIGH-高, URGENT-紧急',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_order_no (order_no),
    INDEX idx_resident_id (resident_id, status),
    INDEX idx_community_status_time (community_id, status, created_at),
    INDEX idx_category_id (category_id),
    FOREIGN KEY (resident_id) REFERENCES resident(id),
    FOREIGN KEY (community_id) REFERENCES community(id),
    FOREIGN KEY (category_id) REFERENCES service_category(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单表';

-- -----------------------------------------------------------------------------
-- 16. work_order_attachment - 工单附件表
-- -----------------------------------------------------------------------------
CREATE TABLE work_order_attachment (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '附件ID',
    work_order_id BIGINT UNSIGNED NOT NULL COMMENT '工单ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_url VARCHAR(500) NOT NULL COMMENT '文件URL',
    file_type VARCHAR(50) NULL COMMENT '文件类型',
    file_size BIGINT NULL COMMENT '文件大小（字节）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    INDEX idx_work_order_id (work_order_id),
    FOREIGN KEY (work_order_id) REFERENCES work_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单附件表';

-- -----------------------------------------------------------------------------
-- 17. work_order_process - 工单处理记录表（时间线）
-- -----------------------------------------------------------------------------
CREATE TABLE work_order_process (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    work_order_id BIGINT UNSIGNED NOT NULL COMMENT '工单ID',
    operator_id BIGINT UNSIGNED NOT NULL COMMENT '操作人ID',
    operator_type VARCHAR(20) NOT NULL COMMENT '操作人类型：RESIDENT-居民, STAFF-服务人员, ADMIN-管理员',
    action VARCHAR(50) NOT NULL COMMENT '操作动作',
    old_status VARCHAR(20) NULL COMMENT '变更前状态',
    new_status VARCHAR(20) NULL COMMENT '变更后状态',
    content TEXT NULL COMMENT '处理内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_work_order_id (work_order_id),
    FOREIGN KEY (work_order_id) REFERENCES work_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单处理记录表';

-- -----------------------------------------------------------------------------
-- 18. work_order_assignment - 工单派单关系表
-- -----------------------------------------------------------------------------
CREATE TABLE work_order_assignment (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '派单ID',
    work_order_id BIGINT UNSIGNED NOT NULL COMMENT '工单ID',
    assignee_id BIGINT UNSIGNED NOT NULL COMMENT '服务人员ID',
    assigner_id BIGINT UNSIGNED NOT NULL COMMENT '派单人ID',
    assign_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '派单时间',
    accept_time DATETIME NULL COMMENT '接单时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_work_order_id (work_order_id),
    INDEX idx_assignee_id (assignee_id),
    FOREIGN KEY (work_order_id) REFERENCES work_order(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单派单关系表';

-- =============================================================================
-- C5: 公告广播管理（3 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 19. notice - 公告表
-- -----------------------------------------------------------------------------
CREATE TABLE notice (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '公告ID',
    title VARCHAR(200) NOT NULL COMMENT '公告标题',
    content TEXT NOT NULL COMMENT '公告内容',
    publisher_id BIGINT UNSIGNED NOT NULL COMMENT '发布人ID',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT-草稿, PUBLISHED-已发布, WITHDRAWN-已撤回',
    publish_time DATETIME NULL COMMENT '发布时间',
    start_time DATETIME NULL COMMENT '生效时间',
    end_time DATETIME NULL COMMENT '失效时间',
    view_count INT NOT NULL DEFAULT 0 COMMENT '浏览次数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_status_start_time (status, start_time),
    INDEX idx_publisher_id (publisher_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公告表';

-- -----------------------------------------------------------------------------
-- 20. notice_target - 公告目标范围表
-- -----------------------------------------------------------------------------
CREATE TABLE notice_target (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    notice_id BIGINT UNSIGNED NOT NULL COMMENT '公告ID',
    target_type VARCHAR(20) NOT NULL COMMENT '目标类型：COMMUNITY-社区, BUILDING-楼栋',
    target_id BIGINT UNSIGNED NOT NULL COMMENT '目标ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_notice_id (notice_id),
    INDEX idx_target (target_type, target_id),
    FOREIGN KEY (notice_id) REFERENCES notice(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公告目标范围表';

-- -----------------------------------------------------------------------------
-- 21. notice_view_record - 公告查看记录表（M:N 关联表）
-- -----------------------------------------------------------------------------
CREATE TABLE notice_view_record (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    notice_id BIGINT UNSIGNED NOT NULL COMMENT '公告ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    view_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '查看时间',
    UNIQUE KEY uk_notice_user (notice_id, user_id),
    INDEX idx_user_id (user_id),
    FOREIGN KEY (notice_id) REFERENCES notice(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='公告查看记录表';

-- =============================================================================
-- C6: 居民反馈管理（3 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 22. feedback - 反馈单表
-- -----------------------------------------------------------------------------
CREATE TABLE feedback (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '反馈ID',
    resident_id BIGINT UNSIGNED NOT NULL COMMENT '反馈人ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    title VARCHAR(100) NOT NULL COMMENT '反馈标题',
    content TEXT NOT NULL COMMENT '反馈内容',
    category VARCHAR(50) NOT NULL COMMENT '反馈类别：SUGGESTION-建议, COMPLAINT-投诉, INQUIRY-咨询',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待处理, IN_SESSION-会话中, CLOSED-已关闭',
    handler_id BIGINT UNSIGNED NULL COMMENT '处理人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_resident_id (resident_id),
    INDEX idx_community_id (community_id),
    INDEX idx_status (status),
    FOREIGN KEY (resident_id) REFERENCES resident(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='反馈单表';

-- -----------------------------------------------------------------------------
-- 23. feedback_attachment - 反馈附件表
-- -----------------------------------------------------------------------------
CREATE TABLE feedback_attachment (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '附件ID',
    feedback_id BIGINT UNSIGNED NOT NULL COMMENT '反馈ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_url VARCHAR(500) NOT NULL COMMENT '文件URL',
    file_type VARCHAR(50) NULL COMMENT '文件类型',
    file_size BIGINT NULL COMMENT '文件大小（字节）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    INDEX idx_feedback_id (feedback_id),
    FOREIGN KEY (feedback_id) REFERENCES feedback(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='反馈附件表';

-- -----------------------------------------------------------------------------
-- 24. feedback_message - 反馈会话消息表
-- -----------------------------------------------------------------------------
CREATE TABLE feedback_message (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    feedback_id BIGINT UNSIGNED NOT NULL COMMENT '反馈ID',
    sender_id BIGINT UNSIGNED NOT NULL COMMENT '发送人ID',
    sender_type VARCHAR(20) NOT NULL COMMENT '发送人类型：RESIDENT-居民, ADMIN-管理员',
    content TEXT NOT NULL COMMENT '消息内容',
    parent_id BIGINT UNSIGNED NULL COMMENT '父消息ID（支持回复）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    INDEX idx_feedback_id (feedback_id),
    INDEX idx_parent_id (parent_id),
    FOREIGN KEY (feedback_id) REFERENCES feedback(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='反馈会话消息表';

-- =============================================================================
-- C7: 公共资源预约管理（2 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 25. resource_reservation - 资源预约表
-- -----------------------------------------------------------------------------
CREATE TABLE resource_reservation (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '预约ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '预约人ID',
    resource_id BIGINT UNSIGNED NOT NULL COMMENT '资源ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    reserve_date DATE NOT NULL COMMENT '预约日期',
    start_time TIME NOT NULL COMMENT '开始时间',
    end_time TIME NOT NULL COMMENT '结束时间',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待确认, RESERVED-已预约, COMPLETED-已完成, REJECTED-已拒绝, CANCELLED-已取消, VIOLATED-已违约',
    purpose VARCHAR(255) NULL COMMENT '预约用途',
    contact_phone VARCHAR(20) NOT NULL COMMENT '联系电话',
    remark TEXT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预约时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_resource_date (resource_id, reserve_date, status),
    INDEX idx_community_id (community_id),
    FOREIGN KEY (user_id) REFERENCES resident(id),
    FOREIGN KEY (resource_id) REFERENCES public_resource(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='资源预约表';

-- -----------------------------------------------------------------------------
-- 26. violation_record - 违约处置记录表
-- -----------------------------------------------------------------------------
CREATE TABLE violation_record (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '违约用户ID',
    violation_type VARCHAR(50) NOT NULL COMMENT '违约类型：RESERVATION_NO_SHOW-预约未到, VIEWING_NO_SHOW-看房未到',
    related_id BIGINT UNSIGNED NOT NULL COMMENT '关联ID（预约ID或看房ID）',
    punishment VARCHAR(100) NULL COMMENT '处置措施',
    remark TEXT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    INDEX idx_user_id (user_id),
    FOREIGN KEY (user_id) REFERENCES resident(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='违约处置记录表';

-- =============================================================================
-- C8: 服务评价管理（2 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 27. work_order_evaluation - 工单评价表
-- -----------------------------------------------------------------------------
CREATE TABLE work_order_evaluation (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '评价ID',
    work_order_id BIGINT UNSIGNED NOT NULL COMMENT '工单ID',
    resident_id BIGINT UNSIGNED NOT NULL COMMENT '评价人ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    rating TINYINT NOT NULL COMMENT '评分：1-5星',
    content TEXT NULL COMMENT '评价内容',
    tags VARCHAR(255) NULL COMMENT '标签（逗号分隔）',
    is_satisfied TINYINT NOT NULL COMMENT '是否满意：0-不满意, 1-满意',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    UNIQUE KEY uk_work_order_id (work_order_id),
    INDEX idx_resident_id (resident_id),
    INDEX idx_community_id (community_id),
    INDEX idx_rating (rating),
    FOREIGN KEY (work_order_id) REFERENCES work_order(id),
    FOREIGN KEY (resident_id) REFERENCES resident(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工单评价表';

-- -----------------------------------------------------------------------------
-- 28. unsatisfied_followup - 不满意跟进记录表
-- -----------------------------------------------------------------------------
CREATE TABLE unsatisfied_followup (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '跟进ID',
    evaluation_id BIGINT UNSIGNED NOT NULL COMMENT '评价ID',
    handler_id BIGINT UNSIGNED NOT NULL COMMENT '跟进人ID',
    followup_content TEXT NOT NULL COMMENT '跟进内容',
    followup_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '跟进时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_evaluation_id (evaluation_id),
    INDEX idx_handler_id (handler_id),
    FOREIGN KEY (evaluation_id) REFERENCES work_order_evaluation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='不满意跟进记录表';

-- =============================================================================
-- C9: 社区运营统计（1 张表，可选）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 29. statistics_snapshot - 统计快照表（可选预聚合表）
-- -----------------------------------------------------------------------------
CREATE TABLE statistics_snapshot (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '快照ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    snapshot_date DATE NOT NULL COMMENT '快照日期',
    metric_type VARCHAR(50) NOT NULL COMMENT '指标类型',
    metric_value BIGINT NOT NULL COMMENT '指标值',
    extra_data JSON NULL COMMENT '扩展数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_community_date (community_id, snapshot_date),
    INDEX idx_metric_type (metric_type),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统计快照表';

-- =============================================================================
-- C10: 用户与权限管理（5 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 30. sys_user - 系统用户表（管理员/服务人员）
-- -----------------------------------------------------------------------------
CREATE TABLE sys_user (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL COMMENT '用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希（BCrypt）',
    real_name VARCHAR(50) NOT NULL COMMENT '真实姓名',
    phone VARCHAR(20) NOT NULL COMMENT '手机号',
    email VARCHAR(100) NULL COMMENT '邮箱',
    avatar_url VARCHAR(255) NULL COMMENT '头像URL',
    role VARCHAR(20) NOT NULL COMMENT '角色：SUPER_ADMIN-超级管理员, ADMIN-社区管理员, STAFF-服务人员',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-正常, FROZEN-冻结',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_phone (phone),
    INDEX idx_role (role),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- -----------------------------------------------------------------------------
-- 31. sys_admin_community - 管理员-社区绑定表（M:N 关联表）
-- -----------------------------------------------------------------------------
CREATE TABLE sys_admin_community (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '绑定ID',
    admin_id BIGINT UNSIGNED NOT NULL COMMENT '管理员ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '社区ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    UNIQUE KEY uk_admin_community (admin_id, community_id),
    INDEX idx_community_id (community_id),
    FOREIGN KEY (admin_id) REFERENCES sys_user(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理员-社区绑定表';

-- -----------------------------------------------------------------------------
-- 32. auth_token_blacklist - JWT令牌黑名单表（DB 双写降级方案）
-- -----------------------------------------------------------------------------
CREATE TABLE auth_token_blacklist (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    jti VARCHAR(64) NOT NULL COMMENT 'JWT 唯一标识',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    expire_time DATETIME NOT NULL COMMENT '令牌过期时间',
    reason VARCHAR(50) NOT NULL COMMENT '拉黑原因：LOGOUT-主动登出, PERMISSION_CHANGE-权限变更',
    blacklist_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '拉黑时间',
    UNIQUE KEY uk_jti (jti),
    INDEX idx_expire_time (expire_time),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='JWT令牌黑名单表';

-- -----------------------------------------------------------------------------
-- 33. sys_operation_log - 操作日志表（仅追加，保留 ≥4 年）
-- -----------------------------------------------------------------------------
CREATE TABLE sys_operation_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
    operator_id BIGINT UNSIGNED NOT NULL COMMENT '操作人ID',
    operator_type VARCHAR(20) NOT NULL COMMENT '操作人类型：RESIDENT-居民, ADMIN-管理员, STAFF-服务人员',
    community_id BIGINT UNSIGNED NULL COMMENT '所属社区ID（可选，便于分社区检索）',
    operation_type VARCHAR(50) NOT NULL COMMENT '操作类型',
    target_type VARCHAR(50) NOT NULL COMMENT '操作对象类型',
    target_id BIGINT UNSIGNED NOT NULL COMMENT '操作对象ID',
    content JSON NULL COMMENT '操作内容（JSON 格式）',
    ip VARCHAR(50) NULL COMMENT '操作IP',
    user_agent VARCHAR(500) NULL COMMENT 'User-Agent',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    INDEX idx_operator_id (operator_id, created_at),
    INDEX idx_target (target_type, target_id, created_at),
    INDEX idx_community_id (community_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作日志表（仅追加）';

-- -----------------------------------------------------------------------------
-- 34. sys_blacklist_log - 拉黑审计日志表
-- -----------------------------------------------------------------------------
CREATE TABLE sys_blacklist_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
    blocked_key VARCHAR(255) NOT NULL COMMENT '被拉黑的键（IP/用户ID等）',
    block_type VARCHAR(50) NOT NULL COMMENT '拉黑类型：IP-IP地址, USER-用户',
    reason VARCHAR(255) NOT NULL COMMENT '拉黑原因',
    expire_time DATETIME NULL COMMENT '过期时间',
    operator_id BIGINT UNSIGNED NULL COMMENT '操作人ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '拉黑时间',
    INDEX idx_blocked_key (blocked_key),
    INDEX idx_block_type (block_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='拉黑审计日志表';

-- =============================================================================
-- C11: 消息与通知中心（2 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 35. notification - 通知记录表（含 seq 字段，Redis INCR 生成）
-- -----------------------------------------------------------------------------
CREATE TABLE notification (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '通知ID',
    seq BIGINT UNSIGNED NOT NULL COMMENT '全局递增序号（Redis INCR 生成）',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '接收人ID',
    community_id BIGINT UNSIGNED NULL COMMENT '所属社区ID（可选，便于检索）',
    title VARCHAR(255) NOT NULL COMMENT '通知标题',
    content TEXT NOT NULL COMMENT '通知内容',
    type VARCHAR(50) NOT NULL COMMENT '通知类型',
    source_type VARCHAR(50) NULL COMMENT '来源类型',
    source_id BIGINT UNSIGNED NULL COMMENT '来源ID',
    channels VARCHAR(100) NULL COMMENT '推送渠道（逗号分隔）：WEBSOCKET, EMAIL, SMS',
    is_read TINYINT NOT NULL DEFAULT 0 COMMENT '已读状态：0-未读, 1-已读',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_seq (seq),
    INDEX idx_user_seq (user_id, seq),
    INDEX idx_user_read (user_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知记录表';

-- -----------------------------------------------------------------------------
-- 36. notification_channel_log - 通知渠道发送记录表（模拟渠道发送）
-- -----------------------------------------------------------------------------
CREATE TABLE notification_channel_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    notification_id BIGINT UNSIGNED NOT NULL COMMENT '通知ID',
    channel VARCHAR(50) NOT NULL COMMENT '渠道：WEBSOCKET, EMAIL, SMS',
    status VARCHAR(20) NOT NULL COMMENT '发送状态：SUCCESS-成功, FAILED-失败',
    error_message TEXT NULL COMMENT '错误信息',
    sent_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    INDEX idx_notification_id (notification_id),
    FOREIGN KEY (notification_id) REFERENCES notification(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知渠道发送记录表';

-- =============================================================================
-- C12: 房源展示与看房预约（3 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 37. housing - 房源信息表
-- -----------------------------------------------------------------------------
CREATE TABLE housing (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '房源ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    house_id BIGINT UNSIGNED NOT NULL COMMENT '关联房屋ID',
    title VARCHAR(200) NOT NULL COMMENT '房源标题',
    description TEXT NULL COMMENT '房源描述',
    monthly_rent DECIMAL(10, 2) NOT NULL COMMENT '月租金',
    deposit DECIMAL(10, 2) NULL COMMENT '押金',
    images VARCHAR(1000) NULL COMMENT '房源图片（逗号分隔URL）',
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT '状态：AVAILABLE-可租, RESERVED-已预订, RENTED-已出租, OFFLINE-已下线',
    view_count INT NOT NULL DEFAULT 0 COMMENT '浏览次数',
    publish_time DATETIME NULL COMMENT '发布时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_community_id (community_id),
    INDEX idx_house_id (house_id),
    INDEX idx_status (status),
    FOREIGN KEY (community_id) REFERENCES community(id),
    FOREIGN KEY (house_id) REFERENCES house(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房源信息表';

-- -----------------------------------------------------------------------------
-- 38. viewing_appointment - 看房预约表
-- -----------------------------------------------------------------------------
CREATE TABLE viewing_appointment (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '预约ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '预约人ID',
    housing_id BIGINT UNSIGNED NOT NULL COMMENT '房源ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID',
    appointment_date DATE NOT NULL COMMENT '预约日期',
    start_time TIME NOT NULL COMMENT '开始时间',
    end_time TIME NOT NULL COMMENT '结束时间',
    status VARCHAR(20) NOT NULL DEFAULT 'TO_CONFIRM' COMMENT '状态：TO_CONFIRM-待确认, RESERVED-已预约, COMPLETED-已完成, CANCELLED-已取消, VIOLATED-已违约',
    contact_phone VARCHAR(20) NOT NULL COMMENT '联系电话',
    remark TEXT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预约时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_housing_date (housing_id, appointment_date, status),
    INDEX idx_community_id (community_id),
    FOREIGN KEY (user_id) REFERENCES resident(id),
    FOREIGN KEY (housing_id) REFERENCES housing(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='看房预约表';

-- -----------------------------------------------------------------------------
-- 39. housing_timeslot - 房源可预约时段表
-- -----------------------------------------------------------------------------
CREATE TABLE housing_timeslot (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '时段ID',
    housing_id BIGINT UNSIGNED NOT NULL COMMENT '房源ID',
    day_of_week TINYINT NOT NULL COMMENT '星期几：1-周一, 7-周日',
    start_time TIME NOT NULL COMMENT '开始时间',
    end_time TIME NOT NULL COMMENT '结束时间',
    is_available TINYINT NOT NULL DEFAULT 1 COMMENT '是否可预约：0-不可预约, 1-可预约',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_housing_id (housing_id),
    FOREIGN KEY (housing_id) REFERENCES housing(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='房源可预约时段表';

-- =============================================================================
-- 横切支撑模块（1 张表）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 40. sys_task_log - 定时任务执行日志表
-- -----------------------------------------------------------------------------
CREATE TABLE sys_task_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '日志ID',
    task_name VARCHAR(100) NOT NULL COMMENT '任务名称',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NULL COMMENT '结束时间',
    duration BIGINT NULL COMMENT '执行时长（毫秒）',
    status VARCHAR(20) NOT NULL COMMENT '执行状态：SUCCESS-成功, FAILED-失败',
    processed_count INT NULL COMMENT '处理条数',
    success_count INT NULL COMMENT '成功条数',
    failed_count INT NULL COMMENT '失败条数',
    alert_level VARCHAR(20) NULL COMMENT '告警级别：INFO-信息, WARNING-警告, ERROR-错误',
    error_message TEXT NULL COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_task_name (task_name, created_at),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='定时任务执行日志表';

-- =============================================================================
-- 脚本结束
-- =============================================================================
