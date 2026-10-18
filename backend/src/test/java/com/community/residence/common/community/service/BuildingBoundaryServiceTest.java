package com.community.residence.common.community.service;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.community.entity.Building;
import com.community.residence.common.community.entity.Unit;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class BuildingBoundaryServiceTest {
 @Mock BuildingMapper buildingMapper; @Mock HouseMapper houseMapper; @InjectMocks BuildingBoundaryService service;
 private Building building(int floors){Building b=new Building();b.setId(1L);b.setCommunityId(1L);b.setFloors(floors);return b;}
 @Test void sixFloorsRejectsNinth(){assertThatThrownBy(()->service.validateLockedFloor(building(6),9)).isInstanceOf(BusinessException.class).hasMessageContaining("1～6");}
 @Test void sixthFloorAllowed(){assertThatCode(()->service.validateLockedFloor(building(6),6)).doesNotThrowAnyException();}
 @Test void thousandthFloorAllowed(){assertThatCode(()->service.validateLockedFloor(building(1000),1000)).doesNotThrowAnyException();}
 @Test void thousandAndFirstRejected(){assertThatThrownBy(()->service.validateLockedFloor(building(1000),1001)).isInstanceOf(BusinessException.class);}
 @Test void increaseFirstAllowsNewFloor(){when(buildingMapper.selectByIdForUpdate(1L)).thenReturn(building(9));Unit u=new Unit();u.setBuildingId(1L);u.setCommunityId(1L);service.validateFloor(u,9);verify(buildingMapper).selectByIdForUpdate(1L);}
 @Test void decreaseRejectsExistingHigherHouse(){when(buildingMapper.selectByIdForUpdate(1L)).thenReturn(building(9));when(houseMapper.selectHighestFloorByBuilding(1L)).thenReturn(9);assertThatThrownBy(()->service.validateReduction(1L,6)).isInstanceOf(BusinessException.class).hasMessageContaining("第9层");}
 @Test void decreaseAllowedAfterHigherHousesRemoved(){when(buildingMapper.selectByIdForUpdate(1L)).thenReturn(building(9));when(houseMapper.selectHighestFloorByBuilding(1L)).thenReturn(6);assertThatCode(()->service.validateReduction(1L,6)).doesNotThrowAnyException();}
 @Test void missingParentFailsClosed(){assertThatThrownBy(()->service.lockBuilding(1L)).isInstanceOf(ResourceNotFoundException.class);}
 @Test void invalidFloorAndMissingFloorRejected(){for(Integer floor:new Integer[]{null,0,-1})assertThatThrownBy(()->service.validateLockedFloor(building(6),floor)).isInstanceOf(BusinessException.class);}
}
