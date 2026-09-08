package com.community.residence.community.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.dto.CreateBuildingDTO;
import com.community.residence.community.entity.Building;
import com.community.residence.community.entity.Community;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.UnitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** 楼栋业务逻辑测试：删除保护（有单元不可删）、跨社区迁移拒绝 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BuildingService 单元测试")
class BuildingServiceTest {

    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private CommunityService communityService;

    @InjectMocks
    private BuildingService buildingService;

    private Building building;

    @BeforeEach
    void setUp() {
        building = new Building();
        building.setId(1L);
        building.setCommunityId(1L);
        building.setName("B1");
        building.setFloors(18);
    }

    @Test
    @DisplayName("删除保护：楼栋下存在单元时拒绝删除（BUILDING_REFERENCED 5102）")
    void delete_withUnits_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(buildingMapper.selectById(1L)).thenReturn(building);
            when(unitMapper.selectCount(any())).thenReturn(2L);

            assertThatThrownBy(() -> buildingService.delete(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.BUILDING_REFERENCED));
        }
    }

    @Test
    @DisplayName("删除保护通过：无单元时软删除执行")
    void delete_withoutUnits_softDeletes() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(buildingMapper.selectById(1L)).thenReturn(building);
            when(unitMapper.selectCount(any())).thenReturn(0L);
            when(buildingMapper.softDeleteById(1L)).thenReturn(1);

            buildingService.delete(1L);
        }
    }

    @Test
    @DisplayName("更新楼栋：不允许变更所属社区")
    void update_changeCommunity_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(buildingMapper.selectById(1L)).thenReturn(building);
            CreateBuildingDTO dto = new CreateBuildingDTO();
            dto.setCommunityId(2L);
            dto.setName("B1");
            dto.setFloors(18);

            assertThatThrownBy(() -> buildingService.update(1L, dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许变更所属社区");
        }
    }

    @Test
    @DisplayName("创建楼栋：社区须为运营中（停用社区拒绝）")
    void create_inactiveCommunity_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            Community inactive = new Community();
            inactive.setId(1L);
            inactive.setStatus("INACTIVE");
            when(communityService.requireActiveCommunity(1L)).thenThrow(
                    new BusinessException(ErrorCode.COMMUNITY_INACTIVE));

            CreateBuildingDTO dto = new CreateBuildingDTO();
            dto.setCommunityId(1L);
            dto.setName("B1");
            dto.setFloors(5);

            assertThatThrownBy(() -> buildingService.create(dto))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
