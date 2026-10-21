package com.community.residence.common.community.service;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.community.entity.*;
import com.community.residence.common.community.mapper.*;
import com.community.residence.common.resident.entity.*;
import com.community.residence.common.resident.mapper.*;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class HouseStateServiceTest {
    @Mock HouseMapper houses; @Mock HouseStatusHistoryMapper histories;
    @Mock ResidenceRelationMapper relations; @Mock ResidenceApplicationMapper applications;
    @Mock LeaseRecordMapper leases; @InjectMocks HouseStateService service;
    House house;
    @BeforeEach void init() {
        var assistant = new MapperBuilderAssistant(new Configuration(), "test");
        for (Class<?> type : List.of(House.class, ResidenceRelation.class, ResidenceApplication.class)) TableInfoHelper.initTableInfo(assistant, type);
        house = new House(); house.setId(9L); house.setCommunityId(1L); house.setStatus("VACANT");
    }
    ResidenceRelation main(String type) { var r = new ResidenceRelation(); r.setRelationType(type); return r; }
    @Test void ownerStateComesFromMainRelationAndIsRecorded() {
        when(houses.selectByIdForUpdate(9L)).thenReturn(house); when(relations.selectList(any())).thenReturn(List.of(main("OWNER")));
        service.refresh(9L, "登记"); assertThat(house.getStatus()).isEqualTo("OWNER_OCCUPIED");
        var history = ArgumentCaptor.forClass(HouseStatusHistory.class); verify(histories).insert(history.capture());
        assertThat(history.getValue().getOldStatus()).isEqualTo("VACANT"); assertThat(history.getValue().getNewStatus()).isEqualTo("OWNER_OCCUPIED");
    }
    @Test void tenantWinsWhenOwnerAndTenantBothHaveEffectiveMainRelations() {
        when(houses.selectByIdForUpdate(9L)).thenReturn(house); when(relations.selectList(any())).thenReturn(List.of(main("OWNER"), main("TENANT")));
        service.refresh(9L, "生效"); assertThat(house.getStatus()).isEqualTo("TENANT_OCCUPIED");
    }
    @Test void mainMoveOutRestoresVacant() {
        house.setStatus("TENANT_OCCUPIED"); when(houses.selectByIdForUpdate(9L)).thenReturn(house); when(relations.selectList(any())).thenReturn(List.of());
        service.refresh(9L, "迁出"); assertThat(house.getStatus()).isEqualTo("VACANT");
    }
    @Test void refreshKeepsMaintenanceAndIsIdempotent() {
        house.setStatus("MAINTENANCE"); when(houses.selectByIdForUpdate(9L)).thenReturn(house); when(relations.selectList(any())).thenReturn(List.of());
        service.refresh(9L, "重算"); verifyNoInteractions(histories); verify(houses, never()).updateById(any(House.class));
    }
    @Test void manualOccupiedIsAlwaysRejected() {
        assertThatThrownBy(() -> service.validateManual(house, "OWNER_OCCUPIED")).hasMessageContaining("主居住关系"); verifyNoInteractions(relations, applications, leases);
    }
    @Test void occupiedHouseCannotEnterMaintenance() {
        when(relations.selectList(any())).thenReturn(List.of(main("OWNER")));
        assertThatThrownBy(() -> service.validateManual(house, "MAINTENANCE")).hasMessageContaining("居住关系或临时占用");
    }
    @Test void pendingApplicationCannotEnterInactive() {
        when(relations.selectList(any())).thenReturn(List.of()); when(applications.selectList(any())).thenReturn(List.of(new ResidenceApplication()));
        assertThatThrownBy(() -> service.validateManual(house, "INACTIVE")).hasMessageContaining("临时占用");
    }
    @Test void pendingLeaseCannotBeReleasedByManualVacant() {
        when(relations.selectList(any())).thenReturn(List.of()); when(applications.selectList(any())).thenReturn(List.of()); when(leases.selectBlockingIdsForUpdate(9L)).thenReturn(List.of(3L));
        assertThatThrownBy(() -> service.validateManual(house, "VACANT")).hasMessageContaining("临时占用");
    }
    @Test void emptyHouseCanBeMaintainedStoppedAndRestored() {
        when(relations.selectList(any())).thenReturn(List.of()); when(applications.selectList(any())).thenReturn(List.of()); when(leases.selectBlockingIdsForUpdate(9L)).thenReturn(List.of());
        for (String state : List.of("MAINTENANCE", "INACTIVE", "VACANT")) service.validateManual(house, state);
    }
}
