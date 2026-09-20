package com.community.residence.housing.service;

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
import static org.mockito.Mockito.when;

/**
 * R60 看房预约区间覆盖校验回归：所选时段须被当日可约模板段并集完整覆盖——
 * 跨两段相邻（prev.end == next.start）连续区间通过，段间有间隙拒绝
 * （原单模板覆盖在合并提交场景误拒，已升级为并集口径）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("看房预约区间覆盖校验回归（R60）")
class ViewingSlotCoverageTest {

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
    @Mock
    private com.community.residence.conversation.service.ConversationService conversationService;

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
        lenient().when(appointmentMapper.selectCount(any())).thenReturn(0L);
        lenient().when(appointmentMapper.insert(
                any(com.community.residence.housing.entity.ViewingAppointment.class))).thenReturn(1);
    }

    @Test
    @DisplayName("跨两段连续区间（09:00-10:00 + 10:00-11:00 合并 09:00-11:00）：通过")
    void create_adjacentSegmentsMerged_ok() {
        stubTemplates(List.of(
                template(LocalTime.of(9, 0), LocalTime.of(10, 0)),
                template(LocalTime.of(10, 0), LocalTime.of(11, 0))));

        ViewingAppointmentVO vo = invokeCreate(LocalTime.of(9, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
    }

    @Test
    @DisplayName("DEF-061 切片后创建合并区间：V6 双段长模板（09:00-12:00）内 09:00-11:00 合并请求通过")
    void create_slicedTemplate_mergedInterval_ok() {
        /* available-slots 已栅格化为 60 分钟切片，用户跨片多选后按合并区间提交
           （09:00+10:00 两片合并 09:00-11:00）：区间覆盖按模板段（非切片）判定，
           单段 09:00-12:00 完整覆盖请求区间 → 放行 */
        stubTemplates(List.of(
                template(LocalTime.of(9, 0), LocalTime.of(12, 0)),
                template(LocalTime.of(14, 0), LocalTime.of(18, 0))));

        ViewingAppointmentVO vo = invokeCreate(LocalTime.of(9, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
    }

    @Test
    @DisplayName("段间有间隙（09:00-10:00 + 10:30-11:30，请求 09:30-11:00）：拒绝")
    void create_gapBetweenSegments_rejected() {
        stubTemplates(List.of(
                template(LocalTime.of(9, 0), LocalTime.of(10, 0)),
                template(LocalTime.of(10, 30), LocalTime.of(11, 30))));

        assertThatThrownBy(() -> invokeCreate(LocalTime.of(9, 30), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在房源可预约时段内");
    }

    @Test
    @DisplayName("段间有间隙（间隙在请求中部，恰好 120 分钟请求跨 09:30-10:00 空档）：拒绝")
    void create_gapInsideRequest_rejected() {
        stubTemplates(List.of(
                template(LocalTime.of(8, 0), LocalTime.of(9, 30)),
                template(LocalTime.of(10, 0), LocalTime.of(12, 0))));

        assertThatThrownBy(() -> invokeCreate(LocalTime.of(9, 0), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在房源可预约时段内");
    }

    /* ---- 脚手架：游客路径创建 ---- */

    private void stubTemplates(List<HousingTimeslot> templates) {
        when(timeslotMapper.selectList(any())).thenReturn(templates);
    }

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

    private HousingTimeslot template(LocalTime start, LocalTime end) {
        HousingTimeslot t = new HousingTimeslot();
        t.setId(1L);
        t.setHousingId(1L);
        t.setDayOfWeek(LocalDate.now().plusDays(3).getDayOfWeek().getValue());
        t.setStartTime(start);
        t.setEndTime(end);
        t.setIsAvailable(1);
        return t;
    }
}
