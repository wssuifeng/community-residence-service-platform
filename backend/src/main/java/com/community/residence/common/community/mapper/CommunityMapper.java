package com.community.residence.common.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.community.entity.Community;
import org.apache.ibatis.annotations.Mapper;

/** 社区数据访问（数据级权限：community 本表按 id 过滤，由拦截器处理） */
@Mapper
public interface CommunityMapper extends BaseMapper<Community> {
    /* 锁父记录，使删除前检查与执行处于同一事务，结构外键新增不能穿越删除。 */
    @org.apache.ibatis.annotations.Select("SELECT id FROM community WHERE id=#{id} FOR UPDATE")
    Long lockForDeletion(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Select("SELECT id FROM residence_relation WHERE community_id=#{id} FOR UPDATE")
    java.util.List<Long> lockRelationsForDeletion(@org.apache.ibatis.annotations.Param("id") Long id);
    @org.apache.ibatis.annotations.Select("SELECT id FROM lease_record WHERE community_id=#{id} FOR UPDATE")
    java.util.List<Long> lockLeasesForDeletion(@org.apache.ibatis.annotations.Param("id") Long id);
    @org.apache.ibatis.annotations.Select("SELECT id FROM residence_application WHERE community_id=#{id} FOR UPDATE")
    java.util.List<Long> lockApplicationsForDeletion(@org.apache.ibatis.annotations.Param("id") Long id);

    /* 单次只读聚合给出真实影响；历史记录不能隐去。 */
    @org.apache.ibatis.annotations.Select("""
        SELECT c.id community_id, c.name community_name,
          (SELECT COUNT(*) FROM house h WHERE h.community_id=c.id) houses,
          (SELECT COUNT(*) FROM residence_relation r WHERE r.community_id=c.id) relations,
          (SELECT COUNT(*) FROM residence_relation r WHERE r.community_id=c.id AND r.move_out_date IS NULL) active_relations,
          (SELECT COUNT(*) FROM lease_record l WHERE l.community_id=c.id) leases,
          (SELECT COUNT(*) FROM lease_record l WHERE l.community_id=c.id AND l.status IN ('PENDING','TO_SIGN','ACTIVE')) active_leases,
          (SELECT COUNT(*) FROM residence_application a WHERE a.community_id=c.id) applications,
          (SELECT COUNT(*) FROM residence_application a WHERE a.community_id=c.id AND a.status='PENDING') pending_applications,
          (SELECT COUNT(*) FROM lease_payment p JOIN lease_record l ON l.id=p.lease_id WHERE l.community_id=c.id) payments,
          (SELECT COUNT(*) FROM lease_deposit_refund p JOIN lease_record l ON l.id=p.lease_id WHERE l.community_id=c.id) refunds
        FROM community c WHERE c.id=#{id}
        """)
    com.community.residence.common.community.vo.CommunityDeletionImpactVO selectDeletionImpact(@org.apache.ibatis.annotations.Param("id") Long id);

    /* 改角色前锁定读引用，避免把正在使用的责任人改成非管理员。 */
    @org.apache.ibatis.annotations.Select("SELECT id FROM community WHERE responsible_admin_id=#{adminId} LIMIT 1 FOR UPDATE")
    Long selectResponsibleReferenceForUpdate(@org.apache.ibatis.annotations.Param("adminId") Long adminId);
}
