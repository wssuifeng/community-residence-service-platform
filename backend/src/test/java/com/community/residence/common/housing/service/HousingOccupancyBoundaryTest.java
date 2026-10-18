package com.community.residence.common.housing.service;

import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.housing.mapper.HousingTimeslotMapper;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.common.housing.mapper.ViewingMessageMapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.GlobalExceptionHandler;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.exception.UnauthorizedException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;

@ExtendWith(MockitoExtension.class)
class HousingOccupancyBoundaryTest {
    @org.mockito.Mock private com.community.residence.common.community.service.HouseImageService houseImageService;
    @Mock HousingMapper housingMapper;
    @Mock ViewingAppointmentMapper appointmentMapper;
    @InjectMocks HousingService service;
    private Housing listing() { var h=new Housing(); h.setId(1L); h.setHouseId(2L); h.setCommunityId(3L); h.setStatus("AVAILABLE"); return h; }
    @Test void occupiedDetailAndSlotsAreNotPublic() {
        when(housingMapper.selectById(1L)).thenReturn(listing());
        try (var security=mockStatic(SecurityUtils.class)) {
            assertThatThrownBy(() -> service.getById(1L)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.recordView(1L)).isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> service.availableSlots(1L, LocalDate.now(),null)).isInstanceOf(ResourceNotFoundException.class);
        }
    }
    @Test void explicitOfflineFilterCannotBypassPublicPredicate() {
        when(housingMapper.selectPage(any(),any())).thenAnswer(i -> {
            var wrapper=(com.baomidou.mybatisplus.core.conditions.Wrapper<?>)i.getArgument(1);
            assertThat(wrapper.getSqlSegment()).contains(HousingOccupancyRules.PUBLIC);
            return new Page<Housing>(1,20);
        });
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),""),Housing.class);
        try (var security=mockStatic(SecurityUtils.class)) { service.page(1,20,null,"OFFLINE",null,null,null,null,null); }
    }
    @Test void historicalOccupancyPreventsPhysicalDelete() {
        when(housingMapper.selectById(1L)).thenReturn(listing()); when(housingMapper.rentalHistoryCount(1L)).thenReturn(1L);
        try (var security=mockStatic(SecurityUtils.class)) {
            assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BusinessException.class).hasMessageContaining("只能下线");
            verify(housingMapper,never()).deleteById(anyLong());
        }
    }
}
