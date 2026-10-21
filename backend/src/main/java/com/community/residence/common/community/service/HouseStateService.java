package com.community.residence.common.community.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.community.constant.HouseStatusConstant;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.entity.HouseStatusHistory;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.entity.ResidenceApplication;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.web.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Objects;

/** 所有房屋状态入口共用房屋锁，正式居住状态只从有效主关系派生。 */
@Service
@RequiredArgsConstructor
public class HouseStateService {
    private final HouseMapper houses;
    private final HouseStatusHistoryMapper histories;
    private final ResidenceRelationMapper relations;
    private final ResidenceApplicationMapper applications;
    private final LeaseRecordMapper leases;

    /** 空房可人工维护/停用/恢复；正式或临时占用期间须先完成原业务链。 */
    public void validateManual(House house, String target) {
        if (!HouseStatusConstant.isManual(target)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "入住状态由有效主居住关系产生，请通过入住登记或租约办理");
        }
        if (!relations.selectList(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId, house.getId()).isNull(ResidenceRelation::getMoveOutDate)
                .last("FOR UPDATE")).isEmpty()
                || !applications.selectList(new LambdaQueryWrapper<ResidenceApplication>()
                    .eq(ResidenceApplication::getHouseId, house.getId()).eq(ResidenceApplication::getStatus, "PENDING")
                    .last("FOR UPDATE")).isEmpty()
                || !leases.selectBlockingIdsForUpdate(house.getId()).isEmpty()) {
            throw new BusinessException(ErrorCode.HOUSE_NOT_VACANT, "房屋存在居住关系或临时占用，请先完成相关业务");
        }
    }

    /* 已在业务事务内更新关系后重算，同房屋锁保证看到本事务和此前提交的最新关系。 */
    @Transactional(rollbackFor = Exception.class)
    public void refresh(Long houseId, String remark) {
        House house = houses.selectByIdForUpdate(houseId);
        if (house == null) return;
        var active = relations.selectList(new LambdaQueryWrapper<ResidenceRelation>()
                .eq(ResidenceRelation::getHouseId, houseId).isNull(ResidenceRelation::getMoveOutDate)
                .in(ResidenceRelation::getRelationType, "OWNER", "TENANT")
                .le(ResidenceRelation::getMoveInDate, LocalDate.now()).last("FOR UPDATE"));
        boolean tenant = active.stream().anyMatch(r -> "TENANT".equals(r.getRelationType()));
        boolean owner = active.stream().anyMatch(r -> "OWNER".equals(r.getRelationType()));
        String target = tenant ? HouseStatusConstant.TENANT_OCCUPIED : owner ? HouseStatusConstant.OWNER_OCCUPIED
                : (HouseStatusConstant.MAINTENANCE.equals(house.getStatus()) || HouseStatusConstant.INACTIVE.equals(house.getStatus()))
                    ? house.getStatus() : HouseStatusConstant.VACANT;
        if (Objects.equals(target, house.getStatus())) return;
        String previous = house.getStatus();
        house.setStatus(target); houses.updateById(house);
        HouseStatusHistory history = new HouseStatusHistory(); history.setHouseId(houseId);
        history.setCommunityId(house.getCommunityId()); history.setOldStatus(previous); history.setNewStatus(target);
        history.setOperatorId(SecurityUtils.getUser() == null ? 0L : SecurityUtils.getUserId());
        history.setRemark(remark); histories.insert(history);
    }
}
