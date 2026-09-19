package com.community.residence.housing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.reservation.vo.AvailableSlotVO;
import com.community.residence.resident.mapper.ResidentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
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
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-045 修复回归：今日已过时段不过滤（R55 时段语义）。
 * 服务端权威两处：availableSlots 展示侧 end<=now 剔除 + 创建侧同口径拒绝（5804）；
 * 明日及以后时段不受影响。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("看房过去时段过滤修复回归（DEF-045）")
class HousingPastSlotFixTest {

    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    @InjectMocks
    private HousingService housingService;

    @InjectMocks
    private ViewingAppointmentService appointmentService;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Housing.class);
        TableInfoHelper.initTableInfo(assistant, HousingTimeslot.class);
        TableInfoHelper.initTableInfo(assistant, ViewingAppointment.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            lenient().when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private HousingTimeslot slot(int dayOfWeek, LocalTime start, LocalTime end) {
        HousingTimeslot s = new HousingTimeslot();
        s.setId(1L);
        s.setHousingId(1L);
        s.setDayOfWeek(dayOfWeek);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setIsAvailable(1);
        return s;
    }

    private void stubHousing() {
        Housing housing = new Housing();
        housing.setId(1L);
        housing.setCommunityId(1L);
        housing.setStatus("AVAILABLE");
        lenient().when(housingMapper.selectById(1L)).thenReturn(housing);
    }

    /* ---- 展示侧：availableSlots 过滤 ---- */

    @Test
    @DisplayName("今日已结束时段（end<=now）不返回——原样返回导致游客可约已过去时段")
    void availableSlots_todayEndedSlot_excluded() {
        LocalTime now = LocalTime.now();
        assumeThat(now.getHour()).as("跨午夜时段用例不稳定，01:00 前跳过").isGreaterThanOrEqualTo(1);
        LocalDate today = LocalDate.now();
        stubHousing();
        when(timeslotMapper.selectList(any())).thenReturn(List.of(
                slot(today.getDayOfWeek().getValue(), now.minusMinutes(90), now.minusMinutes(30))));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, today, today);

        assertThat(result).as("今日已结束时段应被剔除而非标 FULL").isEmpty();
    }

    @Test
    @DisplayName("今日未结束时段（end>now）正常返回 AVAILABLE")
    void availableSlots_todayUpcomingSlot_returned() {
        LocalTime now = LocalTime.now();
        assumeThat(now.getHour()).as("跨午夜时段用例不稳定，22:00 后跳过").isLessThanOrEqualTo(21);
        LocalDate today = LocalDate.now();
        stubHousing();
        when(timeslotMapper.selectList(any())).thenReturn(List.of(
                slot(today.getDayOfWeek().getValue(), now.plusMinutes(30), now.plusMinutes(90))));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, today, today);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("明日及以后不受影响：同款已过时刻模板明日仍返回")
    void availableSlots_tomorrow_unaffected() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        stubHousing();
        when(timeslotMapper.selectList(any())).thenReturn(List.of(
                slot(tomorrow.getDayOfWeek().getValue(), LocalTime.of(9, 0), LocalTime.of(10, 0))));
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        List<AvailableSlotVO> result = housingService.availableSlots(1L, tomorrow, tomorrow);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDate()).isEqualTo(tomorrow);
        assertThat(result.get(0).getStatus()).isEqualTo("AVAILABLE");
    }

    /* ---- 创建侧：过去时段拒绝（服务端权威，只改展示不治本无效） ---- */

    @Test
    @DisplayName("创建预约：今日已结束时段 → 业务码 5804 拒绝")
    void create_todayEndedSlot_rejected() {
        LocalTime now = LocalTime.now();
        assumeThat(now.getHour()).as("跨午夜时段用例不稳定，01:00 前跳过").isGreaterThanOrEqualTo(1);
        stubHousing();
        CreateViewingAppointmentDTO dto = new CreateViewingAppointmentDTO();
        dto.setHousingId(1L);
        dto.setAppointmentDate(LocalDate.now());
        dto.setStartTime(now.minusMinutes(90));
        dto.setEndTime(now.minusMinutes(30));
        dto.setContactPhone("13800001111");
        dto.setVisitorName("游客甲");

        assertThatThrownBy(() -> appointmentService.create(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAST_SLOT))
                .hasMessageContaining("不能预约已过去的时段");
        assertThat(ErrorCode.PAST_SLOT.getCode()).isEqualTo(5804);
    }

    @Test
    @DisplayName("创建预约：明日同款时段正常创建（日期非今天不走过去时段校验）")
    void create_tomorrowSlot_ok() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        stubHousing();
        when(timeslotMapper.selectList(any())).thenReturn(List.of(
                slot(tomorrow.getDayOfWeek().getValue(), LocalTime.of(9, 0), LocalTime.of(10, 0))));
        when(appointmentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(appointmentMapper.insert(any(ViewingAppointment.class))).thenReturn(1);

        CreateViewingAppointmentDTO dto = new CreateViewingAppointmentDTO();
        dto.setHousingId(1L);
        dto.setAppointmentDate(tomorrow);
        dto.setStartTime(LocalTime.of(9, 0));
        dto.setEndTime(LocalTime.of(10, 0));
        dto.setContactPhone("13800001111");
        dto.setVisitorName("游客甲");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(null);

            ViewingAppointmentVO vo = appointmentService.create(dto);

            assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
        }
        ArgumentCaptor<ViewingAppointment> captor = ArgumentCaptor.forClass(ViewingAppointment.class);
        verify(appointmentMapper).insert(captor.capture());
        assertThat(captor.getValue().getAppointmentDate()).isEqualTo(tomorrow);
    }
}
