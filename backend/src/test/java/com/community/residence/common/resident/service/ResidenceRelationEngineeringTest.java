package com.community.residence.common.resident.service;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
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
import com.community.residence.common.resident.dto.MoveOutDTO;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.auth.mapper.SysConfigMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** DEF-146迁出分支及管理员授权范围分页总数。 */
class ResidenceRelationEngineeringTest {
    private final ResidenceRelationMapper relations=mock(ResidenceRelationMapper.class);
    private final HouseMapper houses=mock(HouseMapper.class);
    private final LeaseRecordMapper leases=mock(LeaseRecordMapper.class);
    private final com.community.residence.common.lease.service.LeaseService leaseService=mock(com.community.residence.common.lease.service.LeaseService.class);
    private final com.community.residence.common.community.service.HouseStateService houseStates=mock(com.community.residence.common.community.service.HouseStateService.class);
    private final ResidenceRelationService service=new ResidenceRelationService(new ResidenceDependencyService(relations), relations,mock(ResidentMapper.class),houses,houseStates,mock(UnitMapper.class),mock(BuildingMapper.class),leases,leaseService,mock(ResidenceApplicationMapper.class));
    @AfterEach void clear(){SecurityUtils.clear();}
    private ResidenceRelation relation(){var r=new ResidenceRelation();r.setId(1L);r.setResidentId(7L);r.setHouseId(3L);r.setCommunityId(5L);return r;}
    private MoveOutDTO dto(){var dto=new MoveOutDTO();dto.setMoveOutDate(LocalDate.of(2026,10,11));return dto;}
    private void admin(){SecurityUtils.setAuthentication(new UserContext(9L,"test","ADMIN",Set.of(5L)));}
    @Test void adminPaginationPreservesDatabaseScopedTotal(){
        admin();Page<ResidenceRelation> page=new Page<>(1,1,2);page.setRecords(List.of(relation()));when(relations.selectPage(any(),any())).thenReturn(page);
        var result=service.pageByResident(7L,1,1,null);assertThat(result.getTotal()).isEqualTo(2);assertThat(result.getRecords()).hasSize(1);
    }
    @Test void adminWithoutBindingsHasEmptyPageWithoutQuery(){
        SecurityUtils.setAuthentication(new UserContext(9L,"test","ADMIN",Set.of()));
        var result=service.pageByResident(7L,1,1,null);assertThat(result.getTotal()).isZero();verifyNoInteractions(relations);
    }
    @Test void residentCannotReadOtherResidentsRelations(){
        SecurityUtils.setAuthentication(new UserContext(8L,"test","RESIDENT",Set.of()));
        assertThatThrownBy(()->service.pageByResident(7L,1,20,null)).isInstanceOf(ForbiddenException.class);verifyNoInteractions(relations);
    }
    @Test void moveOutTerminatesActiveLeaseAndRecalculatesHouseState(){
        admin();var r=relation();when(relations.selectById(1L)).thenReturn(r);when(relations.selectCount(any())).thenReturn(0L);
        var lease=new LeaseRecord();lease.setId(20L);lease.setStatus("ACTIVE");when(leases.selectList(any())).thenReturn(List.of(lease));
        service.moveOut(1L,dto());assertThat(r.getMoveOutDate()).isEqualTo(dto().getMoveOutDate());
        verify(leaseService).applyTransition(lease,"MOVED_OUT","居住关系迁出登记");
        verify(houseStates).refresh(3L,"居住关系迁出后重算房屋状态");
    }
    @Test void remainingCohabitantKeepsHouseOccupied(){
        admin();when(relations.selectById(1L)).thenReturn(relation());when(relations.selectCount(any())).thenReturn(1L);when(leases.selectList(any())).thenReturn(List.of());
        service.moveOut(1L,dto());verify(houseStates).refresh(3L,"居住关系迁出后重算房屋状态");
    }
    @Test void repeatMoveOutDoesNotWriteAgain(){
        admin();var r=relation();r.setMoveOutDate(LocalDate.of(2026,10,10));when(relations.selectById(1L)).thenReturn(r);
        assertThatThrownBy(()->service.moveOut(1L,dto())).isInstanceOf(BusinessException.class);verify(relations,never()).updateById(any(ResidenceRelation.class));verifyNoInteractions(leases,houses);
    }
    @Test void crossCommunityMoveOutDoesNotWrite(){
        SecurityUtils.setAuthentication(new UserContext(9L,"test","ADMIN",Set.of(6L)));when(relations.selectById(1L)).thenReturn(relation());
        assertThatThrownBy(()->service.moveOut(1L,dto())).isInstanceOf(ForbiddenException.class);verify(relations,never()).updateById(any(ResidenceRelation.class));verifyNoInteractions(leases,houses);
    }
    @Test void ambiguousHistoricalLeaseDoesNotMoveAnyRelation() {
        var relation=relation(); when(relations.selectById(1L)).thenReturn(relation);
        when(leases.selectList(any())).thenReturn(List.of(new LeaseRecord(),new LeaseRecord()));
        assertThatThrownBy(() -> service.moveOut(1L,dto())).isInstanceOf(BusinessException.class);
        verify(relations,never()).updateById(any(ResidenceRelation.class)); verifyNoInteractions(leaseService);
    }

}
