package com.community.residence.common.community.service;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.community.dto.BatchHouseLayoutDTO;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.entity.Unit;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.housing.mapper.HousingMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class HouseLayoutServiceTest {
 @Mock HouseMapper houseMapper; @Mock UnitMapper unitMapper; @Mock HousingMapper housingMapper; @Mock BuildingBoundaryService buildingBoundaryService; @InjectMocks HouseLayoutService service;
 private House house(long id,long community){House h=new House();h.setId(id);h.setCommunityId(community);h.setUnitId(1L);return h;}
 private BatchHouseLayoutDTO dto(){BatchHouseLayoutDTO d=new BatchHouseLayoutDTO();d.setHouseIds(List.of(2L,1L));d.setLayout("三室一厅一卫");return d;}
 @Test void completeSelectionUpdatesBothTables(){House a=house(1,1),b=house(2,1);Unit u=new Unit();u.setId(1L);u.setBuildingId(3L);when(houseMapper.selectBatchIds(anyCollection())).thenReturn(List.of(a,b));when(unitMapper.selectBatchIds(anyCollection())).thenReturn(List.of(u));when(houseMapper.selectByIdForUpdate(1L)).thenReturn(a);when(houseMapper.selectByIdForUpdate(2L)).thenReturn(b);try(MockedStatic<SecurityUtils> auth=mockStatic(SecurityUtils.class)){assertThat(service.update(dto())).isEqualTo(2);}verify(buildingBoundaryService).lockBuilding(3L);verify(houseMapper).update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));verify(housingMapper).update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));}
 @Test void missingItemRejectsBeforeAnyWrite(){when(houseMapper.selectBatchIds(anyCollection())).thenReturn(List.of(house(1,1)));assertThatThrownBy(()->service.update(dto())).isInstanceOf(ResourceNotFoundException.class);verifyNoInteractions(housingMapper,unitMapper);verify(houseMapper,never()).update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));}
 @Test void unauthorizedItemRejectsWholeSelection(){when(houseMapper.selectBatchIds(anyCollection())).thenReturn(List.of(house(1,1),house(2,2)));try(MockedStatic<SecurityUtils> auth=mockStatic(SecurityUtils.class)){auth.when(()->SecurityUtils.checkCommunityAccess(2L)).thenThrow(new ForbiddenException("越权"));assertThatThrownBy(()->service.update(dto())).isInstanceOf(ForbiddenException.class);}verifyNoInteractions(housingMapper,unitMapper);}
}
