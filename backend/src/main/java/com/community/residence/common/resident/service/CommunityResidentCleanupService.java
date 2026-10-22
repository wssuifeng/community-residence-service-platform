package com.community.residence.common.resident.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.resident.entity.ResidenceApplication;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 小区删除时的resident域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityResidentCleanupService {
    private final ResidenceApplicationMapper residenceApplicationMapper;
    private final ResidenceRelationMapper residenceRelationMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeApplications(Long id) {
        residenceApplicationMapper.delete(new LambdaQueryWrapper<ResidenceApplication>()
                .eq(ResidenceApplication::getCommunityId, id));
    }
    /* 按原级联顺序清理本域，所有条件来自已确认小区或其受限关联ID。 */
    public void removeRelations(Long id) {
        residenceRelationMapper.delete(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getCommunityId, id));

    }
}
