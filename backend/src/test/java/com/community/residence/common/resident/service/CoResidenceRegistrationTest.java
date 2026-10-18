package com.community.residence.common.resident.service;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.resident.entity.ResidenceApplication;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.auth.entity.SysConfig;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.auth.mapper.SysConfigMapper;
import com.community.residence.common.resident.dto.AdminCreateRelationDTO;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.mapper.HouseImageMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.common.community.mapper.PublicResourceMapper;
import com.community.residence.common.community.mapper.ResourceTimeslotMapper;
import com.community.residence.common.community.mapper.StructureBusinessCodeMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.LeaseService;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class CoResidenceRegistrationTest {
    @org.mockito.Mock private com.community.residence.common.lease.service.LeaseContractTermService termService;
 @Mock ResidenceDependencyService residenceDependencyService; @Mock ResidenceRelationMapper relationMapper;
 @Mock ResidentMapper residentMapper; @Mock HouseMapper houseMapper; @Mock UnitMapper unitMapper; @Mock BuildingMapper buildingMapper;
 @Mock LeaseRecordMapper leaseRecordMapper; @Mock LeaseService leaseService; @Mock ResidenceApplicationMapper applicationMapper;
 @InjectMocks ResidenceRelationService service;
 House house; ResidenceRelation root; Resident member; AdminCreateRelationDTO dto;
 @BeforeAll static void metadata(){var config=new MybatisConfiguration();for(var c:List.of(House.class,ResidenceRelation.class,LeaseRecord.class))TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config,"test"),c);}
 @BeforeEach void setup(){house=new House();house.setId(100L);house.setCommunityId(5L);house.setUnitId(1L);house.setStatus("OCCUPIED");root=new ResidenceRelation();root.setId(10L);root.setHouseId(100L);root.setResidentId(1L);root.setRelationType("OWNER");root.setMoveInDate(LocalDate.of(2026,10,1));member=new Resident();member.setId(2L);member.setStatus("ACTIVE");dto=new AdminCreateRelationDTO();dto.setResidentId(2L);dto.setHouseId(100L);dto.setAnchorRelationId(10L);dto.setRelationType("FAMILY");dto.setMoveInDate(LocalDate.of(2026,10,2));lenient().when(houseMapper.selectByIdForUpdate(100L)).thenReturn(house);lenient().when(houseMapper.selectById(100L)).thenReturn(house);lenient().when(residentMapper.selectById(any(Long.class))).thenReturn(member);lenient().when(relationMapper.selectList(any())).thenReturn(List.of(root));}
 @Test void registersIndependentMemberWithoutNewLease(){try(var scope=mockStatic(SecurityUtils.class)){service.adminCreate(dto);var c=ArgumentCaptor.forClass(ResidenceRelation.class);verify(relationMapper).insert(c.capture());assertThat(c.getValue().getAnchorRelationId()).isEqualTo(10L);assertThat(c.getValue().getResidentId()).isEqualTo(2L);assertThat(c.getValue().getLeaseId()).isNull();verify(leaseRecordMapper,never()).insert(any(LeaseRecord.class));verify(houseMapper,never()).updateById(any(House.class));}}
 @Test void vacantHouseCannotManufactureDependentRoot(){house.setStatus("VACANT");try(var scope=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(dto)).isInstanceOf(BusinessException.class);verify(relationMapper,never()).insert(any(ResidenceRelation.class));}}
 @Test void memberCannotDependOnAnotherHouse(){dto.setAnchorRelationId(99L);try(var scope=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("主居住关系");}}
 @Test void duplicateActiveRelationIsRejected(){when(relationMapper.selectCount(any())).thenReturn(1L);try(var scope=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("已有");}}
 @Test void cannotJoinBeforeMainRelation(){dto.setMoveInDate(LocalDate.of(2026,9,30));try(var scope=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("日期");}}
 @Test void pendingMainLeaseCannotBypassFirstPayment(){root.setRelationType("TENANT");root.setLeaseId(20L);var lease=new LeaseRecord();lease.setStatus("TO_SIGN");when(leaseRecordMapper.selectById(20L)).thenReturn(lease);try(var scope=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("未正式生效");}}
 @Test void frozenAccountCannotJoin(){member.setStatus("FROZEN");try(var scope=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(dto)).isInstanceOf(BusinessException.class).hasMessageContaining("冻结");}}
}
