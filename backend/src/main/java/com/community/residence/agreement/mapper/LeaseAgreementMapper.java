package com.community.residence.agreement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.agreement.entity.LeaseAgreement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/* 租约协议：按租约查协议流水（含已撤回历史），正文快照入库不再回查模板 */
@Mapper
public interface LeaseAgreementMapper extends BaseMapper<LeaseAgreement> {
    /* 先锁租约再锁协议，以当前读串行化撤回与居民同意。 */
    @Select("SELECT id, lease_id, community_id, template_id, template_name, title, content, "
            + "template_file_url, template_file_name, status, tenant_id, tenant_confirm_name, tenant_confirm_time, "
            + "admin_id, admin_confirm_name, admin_confirm_time, cancel_reason, remark, created_by, created_at, "
            + "updated_at FROM lease_agreement WHERE id = #{id} FOR UPDATE")
    LeaseAgreement selectByIdForUpdate(@Param("id") Long id);
}
