package com.community.residence.housing.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R60 连续时长上限回归（看房预约域）：sys_config viewing.max_continuous_minutes
 * （缺省 120）服务端权威校验——API 直调超长请求被拒（消息含上限值，不落库），
 * 恰好 120 分钟通过；配置覆盖生效。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("看房预约连续时长上限回归（R60）")
class ViewingContinuousLimitTest {

    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private ViewingAppointmentService service;

    private Housing housing;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            lenient().when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        housing = new Housing();
        housing.setId(1L);
        housing.setCommunityId(1L);
        housing.setStatus("AVAILABLE");
        housing.setTitle("测试房源");
        lenient().when(housingMapper.selectById(1L)).thenReturn(housing);
    }

    @Test
    @DisplayName("超限 150 分钟（09:00-11:30）：拒绝 5806，消息含上限值，不落库")
    void create_overLimit_rejected() {
        assertThatThrownBy(() -> invokeCreate(LocalTime.of(9, 0), LocalTime.of(11, 30)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VIEWING_DURATION_LIMIT))
                .hasMessageContaining("单次看房预约最长 120 分钟");
        assertThat(ErrorCode.VIEWING_DURATION_LIMIT.getCode()).isEqualTo(5806);
        verify(appointmentMapper, never()).insert(any(com.community.residence.housing.entity.ViewingAppointment.class));
    }

    @Test
    @DisplayName("恰好 120 分钟（09:00-11:00）：放行")
    void create_exactlyLimit_ok() {
        when(timeslotMapper.selectList(any()))
                .thenReturn(List.of(template(LocalDate.now().plusDays(3), LocalTime.of(8, 0), LocalTime.of(14, 0))));
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(appointmentMapper.insert(any(com.community.residence.housing.entity.ViewingAppointment.class)))
                .thenReturn(1);

        ViewingAppointmentVO vo = invokeCreate(LocalTime.of(9, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
    }

    @Test
    @DisplayName("上限可配生效：sys_config=30 时 60 分钟请求亦被拒（消息含 30）")
    void create_configuredLimit_enforced() {
        when(sysConfigService.getValue("viewing.max_continuous_minutes")).thenReturn("30");

        assertThatThrownBy(() -> invokeCreate(LocalTime.of(9, 0), LocalTime.of(10, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VIEWING_DURATION_LIMIT))
                .hasMessageContaining("单次看房预约最长 30 分钟");
    }

    /* ---- 脚手架：游客路径创建（SecurityUtils.getUser 返回 null 走 visitor 分支） ---- */

    private ViewingAppointmentVO invokeCreate(LocalTime start, LocalTime end) {
        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(null);
            return service.create(dto(start, end));
        }
    }

    private CreateViewingAppointmentDTO dto(LocalTime start, LocalTime end) {
        CreateViewingAppointmentDTO dto = new CreateViewingAppointmentDTO();
        dto.setHousingId(1L);
        dto.setAppointmentDate(LocalDate.now().plusDays(3));
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setContactPhone("13800001111");
        dto.setVisitorName("游客甲");
        return dto;
    }

    private HousingTimeslot template(LocalDate date, LocalTime start, LocalTime end) {
        HousingTimeslot t = new HousingTimeslot();
        t.setId(1L);
        t.setHousingId(1L);
        t.setDayOfWeek(date.getDayOfWeek().getValue());
        t.setStartTime(start);
        t.setEndTime(end);
        t.setIsAvailable(1);
        return t;
    }
}
