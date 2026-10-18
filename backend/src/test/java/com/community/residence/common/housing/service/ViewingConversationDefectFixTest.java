package com.community.residence.common.housing.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.conversation.constant.ConversationStatus;
import com.community.residence.common.conversation.constant.ConversationType;
import com.community.residence.common.web.constant.ErrorCode;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.conversation.entity.Conversation;
import com.community.residence.common.housing.dto.CreateViewingAppointmentDTO;
import com.community.residence.common.housing.entity.Housing;
import com.community.residence.common.housing.entity.HousingTimeslot;
import com.community.residence.common.housing.entity.ViewingAppointment;
import com.community.residence.common.housing.mapper.HousingMapper;
import com.community.residence.common.housing.mapper.HousingTimeslotMapper;
import com.community.residence.common.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.common.housing.mapper.ViewingMessageMapper;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.reservation.mapper.ResourceReservationMapper;
import com.community.residence.common.reservation.mapper.ViolationRecordMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.auth.service.SysConfigService;
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
 * OCR 静态评审缺陷修复回归（DEF-110 / DEF-118）：
 * 会话内发起看房预约（R69）三处收口——CLOSED 会话拒绝 5604（§9.19.7 错误码
 * 消费点）、传入/推导房源归属租约房屋校验（防跨租约串约）、租约关联看房
 * 预约列表按预约日期降序 + id 降序稳定排序（§9.19.8 契约）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("看房预约链路缺陷修复回归（OCR DEF-110/118）")
class ViewingConversationDefectFixTest {

    private static final long CONVERSATION_ID = 55L;
    private static final long LEASE_ID = 3L;
    private static final long RESIDENT_ID = 5L;

    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private ViewingMessageMapper messageMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private com.community.residence.common.auth.mapper.SysUserMapper sysUserMapper;
    @Mock
    private LeaseRecordMapper leaseMapper;
    @Mock
    private com.community.residence.common.auth.mapper.SysAdminCommunityMapper sysAdminCommunityMapper;
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
    private NotificationService notificationService;
    @Mock
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;
    @Mock
    private com.community.residence.common.messaging.service.WebSocketSessionService webSocketSessionService;
    @Mock
    private com.community.residence.common.conversation.service.ConversationService conversationService;

    @InjectMocks
    private ViewingAppointmentService service;

    private Conversation leaseGroup;
    private LeaseRecord lease;

