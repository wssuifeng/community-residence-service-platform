package com.community.residence.resident.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.resident.dto.AdminCreateRelationDTO;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.vo.RelationVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 第三批 D-端点3 回归：管理员直建居住关系（不走审批流） */
@ExtendWith(MockitoExtension.class)
@DisplayName("管理员直建居住关系回归（D-端点3）")
class AdminCreateRelationTest {

    @Mock
    private ResidenceRelationMapper relationMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private LeaseRecordMapper leaseRecordMapper;

    @InjectMocks
    private ResidenceRelationService relationService;

    @Test
    @DisplayName("D-端点3：空置房屋直建关系成功——房屋置 OCCUPIED、isPrimary=1")
    void adminCreate_vacantHouse_ok() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(5L);
            org.mockito.Mockito.lenient().when(residentMapper.selectById(10L)).thenReturn(resident());
            when(houseMapper.selectById(101L)).thenReturn(vacantHouse());
            when(relationMapper.selectCount(any())).thenReturn(0L);
            when(relationMapper.insert(any(ResidenceRelation.class))).thenAnswer(inv -> {
                inv.getArgument(0, ResidenceRelation.class).setId(200L);
                return 1;
            });
            /* toVO 装配链（居民/单元/楼栋回填） */
            org.mockito.Mockito.lenient().when(residentMapper.selectById(any())).thenReturn(resident());
            Unit unit = new Unit();
            unit.setId(11L);
            unit.setBuildingId(1L);
            unit.setName("一单元");
            org.mockito.Mockito.lenient().when(unitMapper.selectById(any())).thenReturn(unit);
            Building building = new Building();
            building.setId(1L);
            building.setName("1栋");
            org.mockito.Mockito.lenient().when(buildingMapper.selectById(any())).thenReturn(building);

            RelationVO vo = relationService.adminCreate(dto());

            assertThat(vo.getRelationType()).isEqualTo("OWNER");
            ArgumentCaptor<House> houseCaptor = ArgumentCaptor.forClass(House.class);
            verify(houseMapper).updateById(houseCaptor.capture());
            assertThat(houseCaptor.getValue().getStatus()).isEqualTo("OCCUPIED");
            ArgumentCaptor<ResidenceRelation> relationCaptor =
                    ArgumentCaptor.forClass(ResidenceRelation.class);
            verify(relationMapper).insert(relationCaptor.capture());
            assertThat(relationCaptor.getValue().getIsPrimary()).isEqualTo(1);
            assertThat(relationCaptor.getValue().getCommunityId()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("D-端点3：已入住房屋拒绝 5205")
    void adminCreate_occupiedHouse_rejects() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(5L);
            when(residentMapper.selectById(10L)).thenReturn(resident());
            House occupied = vacantHouse();
            occupied.setStatus("OCCUPIED");
            when(houseMapper.selectById(101L)).thenReturn(occupied);

            assertThatThrownBy(() -> relationService.adminCreate(dto()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.HOUSE_NOT_VACANT));
        }
    }

    @Test
    @DisplayName("D-端点3：ADMIN 越绑定社区 403")
    void adminCreate_outOfBoundCommunity_forbidden() {
        /* CALLS_REAL_METHODS：checkCommunityAccess 真实执行（mockStatic 默认会把它
           连带 mock 成空操作，导致校验形同虚设），仅桩 getUserId/getUser */
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class,
                             org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(5L);
            /* getUser 返回绑定社区 2 的 ADMIN 上下文（校验目标房屋属社区 1） */
            mocked.when(com.community.residence.common.context.SecurityUtils::getUser)
                    .thenReturn(new com.community.residence.common.context.UserContext(
                            5L, "admin1", "ADMIN", java.util.Set.of(2L)));
            when(residentMapper.selectById(10L)).thenReturn(resident());
            when(houseMapper.selectById(101L)).thenReturn(vacantHouse());

            assertThatThrownBy(() -> relationService.adminCreate(dto()))
                    .isInstanceOf(com.community.residence.common.exception.ForbiddenException.class);
        }
    }

    /* ---- 脚手架 ---- */

    private AdminCreateRelationDTO dto() {
        AdminCreateRelationDTO dto = new AdminCreateRelationDTO();
        dto.setResidentId(10L);
        dto.setHouseId(101L);
        dto.setRelationType("OWNER");
        dto.setMoveInDate(LocalDate.now().minusDays(1));
        dto.setRemark("台账补录");
        return dto;
    }

    private Resident resident() {
        Resident resident = new Resident();
        resident.setId(10L);
        resident.setRealName("张三");
        return resident;
    }

    private House vacantHouse() {
        House house = new House();
        house.setId(101L);
        house.setCommunityId(1L);
        house.setUnitId(11L);
        house.setHouseNumber("101");
        house.setStatus("VACANT");
        return house;
    }
}
