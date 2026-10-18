package com.community.residence.common.housing.service;

import com.community.residence.common.housing.dto.CreateHousingTimeslotDTO;
import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.entity.HousingTimeslot;
import com.community.residence.common.housing.mapper.HousingTimeslotMapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.time.LocalTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HousingTimeslotServiceTest {
    private final HousingTimeslotMapper mapper = mock(HousingTimeslotMapper.class);
    private final HousingService housing = mock(HousingService.class);
    private final HousingTimeslotService service = new HousingTimeslotService(mapper, housing);
    @BeforeEach void identity() {
        SecurityUtils.setAuthentication(new UserContext(1L,"test","SUPER_ADMIN",Set.of()));
        var row = new Housing(); row.setCommunityId(2L); when(housing.requireHousing(3L)).thenReturn(row);
    }
    @AfterEach void clear() { SecurityUtils.clear(); }
    private CreateHousingTimeslotDTO input() {
        var dto = new CreateHousingTimeslotDTO(); dto.setDayOfWeek(1);
        dto.setStartTime(LocalTime.of(10,0)); dto.setEndTime(LocalTime.of(11,0)); return dto;
    }
    @Test void invalidTimeDoesNotWrite() {
        var dto=input(); dto.setEndTime(LocalTime.of(9,0));
        assertThatThrownBy(()->service.create(3L,dto)).isInstanceOf(BusinessException.class);
        verify(mapper,never()).insert(any(HousingTimeslot.class));
    }
    @Test void overlappingSlotDoesNotWrite() {
        var row = new HousingTimeslot();row.setId(5L);row.setStartTime(LocalTime.of(10,30));row.setEndTime(LocalTime.of(11,30));
        when(mapper.selectList(any())).thenReturn(List.of(row));
        assertThatThrownBy(()->service.create(3L,input())).isInstanceOf(BusinessException.class);
        verify(mapper,never()).insert(any(HousingTimeslot.class));
    }
    @Test void createPreservesHousingAndDefaultAvailability() {
        when(mapper.selectList(any())).thenReturn(List.of());
        var result=service.create(3L,input());
        assertThat(result.getHousingId()).isEqualTo(3L);assertThat(result.getIsAvailable()).isEqualTo(1);
        verify(mapper).insert(result);
    }
}
