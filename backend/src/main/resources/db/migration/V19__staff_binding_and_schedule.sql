-- =============================================================================
-- V19: 服务人员常驻社区/擅长类别绑定 + 排班（C4 物业管理调度）
-- 依据：2026-09-21 用户裁决——工单应为「居民-服务人员调度对接」，物业自身
-- 有长期对接的固定人员，需支持人员-社区-类别绑定与排班管理；
-- 原实现派单候选为全量 STAFF 按「曾派过单」排序，无社区/类别归属，无法支撑调度。
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. staff_community - 服务人员常驻社区绑定（多对多：一人可服务多个社区）
--    派单候选优先级第一档：绑定本社区的人员。
-- -----------------------------------------------------------------------------
CREATE TABLE staff_community (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '绑定ID',
    staff_id BIGINT UNSIGNED NOT NULL COMMENT '服务人员用户ID（sys_user.role=STAFF）',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '常驻社区ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    UNIQUE KEY uk_staff_community (staff_id, community_id),
    INDEX idx_staff_community_community (community_id),
    FOREIGN KEY (staff_id) REFERENCES sys_user(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务人员常驻社区绑定表';

-- -----------------------------------------------------------------------------
-- 2. staff_service_category - 服务人员擅长服务类别（多对多）
--    派单候选优先级第二档依据：擅长该工单类别的人员。
-- -----------------------------------------------------------------------------
CREATE TABLE staff_service_category (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '绑定ID',
    staff_id BIGINT UNSIGNED NOT NULL COMMENT '服务人员用户ID',
    category_id BIGINT UNSIGNED NOT NULL COMMENT '服务类别ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
    UNIQUE KEY uk_staff_category (staff_id, category_id),
    INDEX idx_staff_category_category (category_id),
    FOREIGN KEY (staff_id) REFERENCES sys_user(id),
    FOREIGN KEY (category_id) REFERENCES service_category(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务人员擅长服务类别表';

-- -----------------------------------------------------------------------------
-- 3. staff_schedule - 服务人员排班（一人一社区一天一条）
--    shift_type 决定默认起止时间，start_time/end_time 为落库快照，
--    便于后续按班次时段做派单提示与在岗校验；
--    REST（休息）行同样落库，用于「明确休息」与「未排班」区分。
-- -----------------------------------------------------------------------------
CREATE TABLE staff_schedule (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY COMMENT '排班ID',
    staff_id BIGINT UNSIGNED NOT NULL COMMENT '服务人员用户ID',
    community_id BIGINT UNSIGNED NOT NULL COMMENT '排班社区ID',
    work_date DATE NOT NULL COMMENT '排班日期',
    shift_type VARCHAR(20) NOT NULL COMMENT '班次：MORNING-早班, AFTERNOON-午班, EVENING-晚班, FULL-全天, REST-休息',
    start_time TIME NULL COMMENT '班次开始时间（REST 为空）',
    end_time TIME NULL COMMENT '班次结束时间（REST 为空）',
    remark VARCHAR(255) NULL COMMENT '备注（如代班、调休说明）',
    created_by BIGINT UNSIGNED NULL COMMENT '排班人用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_staff_schedule (staff_id, community_id, work_date),
    INDEX idx_staff_schedule_community_date (community_id, work_date),
    FOREIGN KEY (staff_id) REFERENCES sys_user(id),
    FOREIGN KEY (community_id) REFERENCES community(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务人员排班表';

-- -----------------------------------------------------------------------------
-- 4. 存量数据回填：按既有派单流水推导「人员-社区」绑定，
--    使已在服务的固定对接关系开箱可见，避免上线后候选全空。
-- -----------------------------------------------------------------------------
INSERT INTO staff_community (staff_id, community_id)
SELECT DISTINCT a.assignee_id, w.community_id
FROM work_order_assignment a
JOIN work_order w ON w.id = a.work_order_id
WHERE a.assignee_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM staff_community sc
      WHERE sc.staff_id = a.assignee_id AND sc.community_id = w.community_id
  );