    /* lambda 列解析依赖 TableInfo（wrapper.getSqlSegment() 物化排序列需要） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
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
        leaseGroup = new Conversation();
        leaseGroup.setId(CONVERSATION_ID);
        leaseGroup.setType(ConversationType.LEASE_GROUP);
        leaseGroup.setRelatedId(LEASE_ID);
        leaseGroup.setStatus(ConversationStatus.ACTIVE);
        lenient().when(conversationService.requireLeaseGroup(CONVERSATION_ID)).thenReturn(leaseGroup);

        lease = new LeaseRecord();
        lease.setId(LEASE_ID);
        lease.setStatus("TO_SIGN");
        lenient().when(housingMapper.selectLeaseSourceHousingIds(LEASE_ID)).thenReturn(List.of(2L));
        lenient().when(housingMapper.privateLeaseViewingAvailabilityCount(anyLong(),anyLong(),anyLong())).thenReturn(1L);
        lease.setTenantId(RESIDENT_ID);
        lease.setCommunityId(1L);
        lease.setHouseId(8L);
        lenient().when(leaseMapper.selectById(LEASE_ID)).thenReturn(lease);

        /* 时长上限放宽为一天：聚焦本回归目标，不与 120 分钟缺省纠缠 */
        lenient().when(sysConfigService.getValue("viewing.max_continuous_minutes"))
                .thenReturn("1440");
    }

    /* ---- DEF-110：关闭态租约群拒绝 5604（§9.19.7 错误码消费点） ---- */

    @Test
    @DisplayName("已关闭租约群聊内发起看房预约被拒 5604，不触达租约/房源查询更不落库")
    void createInConversation_closedGroup_rejected5604() {
        leaseGroup.setStatus(ConversationStatus.CLOSED);

        try (MockedStatic<SecurityUtils> ignored = mockResident()) {
            assertThatThrownBy(() -> service.createInConversation(CONVERSATION_ID, validDto()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.CONVERSATION_CLOSED));
        }
        assertThat(ErrorCode.CONVERSATION_CLOSED.getCode()).isEqualTo(5604);
        /* 校验先于一切读写路径 */
        verify(leaseMapper, never()).selectById(anyLong());
        verify(housingMapper, never()).selectById(anyLong());
        verify(appointmentMapper, never()).insert(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("进行中（ACTIVE）租约群聊内发起：CLOSED 校验不过度拦截，走完整创建链路")
    void createInConversation_activeGroup_resolvedHousing_proceeds() {
        /* 未传 housingId → 按租约房屋 8L 推导挂牌：明确申请来源11L；不按在架/更新时间挑选，推导结果回填请求并进入正常落库链路 */
        Housing available = housingOf(11L, 8L);
        when(housingMapper.selectLeaseSourceHousingIds(LEASE_ID)).thenReturn(List.of(11L));
        when(housingMapper.selectById(11L)).thenReturn(available);
        when(timeslotMapper.selectList(any())).thenReturn(List.of(template()));
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(resourceReservationMapper.selectCount(any())).thenReturn(0L);
        when(appointmentMapper.insert(any(ViewingAppointment.class))).thenReturn(1);

        CreateViewingAppointmentDTO dto = validDto();
        try (MockedStatic<SecurityUtils> ignored = mockResident()) {
            var vo = service.createInConversation(CONVERSATION_ID, dto);

            assertThat(dto.getHousingId()).isEqualTo(11L);
            assertThat(vo.getHousingId()).isEqualTo(11L);
            assertThat(vo.getLeaseId()).isEqualTo(LEASE_ID);
            assertThat(vo.getConversationId()).isEqualTo(CONVERSATION_ID);
            assertThat(vo.getStatus()).isEqualTo("TO_CONFIRM");
        }
        verify(appointmentMapper).insert(any(ViewingAppointment.class));
    }

    /* ---- DEF-118：传入 housingId 归属校验 ---- */

    @Test
    @DisplayName("预约传入与租约房屋不一致的 housingId 被拒 400（他房屋挂牌房源）")
    void createInConversation_housingOfOtherHouse_rejected() {
        /* 租约房屋 8L，传入房源指向房屋 99L：拒绝，租约会话不得沦为任意房源入口 */
        Housing otherHouseHousing = housingOf(2L, 99L);
        when(housingMapper.selectById(2L)).thenReturn(otherHouseHousing);

        try (MockedStatic<SecurityUtils> ignored = mockResident()) {
            assertThatThrownBy(() -> service.createInConversation(CONVERSATION_ID, dtoWithHousingId(2L)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM))
                    .hasMessageContaining("预约房源与租约房屋不一致");
        }
        verify(appointmentMapper, never()).insert(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("归属一致的传入 housingId 放行：租约会话仅可预约本租约房屋的挂牌")
    void createInConversation_housingOfLeaseHouse_accepted() {
        Housing ownHousing = housingOf(2L, 8L);
        when(housingMapper.selectById(2L)).thenReturn(ownHousing);
        when(timeslotMapper.selectList(any())).thenReturn(List.of(template()));
        when(appointmentMapper.selectCount(any())).thenReturn(0L);
        when(resourceReservationMapper.selectCount(any())).thenReturn(0L);
        when(appointmentMapper.insert(any(ViewingAppointment.class))).thenReturn(1);

        try (MockedStatic<SecurityUtils> ignored = mockResident()) {
            var vo = service.createInConversation(CONVERSATION_ID, dtoWithHousingId(2L));

            assertThat(vo.getHousingId()).isEqualTo(2L);
            assertThat(vo.getLeaseId()).isEqualTo(LEASE_ID);
        }
        verify(appointmentMapper).insert(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("传入 housingId 归属校验：租约不存在与解析路径同口径 404")
    void createInConversation_leaseMissing_throws404() {
        when(leaseMapper.selectById(LEASE_ID)).thenReturn(null);

        try (MockedStatic<SecurityUtils> ignored = mockResident()) {
            assertThatThrownBy(() -> service.createInConversation(CONVERSATION_ID, dtoWithHousingId(2L)))
                    .isInstanceOf(com.community.residence.common.web.exception.ResourceNotFoundException.class)
                    .hasMessageContaining("租住记录不存在");
        }
    }

    @Test
    @DisplayName("解析路径同样过归属校验：推导结果指向他房屋（如查询口径被放宽）拒绝 400")
    void createInConversation_resolvedHousingMistargeted_rejected() {
        /* 模拟解析查询返回非本租约房屋的挂牌（当前 eq(house_id) 口径下不可达，
           锁定纵深防御：推导结果仍须显式过归属校验，口径放宽时不依赖隐式前提） */
        Housing mistargeted = housingOf(11L, 99L);
        when(housingMapper.selectLeaseSourceHousingIds(LEASE_ID)).thenReturn(List.of(11L));
        when(housingMapper.selectById(11L)).thenReturn(mistargeted);

        try (MockedStatic<SecurityUtils> ignored = mockResident()) {
            assertThatThrownBy(() -> service.createInConversation(CONVERSATION_ID, validDto()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM))
                    .hasMessageContaining("预约房源与租约房屋不一致");
        }
        verify(appointmentMapper, never()).insert(any(ViewingAppointment.class));
    }

    @Test
    void ambiguousLegacySourceRejectedWithoutGuessing() {
        when(housingMapper.selectLeaseSourceHousingIds(LEASE_ID)).thenReturn(List.of());
        when(housingMapper.selectList(any())).thenReturn(List.of(housingOf(11L,8L),housingOf(12L,8L)));
        try(var auth=mockResident()) {
            assertThatThrownBy(()->service.requireConversationViewingHousing(CONVERSATION_ID,null)).isInstanceOf(BusinessException.class).hasMessageContaining("唯一来源");
        }
        verify(appointmentMapper,never()).insert(any(ViewingAppointment.class));
    }
    @Test
    void privateContextStillRejectsUnavailableLeaseOrAnotherTenant() {
        when(housingMapper.selectById(2L)).thenReturn(housingOf(2L,8L));
        when(housingMapper.privateLeaseViewingAvailabilityCount(2L,LEASE_ID,RESIDENT_ID)).thenReturn(0L);
        try(var auth=mockResident()) {
            assertThatThrownBy(()->service.requireConversationViewingHousing(CONVERSATION_ID,null)).isInstanceOf(BusinessException.class).hasMessageContaining("当前租约来源房源不可预约");
        }
    }
    @Test
    void suppliedDifferentSourceEvenInSameHouseIsRejected() {
        try(var auth=mockResident()) {
            assertThatThrownBy(()->service.requireConversationViewingHousing(CONVERSATION_ID,99L)).isInstanceOf(BusinessException.class).hasMessageContaining("来源挂牌不一致");
        }
    }

    /* ---- DEF-118：租约看房列表排序契约（§9.19.8） ---- */

    @Test
    @DisplayName("租约看房列表排序契约：appointment_date DESC 主序 + id DESC 稳定次序")
    void listByLease_orderByDateThenIdDesc() {
        when(appointmentMapper.selectList(any())).thenReturn(List.of());

        try (MockedStatic<SecurityUtils> ignored = mockResident()) {
            service.listByLease(LEASE_ID);
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<ViewingAppointment>> captor =
                ArgumentCaptor.forClass(
                        (Class<LambdaQueryWrapper<ViewingAppointment>>) (Class<?>) LambdaQueryWrapper.class);
        verify(appointmentMapper).selectList(captor.capture());
        String segment = captor.getValue().getSqlSegment();
        /* 日期降序在前（原仅 id 排序，同日多条时日期乱序）；id 稳定排序在后 */
        assertThat(segment).contains("ORDER BY appointment_date DESC,id DESC");
        assertThat(segment.indexOf("appointment_date DESC"))
                .isLessThan(segment.indexOf("id DESC"));
        /* 过滤口径不变：仍按 lease_id（R69 双向关联） */
        assertThat(segment).contains("lease_id = ");
    }

    /* ---- 脚手架 ---- */

    /** 居民身份（本人租约）：getUser + getUserId + RESIDENT 角色桩 */
    private MockedStatic<SecurityUtils> mockResident() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        UserContext context = new UserContext(RESIDENT_ID, "resident1",
                RoleConstants.RESIDENT, Set.of());
        mocked.when(SecurityUtils::getUser).thenReturn(context);
        mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);
        return mocked;
    }

    /** 合法预约请求（不传 housingId，走租约推导路径） */
    private CreateViewingAppointmentDTO validDto() {
        CreateViewingAppointmentDTO dto = new CreateViewingAppointmentDTO();
        dto.setAppointmentDate(LocalDate.now().plusDays(3));
        dto.setStartTime(LocalTime.of(10, 0));
        dto.setEndTime(LocalTime.of(11, 0));
        dto.setContactPhone("13800001111");
        return dto;
    }

    /** 指定 housingId 的预约请求（走传入归属校验路径） */
    private CreateViewingAppointmentDTO dtoWithHousingId(Long housingId) {
        CreateViewingAppointmentDTO dto = validDto();
        dto.setHousingId(housingId);
        return dto;
    }

    /** 挂牌房源：id + 指向房屋（默认在架） */
    private Housing housingOf(Long id, Long houseId) {
        Housing housing = new Housing();
        housing.setId(id);
        housing.setHouseId(houseId);
        housing.setCommunityId(1L);
        housing.setTitle("测试房源" + id);
        housing.setStatus("AVAILABLE");
        return housing;
    }

    /** 模板覆盖 08:00-18:00，请求区间必然被并集覆盖 */
    private HousingTimeslot template() {
        HousingTimeslot t = new HousingTimeslot();
        t.setId(1L);
        t.setHousingId(2L);
        t.setDayOfWeek(LocalDate.now().plusDays(3).getDayOfWeek().getValue());
        t.setStartTime(LocalTime.of(8, 0));
        t.setEndTime(LocalTime.of(18, 0));
        t.setIsAvailable(1);
        return t;
    }
    @org.junit.jupiter.api.BeforeEach
    void rentalAvailabilityFixture() {
        org.mockito.Mockito.lenient().when(housingMapper.publicAvailabilityCount(org.mockito.ArgumentMatchers.anyLong())).thenReturn(1L);
    }
}
