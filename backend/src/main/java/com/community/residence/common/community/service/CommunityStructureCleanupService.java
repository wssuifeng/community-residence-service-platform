package com.community.residence.common.community.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.community.entity.HouseStatusHistory;
import com.community.residence.common.community.entity.ResourceTimeslot;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.common.community.mapper.PublicResourceMapper;
import com.community.residence.common.community.mapper.ResourceTimeslotMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 社区删除时的community域清理；参加管理端协调事务，不能独立提交。 */
@Service
@RequiredArgsConstructor
@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY,rollbackFor=Exception.class)
public class CommunityStructureCleanupService {
    private final BuildingMapper buildingMapper;
    private final UnitMapper unitMapper;
    private final HouseMapper houseMapper;
    private final HouseStatusHistoryMapper houseStatusHistoryMapper;
    private final PublicResourceMapper publicResourceMapper;
    private final ResourceTimeslotMapper resourceTimeslotMapper;
    /* 按原级联顺序清理本域，所有条件来自已确认社区或其受限关联ID。 */
    public void removeStructures(Long id) {
        /* ---- C1 结构（V1 #2~7）：房屋状态历史 → 资源时段 → 房屋 → 单元 → 楼栋 → 资源
                （四张结构表带 @TableLogic 软删除，级联须物理删除以解除 FK 引用，R6 物理删除语义） ---- */
        houseStatusHistoryMapper.delete(new LambdaQueryWrapper<HouseStatusHistory>()
                .eq(HouseStatusHistory::getCommunityId, id));
        resourceTimeslotMapper.delete(new LambdaQueryWrapper<ResourceTimeslot>()
                .eq(ResourceTimeslot::getCommunityId, id));
        houseMapper.physicalDeleteByCommunityId(id);
        unitMapper.physicalDeleteByCommunityId(id);
        buildingMapper.physicalDeleteByCommunityId(id);
        publicResourceMapper.physicalDeleteByCommunityId(id);

    }
}
