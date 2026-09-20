package com.community.residence.housing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.HousingTimeslot;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.messaging.service.WebSocketSessionService;
import com.community.residence.reservation.entity.ResourceReservation;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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
 * DEF-062 修复回归（预约人日程冲突，「影分身」拦截）：居民创建看房预约时，
 * 同账号同日与任意房源的看房预约（跨房源）或任意资源的资源预约（跨域）
 * 时间区间重叠即拒 5807；不重叠/非活跃记录放行；游客预约无账号跳过检查。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("看房预约日程冲突回归（DEF-062）")
class ViewingScheduleConflictTest {

    private static final long RESIDENT_ID = 5L;

    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private com.community.residence.auth.mapper.SysUserMapper sysUserMapper;
    @Mock
    private com.community.residence.auth.mapper.SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private ResourceReservationMapper resourceReservationMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private com.community.residence.messaging.service.NotificationService notificationService;
    @Mock
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    @Mock
    private WebSocketSessionService webSocketSessionService;
    @Mock
    private com.community.residence.conversation.service.ConversationService conversationService;

    @InjectMocks
    private ViewingAppointmentService service;

    private Housing housing;

    /* lambda 列解析依赖 TableInfo（wrapper.getSqlSegment() 物化参数需要） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ViewingAppointment.class);
        TableInfoHelper.initTableInfo(assistant, ResourceReservation.class);
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
        housing = new Housing();
        housing.setId(1L);
        housing.setCommunityId(1L);
        housing.setStatus("AVAILABLE");
        lenient().when(housingMapper.selectById(1L)).thenReturn(housing);
        /* 模板覆盖 08:00-18:00：请求区间必然被并集覆盖（聚焦日程冲突，不测覆盖） */
        lenient().when(timeslotMapper.selectList(any())).thenReturn(List.of(template()));
    }

    @Test
    @DisplayName("跨房源同刻：同账号他房源看房预约 10:00-11:00，再约本房源拒 5807")
    void create_crossHousingSameTime_throws5807() {
        /* appointmentMapper.selectCount 两次调用：第 1 次 = 同房源重叠检查（0），
           第 2 次 = 跨房源日程冲突（命中他房源预约 1）；跨域无资源预约 */
        when(appointmentMapper.selectCount(any())).thenReturn(0L, 1L);
        lenient().when(resourceReservationMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> invokeResidentCreate(LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VIEWING_SCHEDULE_CONFLICT))
                .hasMessageContaining("该时间段您已有其他预约安排");
    }

    @Test
    @DisplayName("跨域同刻（C12→C7）：同账号既有资源预约 10:00-11:00，再约看房拒 5807")
    void create_crossDomainResourceSameTime_throws5807() {
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(resourceReservationMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> invokeResidentCreate(LocalTime.of(10, 0), LocalTime.of(11, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VIEWING_SCHEDULE_CONFLICT))
                .hasMessageContaining("请调整时间");
    }

    @Test
    @DisplayName("同日不重叠：既有安排 13:00-14:00，约 10:00-11:00 放行")
    void create_sameDayNonOverlap_ok() {
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(resourceReservationMapper.selectCount(any())).thenReturn(0L);
        when(appointmentMapper.insert(any(ViewingAppointment.class))).thenReturn(1);

        ViewingAppointmentVO vo = invokeResidentCreate(LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
    }

    @Test
    @DisplayName("已取消/已违约记录不算冲突：日程查询带活跃状态集过滤（CANCELLED/VIOLATED 不命中）放行")
    void create_cancelledOrViolatedNotConflicting() {
        /* 既有夹具为本人 CANCELLED/VIOLATED 的预约——日程查询须带活跃状态集
           IN 条件（看房 TO_CONFIRM/RESERVED、资源 PENDING/RESERVED）才会在
           SQL 侧滤掉它们（映射到 selectCount=0）；下方 ArgumentCaptor 断言
           两域查询确实携带活跃集过滤 */
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(resourceReservationMapper.selectCount(any())).thenReturn(0L);
        when(appointmentMapper.insert(any(ViewingAppointment.class))).thenReturn(1);

        ViewingAppointmentVO vo = invokeResidentCreate(LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
        /* 跨房源日程查询（第 2 次 selectCount）带 TO_CONFIRM/RESERVED 活跃集，
           不含 CANCELLED/VIOLATED 非活跃状态（否则已取消记录会被误判冲突）。
           参数在 SQL 渲染时物化，须先调 getSqlSegment() 再读 paramNameValuePairs */
        ArgumentCaptor<LambdaQueryWrapper<ViewingAppointment>> viewingCaptor =
                wrapperCaptor(ViewingAppointment.class);
        verify(appointmentMapper, org.mockito.Mockito.times(2)).selectCount(viewingCaptor.capture());
        List<LambdaQueryWrapper<ViewingAppointment>> viewingWrappers = viewingCaptor.getAllValues();
        viewingWrappers.forEach(LambdaQueryWrapper::getSqlSegment);
        Collection<Object> viewingValues =
                viewingWrappers.get(1).getParamNameValuePairs().values();
        assertThat(viewingValues).contains("TO_CONFIRM", "RESERVED");
        assertThat(viewingValues).doesNotContain("CANCELLED", "VIOLATED");
        /* 跨域资源查询带 PENDING/RESERVED 活跃集 */
        ArgumentCaptor<LambdaQueryWrapper<ResourceReservation>> resourceCaptor =
                wrapperCaptor(ResourceReservation.class);
        verify(resourceReservationMapper).selectCount(resourceCaptor.capture());
        resourceCaptor.getValue().getSqlSegment();
        Collection<Object> resourceValues =
                resourceCaptor.getValue().getParamNameValuePairs().values();
        assertThat(resourceValues).contains("PENDING", "RESERVED");
        assertThat(resourceValues).doesNotContain("CANCELLED", "VIOLATED");
    }

    /** LambdaQueryWrapper 捕获器（泛型擦除，Class 直取） */
    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<LambdaQueryWrapper<T>> wrapperCaptor(Class<T> entityType) {
        return ArgumentCaptor.forClass((Class<LambdaQueryWrapper<T>>) (Class<?>) LambdaQueryWrapper.class);
    }

    @Test
    @DisplayName("游客预约（userId=null）跳过日程冲突检查：仅同房源冲突检测，不触达两域日程查询")
    void create_guest_skipsScheduleCheck() {
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(appointmentMapper.insert(any(ViewingAppointment.class))).thenReturn(1);

        ViewingAppointmentVO vo = invokeGuestCreate(LocalTime.of(10, 0), LocalTime.of(11, 0));

        assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
        /* 日程冲突检查被跳过：两域查询均不触达；同房源冲突检查恰好一次 */
        verify(appointmentMapper, never()).selectList(any());
        verify(resourceReservationMapper, never()).selectCount(any());
        verify(appointmentMapper, org.mockito.Mockito.times(1)).selectCount(any());
    }

    /* ---- 脚手架 ---- */

    /** 居民路径创建（SecurityUtils 返回 RESIDENT 上下文，走 user_id 绑定分支） */
    private ViewingAppointmentVO invokeResidentCreate(LocalTime start, LocalTime end) {
        UserContext context = new UserContext(RESIDENT_ID, "resident1",
                RoleConstants.RESIDENT, Set.of());
        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(context);
            mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
            mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);
            return service.create(dto(start, end));
        }
    }

    /** 游客路径创建（SecurityUtils.getUser 返回 null 走 visitor 分支） */
    private ViewingAppointmentVO invokeGuestCreate(LocalTime start, LocalTime end) {
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

    /** 模板覆盖 08:00-18:00，请求区间必然被并集覆盖 */
    private HousingTimeslot template() {
        HousingTimeslot t = new HousingTimeslot();
        t.setId(1L);
        t.setHousingId(1L);
        t.setDayOfWeek(LocalDate.now().plusDays(3).getDayOfWeek().getValue());
        t.setStartTime(LocalTime.of(8, 0));
        t.setEndTime(LocalTime.of(18, 0));
        t.setIsAvailable(1);
        return t;
    }
}
