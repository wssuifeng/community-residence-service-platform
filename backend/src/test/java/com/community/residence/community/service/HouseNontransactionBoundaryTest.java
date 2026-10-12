package com.community.residence.community.service;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.*;
import com.community.residence.community.dto.CreateHouseDTO;
import com.community.residence.community.dto.UpdateHouseStatusDTO;
import com.community.residence.community.entity.*;
import com.community.residence.community.mapper.*;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 核心房屋非交易保护：楼栋边界委托、归属不可迁移、删除与重复状态。 */
@ExtendWith(MockitoExtension.class)
class HouseNontransactionBoundaryTest {
 @Mock HouseMapper houseMapper; @Mock UnitMapper unitMapper; @Mock HouseStatusHistoryMapper historyMapper;
 @Mock ResidenceRelationMapper residenceRelationMapper; @Mock BuildingBoundaryService buildingBoundaryService; @Mock HouseLayoutService houseLayoutService;
 @InjectMocks HouseService service;
 private House house(){House h=new House();h.setId(1L);h.setUnitId(2L);h.setCommunityId(3L);h.setHouseNumber("101");h.setStatus("VACANT");return h;}
 private CreateHouseDTO dto(){CreateHouseDTO d=new CreateHouseDTO();d.setUnitId(2L);d.setHouseNumber("101");d.setFloor(1);d.setArea(BigDecimal.valueOf(60));d.setLayout("三室一厅一卫");return d;}
 private Unit unit(){Unit u=new Unit();u.setId(2L);u.setCommunityId(3L);u.setBuildingId(4L);u.setName("真实单元");return u;}
 @Test void missingHouseAndUnitDoNotWrite(){when(houseMapper.selectById(1L)).thenReturn(null);when(unitMapper.selectById(2L)).thenReturn(null);assertThatThrownBy(()->service.requireHouse(1L)).isInstanceOf(ResourceNotFoundException.class);assertThatThrownBy(()->service.create(dto())).isInstanceOf(ResourceNotFoundException.class);verify(houseMapper,never()).insert(any(House.class));}
 @Test void cannotMoveHouseBetweenUnits(){when(houseMapper.selectById(1L)).thenReturn(house());CreateHouseDTO d=dto();d.setUnitId(99L);assertThatThrownBy(()->service.update(1L,d)).isInstanceOf(BusinessException.class);verify(houseMapper,never()).updateById(any(House.class));verifyNoInteractions(houseLayoutService,buildingBoundaryService);}
 @Test void duplicateNumberDoesNotWrite(){House h=house();h.setId(9L);when(unitMapper.selectById(2L)).thenReturn(unit());when(houseMapper.selectOne(any())).thenReturn(h);try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.create(dto())).isInstanceOf(BusinessException.class);verify(houseMapper,never()).insert(any(House.class));}}
 @Test void createUsesUnitCommunityAndValidatesItsBoundary(){Unit u=unit();when(unitMapper.selectById(2L)).thenReturn(u);try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)){var r=service.create(dto());assertThat(r.getCommunityId()).isEqualTo(3L);assertThat(r.getStatus()).isEqualTo("VACANT");assertThat(r.getLayout()).isEqualTo("三室一厅一卫");verify(buildingBoundaryService).validateFloor(u,1);verify(houseMapper).insert(any(House.class));}}
 @Test void ownHouseNumberAllowedAndLayoutSynchronizes(){House h=house();Unit u=unit();when(houseMapper.selectById(1L)).thenReturn(h);when(houseMapper.selectOne(any())).thenReturn(h);when(unitMapper.selectById(2L)).thenReturn(u);try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)){service.update(1L,dto());verify(houseLayoutService).synchronize(1L,"三室一厅一卫");verify(houseMapper).updateById(h);}}
 @Test void occupiedHouseDeletionRejected(){when(houseMapper.selectById(1L)).thenReturn(house());when(residenceRelationMapper.selectCount(any())).thenReturn(1L);try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.delete(1L)).isInstanceOf(BusinessException.class);verify(houseMapper,never()).softDeleteById(any());}}
 @Test void emptyHouseDeletionIsSoftOnly(){when(houseMapper.selectById(1L)).thenReturn(house());when(residenceRelationMapper.selectCount(any())).thenReturn(0L);try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)){service.delete(1L);verify(houseMapper).softDeleteById(1L);}}
 @Test void repeatedStatusIsNoOpWithoutDuplicateHistory(){when(houseMapper.selectById(1L)).thenReturn(house());UpdateHouseStatusDTO d=new UpdateHouseStatusDTO();d.setStatus("VACANT");try(MockedStatic<SecurityUtils> s=mockStatic(SecurityUtils.class)){service.updateStatus(1L,d);verify(houseMapper,never()).updateById(any(House.class));verifyNoInteractions(historyMapper);}}
 @Test void houseViewResolvesRealUnitName(){when(houseMapper.selectById(1L)).thenReturn(house());when(unitMapper.selectById(2L)).thenReturn(unit());assertThat(service.getById(1L).getUnitName()).isEqualTo("真实单元");}
 @Test void missingUnitNameDoesNotInventOne(){when(houseMapper.selectById(1L)).thenReturn(house());assertThat(service.getById(1L).getUnitName()).isNull();}
}
