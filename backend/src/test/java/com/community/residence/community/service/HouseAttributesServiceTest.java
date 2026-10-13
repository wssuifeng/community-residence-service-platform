package com.community.residence.community.service;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.dto.BatchHouseAttributesDTO;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
@ExtendWith(MockitoExtension.class)
class HouseAttributesServiceTest {
 @Mock HouseLayoutService houseLayoutService; @Mock HouseMapper houseMapper; @Mock UnitMapper unitMapper; @Mock BuildingBoundaryService boundary;
 @InjectMocks HouseAttributesService service;
 @BeforeAll static void metadata(){TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"test"),House.class);}
 private House house(long id){House h=new House();h.setId(id);h.setUnitId(id+10);return h;}
 private BatchHouseAttributesDTO dto(String...fields){var d=new BatchHouseAttributesDTO();d.setHouseIds(List.of(1L,2L));d.setFields(Set.of(fields));return d;}
 @Test void onlyExplicitLayoutIsWritten(){var d=dto("layout");d.setLayout(" 三室一厅一卫 ");when(houseLayoutService.lockSelection(d.getHouseIds())).thenReturn(List.of(house(1),house(2)));assertThat(service.update(d)).isEqualTo(2);verify(houseLayoutService).synchronize(1L,"三室一厅一卫");verify(houseLayoutService).synchronize(2L,"三室一厅一卫");verifyNoInteractions(unitMapper,boundary);}
 @Test void invalidLastFloorDoesNotWriteFirst(){var d=dto("floor","area");d.setFloor(9);d.setArea(BigDecimal.TEN);when(houseLayoutService.lockSelection(d.getHouseIds())).thenReturn(List.of(house(1),house(2)));Unit a=new Unit();a.setId(11L);Unit b=new Unit();b.setId(12L);when(unitMapper.selectById(11L)).thenReturn(a);when(unitMapper.selectById(12L)).thenReturn(b);doAnswer(inv -> { if (((Unit)inv.getArgument(0)).getId().equals(12L)) throw new BusinessException(com.community.residence.common.constant.ErrorCode.INVALID_PARAM,"该楼栋共6层"); return null; }).when(boundary).validateFloor(any(Unit.class),eq(9));assertThatThrownBy(()->service.update(d)).isInstanceOf(BusinessException.class);verifyNoInteractions(houseMapper);}
 @Test void statusAndRelationsCannotBeModified(){assertThatThrownBy(()->service.update(dto("status"))).isInstanceOf(BusinessException.class);verifyNoInteractions(houseLayoutService,houseMapper);}
 @Test void batchHouseNumberRejected(){var d=dto("houseNumber");d.setHouseNumber("101");assertThatThrownBy(()->service.update(d)).isInstanceOf(BusinessException.class);verifyNoInteractions(houseLayoutService,houseMapper);}
 @Test void explicitEmptyLayoutClearsAndSynchronizes(){var d=dto("layout");when(houseLayoutService.lockSelection(d.getHouseIds())).thenReturn(List.of(house(1),house(2)));service.update(d);verify(houseLayoutService).synchronize(1L,"");}
 @Test void selectedRequiredAreaCannotBeNull(){assertThatThrownBy(()->service.update(dto("area"))).isInstanceOf(BusinessException.class);verifyNoInteractions(houseLayoutService);}
 @Test void singleDuplicateHouseNumberIsBusinessError(){var d=dto("houseNumber");d.setHouseIds(List.of(1L));d.setHouseNumber("102");when(houseLayoutService.lockSelection(d.getHouseIds())).thenReturn(List.of(house(1)));when(houseMapper.selectCount(any())).thenReturn(1L);assertThatThrownBy(()->service.update(d)).isInstanceOf(BusinessException.class).hasMessageContaining("房号");verify(houseMapper,never()).update(isNull(),any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));}
}
