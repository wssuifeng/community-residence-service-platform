package com.community.residence.common.community.service;
import com.community.residence.common.community.dto.BatchCommunityIdsDTO;
import com.community.residence.common.community.dto.BatchCommunityStatusDTO;
import com.community.residence.common.community.dto.BatchCreateBuildingsDTO;
import com.community.residence.common.community.dto.BatchCreateHousesDTO;
import com.community.residence.common.community.dto.BatchCreateTimeslotDTO;
import com.community.residence.common.community.dto.BatchCreateUnitsDTO;
import com.community.residence.common.community.dto.BatchHouseAttributesDTO;
import com.community.residence.common.community.dto.BatchHouseLayoutDTO;
import com.community.residence.common.community.dto.CreateBuildingDTO;
import com.community.residence.common.community.dto.CreateCommunityDTO;
import com.community.residence.common.community.dto.CreateHouseDTO;
import com.community.residence.common.community.dto.CreateResourceDTO;
import com.community.residence.common.community.dto.CreateTimeSlotDTO;
import com.community.residence.common.community.dto.CreateUnitDTO;
import com.community.residence.common.community.dto.StructureBatchGenerateDTO;
import com.community.residence.common.community.dto.UpdateCommunityStatusDTO;
import com.community.residence.common.community.dto.UpdateHouseStatusDTO;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.vo.HouseVO;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class CommunityStructureContractTest {
 @Test void thousandFloorBuildingHasNoFixedBusinessCap() {
  CreateBuildingDTO dto=new CreateBuildingDTO();dto.setCommunityId(1L);dto.setName("1000层楼栋");dto.setFloors(1000);
  try(var factory=Validation.buildDefaultValidatorFactory()){assertThat(factory.getValidator().validate(dto)).isEmpty();}
 }
 @Test void thousandFloorStructureHasNoFixedFloorCap() {
  StructureBatchGenerateDTO dto=new StructureBatchGenerateDTO();dto.setCommunityId(1L);dto.setBuildingStartNo(1);dto.setBuildingEndNo(1);dto.setFloorsPerUnit(1000);
  try(var factory=Validation.buildDefaultValidatorFactory()){assertThat(factory.getValidator().validate(dto)).noneMatch(v->v.getPropertyPath().toString().equals("floorsPerUnit"));}
 }
 @Test void roomCountIsRemovedAcrossContracts(){for(Class<?> type:new Class<?>[]{House.class,CreateHouseDTO.class,HouseVO.class,StructureBatchGenerateDTO.class}){assertThat(type.getDeclaredFields()).noneMatch(f->f.getName().equals("roomCount"));}}
 @Test void communityPersistsAdministratorIdentityInsteadOfFreeText(){for(Class<?> type:new Class<?>[]{Community.class,CreateCommunityDTO.class}){assertThat(type.getDeclaredFields()).anyMatch(f->f.getName().equals("responsibleAdminId"));}}
 @Test void nullableAdministratorAndLayoutAreActuallyCleared() throws Exception {
  for (var field : java.util.List.of(Community.class.getDeclaredField("responsibleAdminId"), House.class.getDeclaredField("layout"))) {
   assertThat(field.getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class).updateStrategy()).isEqualTo(com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS);
  }
 }
 @Test void layoutRouteUsesTheActualControllerPrefix() throws Exception {
  var type=com.community.residence.admin.community.controller.AdminHouseController.class;
  var method=type.getDeclaredMethod("updateLayout",BatchHouseLayoutDTO.class);
  String route=type.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0]+method.getAnnotation(org.springframework.web.bind.annotation.PatchMapping.class).value()[0];
  assertThat(route).isEqualTo("/api/v1/houses/layout");
 }
}
