package com.community.residence.resident.service;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.*;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.resident.dto.MoveOutDTO;
import com.community.residence.resident.entity.ResidenceRelation;
import com.community.residence.resident.mapper.*;
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
    private final com.community.residence.lease.service.LeaseService leaseService=mock(com.community.residence.lease.service.LeaseService.class);
    private final ResidenceRelationService service=new ResidenceRelationService(relations,mock(ResidentMapper.class),houses,mock(UnitMapper.class),mock(BuildingMapper.class),leases,leaseService,mock(ResidenceApplicationMapper.class));
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
    @Test void moveOutTerminatesActiveLeaseAndVacatesOnlyEmptyHouse(){
        admin();var r=relation();when(relations.selectById(1L)).thenReturn(r);when(relations.selectCount(any())).thenReturn(0L);
        var lease=new LeaseRecord();lease.setId(20L);lease.setStatus("ACTIVE");when(leases.selectList(any())).thenReturn(List.of(lease));
        var house=new House();house.setId(3L);house.setStatus("OCCUPIED");when(houses.selectById(3L)).thenReturn(house);
        service.moveOut(1L,dto());assertThat(r.getMoveOutDate()).isEqualTo(dto().getMoveOutDate());assertThat(house.getStatus()).isEqualTo("VACANT");verify(leaseService).applyTransition(lease,"MOVED_OUT","居住关系迁出登记");
    }
    @Test void remainingCohabitantKeepsHouseOccupied(){
        admin();when(relations.selectById(1L)).thenReturn(relation());when(relations.selectCount(any())).thenReturn(1L);when(leases.selectList(any())).thenReturn(List.of());
        service.moveOut(1L,dto());verify(houses, never()).updateById(any(House.class));
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
