-- =============================================================================
-- V18: 社区入住自动审批（C2）+ 租约属性变更历史与轻量租赁协议（C3）
-- 依据：需求规格说明 R64（租赁协议在线确认）+ R62 补（社区级申请租住自动通过）
-- 2026-09-21 用户裁决：轻量版协议（平台自建模板与确认留痕，不引入第三方电子签）
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. community 增自动化变量：入住申请自动通过 + 默认租期
--    auto_approve_residence=1 时，居民提交入住申请即自动审批通过
--    （生成居住关系 + TENANT 生成租约 + 房屋置为已入住），无需人工审核；
--    default_lease_months 仅在自动通过路径用于推导租期止期，人工审核不受影响。
-- -----------------------------------------------------------------------------
ALTER TABLE community
    ADD COLUMN auto_approve_residence TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '入住申请自动通过：0-关闭（人工审核）, 1-开启（提交即通过）',
    ADD COLUMN default_lease_months INT NOT NULL DEFAULT 12
        COMMENT '自动通过时的默认租期月数（人工审核路径不使用）';

-- -----------------------------------------------------------------------------
-- 2. lease_change_log - 租约属性变更历史（字段级前后值留痕）
--    与 sys_operation_log 的分工：操作日志记「谁做了什么」（操作级），
--    本表记「哪个字段从什么变成什么」（字段级），支撑租约属性变更可追溯。
-- -----------------------------------------------------------------------------
CREATE TABLE lease_change_log (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '变更记录ID',
    lease_id BIGINT UNSIGNED NOT NULL COMMENT '租住记录ID',
    change_type VARCHAR(20) NOT NULL COMMENT '变更类型：CREATE-登记, ATTRIBUTE-属性变更, RENEW-续租, STATUS-状态流转, AGREEMENT-协议签约',
    field_name VARCHAR(50) NULL COMMENT '字段名（CREATE/STATUS/AGREEMENT 等非单字段变更时为 NULL）',
    field_label VARCHAR(50) NULL COMMENT '字段中文名（列表展示用）',
    old_value VARCHAR(500) NULL COMMENT '变更前值',
    new_value VARCHAR(500) NULL COMMENT '变更后值',
    reason VARCHAR(500) NULL COMMENT '变更原因/备注',
    operator_id BIGINT UNSIGNED NULL COMMENT '操作人ID（系统自动变更时为 NULL）',
    operator_name VARCHAR(50) NULL COMMENT '操作人姓名（快照，避免账号改名后历史失真）',
    operator_type VARCHAR(20) NOT NULL COMMENT '操作人身份：ADMIN-管理方, RESIDENT-居民, SYSTEM-系统任务',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    INDEX idx_lease_change_lease (lease_id, id),
    FOREIGN KEY (lease_id) REFERENCES lease_record(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租约属性变更历史表';

-- -----------------------------------------------------------------------------
-- 3. lease_record 增签约状态列（协议确认进度，为展示/提醒维度，不参与状态机流转）
--    理由：架构设计 §6 六大状态机已冻结，签约不引入新租住状态，
--    以独立列表达「未发起协议 / 待确认 / 双方已确认」三态。
-- -----------------------------------------------------------------------------
ALTER TABLE lease_record
    ADD COLUMN agreement_status VARCHAR(30) NOT NULL DEFAULT 'NONE'
        COMMENT '协议签约状态：NONE-未发起, PENDING-待确认, PARTIAL-单方已确认, SIGNED-双方已确认, CANCELLED-已撤回';

-- -----------------------------------------------------------------------------
-- 4. agreement_template - 租赁协议模板（支持全局通用模板 + 社区专属模板）
--    community_id 为 NULL 表示全局通用模板（超管维护，各社区可用）。
--    模板正文支持 {{变量}} 占位，发起签约时按租约数据渲染为快照正文。
-- -----------------------------------------------------------------------------
CREATE TABLE agreement_template (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '模板ID',
    community_id BIGINT UNSIGNED NULL COMMENT '所属社区ID（NULL=全局通用模板）',
    name VARCHAR(100) NOT NULL COMMENT '模板名称',
    content TEXT NULL COMMENT '协议正文（支持 {{变量}} 占位）',
    file_name VARCHAR(255) NULL COMMENT '模板附件原始文件名',
    file_url VARCHAR(500) NULL COMMENT '模板附件访问地址（上传的模板文件，供查看/下载）',
    file_size BIGINT UNSIGNED NULL COMMENT '模板附件大小（字节）',
    is_default TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认模板：0-否, 1-是',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-启用, INACTIVE-停用',
    remark VARCHAR(500) NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_agreement_template_community (community_id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租赁协议模板表';

-- -----------------------------------------------------------------------------
-- 5. lease_agreement - 租约协议（一次签约一份，正文为生成时快照）
--    双方确认留痕：居民端与管理员端各记确认人、确认时间、确认时姓名，
--    两方均确认后 status=SIGNED 且回写 lease_record.agreement_status。
--    content 存快照而非模板引用：模板后续修改不得改变已签协议内容。
-- -----------------------------------------------------------------------------
CREATE TABLE lease_agreement (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '协议ID',
    lease_id BIGINT UNSIGNED NOT NULL COMMENT '租住记录ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '所属社区ID（数据级权限锚点）',
    template_id BIGINT UNSIGNED NULL COMMENT '来源模板ID（模板删除后协议仍可追溯正文快照）',
    template_name VARCHAR(100) NULL COMMENT '来源模板名称快照',
    title VARCHAR(150) NOT NULL COMMENT '协议标题',
    content MEDIUMTEXT NULL COMMENT '协议正文快照（占位符已渲染）',
    template_file_url VARCHAR(500) NULL COMMENT '模板附件地址快照',
    template_file_name VARCHAR(255) NULL COMMENT '模板附件文件名快照',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING-待确认, PARTIAL-单方已确认, SIGNED-双方已确认, CANCELLED-已撤回',
    tenant_id BIGINT UNSIGNED NULL COMMENT '居民方居民ID',
    tenant_confirm_name VARCHAR(50) NULL COMMENT '居民方确认人姓名',
    tenant_confirm_time DATETIME NULL COMMENT '居民方确认时间',
    admin_id BIGINT UNSIGNED NULL COMMENT '管理方确认人用户ID',
    admin_confirm_name VARCHAR(50) NULL COMMENT '管理方确认人姓名',
    admin_confirm_time DATETIME NULL COMMENT '管理方确认时间',
    cancel_reason VARCHAR(500) NULL COMMENT '撤回原因',
    remark VARCHAR(500) NULL COMMENT '备注',
    created_by BIGINT UNSIGNED NULL COMMENT '发起人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_lease_agreement_lease (lease_id),
    INDEX idx_lease_agreement_community (community_id),
    FOREIGN KEY (lease_id) REFERENCES lease_record(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租约协议表（轻量在线确认）';

-- -----------------------------------------------------------------------------
-- 6. 存量租约回填：已有协议附件（contract_url）视为已线下签署，标记 SIGNED；
--    其余保持 NONE（未发起协议），由管理端按需发起。
-- -----------------------------------------------------------------------------
UPDATE lease_record SET agreement_status = 'SIGNED'
WHERE contract_url IS NOT NULL AND contract_url <> '';
