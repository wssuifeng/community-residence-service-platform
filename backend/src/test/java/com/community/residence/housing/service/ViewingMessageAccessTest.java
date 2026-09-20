package com.community.residence.housing.service;

import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.housing.dto.CreateViewingMessageDTO;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.entity.ViewingMessage;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.mapper.ViewingMessageMapper;
import com.community.residence.housing.vo.ViewingMessageVO;
import com.community.residence.housing.vo.ViewingPushVO;
import com.community.residence.messaging.service.WebSocketSessionService;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
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
import org.redisson.api.RedissonClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R59 带看会话消息回归：可读=预约居民/带看人/社区 ADMIN/SUPER_ADMIN，
 * 可发=仅预约居民或带看人（管理员可读不可发，除非本人即带看人）；
 * 非参与者 403 100% 拒绝；发送后 WS 推送 /topic/appointment/{id}
 * （载荷 {type:'APPOINTMENT_MESSAGE', data}，复制 R30 模式）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("看房会话消息权限与推送回归（R59）")
class ViewingMessageAccessTest {

    private static final long APPOINTMENT_ID = 1L;
    private static final long RESIDENT_ID = 5L;
    private static final long STAFF_ID = 2L;

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
    private SysUserMapper sysUserMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private WebSocketSessionService webSocketSessionService;
    @Mock
    private com.community.residence.conversation.service.ConversationService conversationService;

    @InjectMocks
    private ViewingAppointmentService service;

    private ViewingAppointment appointment;
    private ViewingMessage stored;

    @BeforeEach
    void setUp() {
        appointment = new ViewingAppointment();
        appointment.setId(APPOINTMENT_ID);
        appointment.setUserId(RESIDENT_ID);
        appointment.setAssignedStaffId(STAFF_ID);
        appointment.setHousingId(1L);
        appointment.setCommunityId(1L);
        appointment.setStatus("RESERVED");
        lenient().when(appointmentMapper.selectById(APPOINTMENT_ID)).thenReturn(appointment);

        stored = new ViewingMessage();
        stored.setId(10L);
        stored.setAppointmentId(APPOINTMENT_ID);
        stored.setSenderId(RESIDENT_ID);
        stored.setContent("明天几点方便？");
        stored.setCreatedAt(LocalDateTime.now());
        lenient().when(messageMapper.selectList(any())).thenReturn(List.of(stored));
        lenient().when(messageMapper.insert(any(ViewingMessage.class))).thenReturn(1);

        Resident resident = new Resident();
        resident.setRealName("张居民");
        lenient().when(residentMapper.selectById(RESIDENT_ID)).thenReturn(resident);
        SysUser staff = new SysUser();
        staff.setId(STAFF_ID);
        staff.setRealName("李带看");
        lenient().when(sysUserMapper.selectById(STAFF_ID)).thenReturn(staff);

        lenient().when(webSocketSessionService.isOnline(anyLong())).thenReturn(false);
    }

    /* ---- 可读口径 ---- */

    @Test
    @DisplayName("非参与居民（非预约人）读：403")
    void list_otherResident_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockUser(9L, "RESIDENT", null)) {
            assertThatThrownBy(() -> service.listMessages(APPOINTMENT_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("非会话参与者");
        }
    }

    @Test
    @DisplayName("非被分配服务人员读：403")
    void list_otherStaff_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockUser(7L, "STAFF", null)) {
            assertThatThrownBy(() -> service.listMessages(APPOINTMENT_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("非会话参与者");
        }
    }

    @Test
    @DisplayName("未绑定社区的 ADMIN 读：403")
    void list_adminWithoutBinding_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockUser(8L, "ADMIN", Set.of())) {
            assertThatThrownBy(() -> service.listMessages(APPOINTMENT_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("非会话参与者");
        }
    }

    @Test
    @DisplayName("预约居民读：成功，时间正序，居民发送人回实姓名")
    void list_ownerResident_ok() {
        try (MockedStatic<SecurityUtils> mocked = mockUser(RESIDENT_ID, "RESIDENT", null)) {
            List<ViewingMessageVO> messages = service.listMessages(APPOINTMENT_ID);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getSenderName()).isEqualTo("张居民");
            assertThat(messages.get(0).getContent()).isEqualTo("明天几点方便？");
        }
    }

    @Test
    @DisplayName("带看人读：成功，sys_user 发送人回姓名")
    void list_assignedStaff_ok() {
        stored.setSenderId(STAFF_ID);
        try (MockedStatic<SecurityUtils> mocked = mockUser(STAFF_ID, "STAFF", null)) {
            List<ViewingMessageVO> messages = service.listMessages(APPOINTMENT_ID);

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).getSenderName()).isEqualTo("李带看");
        }
    }

    @Test
    @DisplayName("未分配预约：社区 ADMIN 可读（空列表），预约居民可读（空列表）")
    void list_unassignedAppointment_adminAndOwnerReadable() {
        appointment.setAssignedStaffId(null);
        when(messageMapper.selectList(any())).thenReturn(List.of());

        try (MockedStatic<SecurityUtils> ignored = mockUser(8L, "ADMIN", Set.of(1L))) {
            assertThat(service.listMessages(APPOINTMENT_ID)).isEmpty();
        }
        try (MockedStatic<SecurityUtils> ignored = mockUser(RESIDENT_ID, "RESIDENT", null)) {
            assertThat(service.listMessages(APPOINTMENT_ID)).isEmpty();
        }
    }

    /* ---- 可发口径 ---- */

    @Test
    @DisplayName("非预约居民发：403")
    void send_otherResident_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockUser(9L, "RESIDENT", null)) {
            assertThatThrownBy(() -> service.sendMessage(APPOINTMENT_ID, dto("好的")))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("仅预约居民或带看人");
        }
    }

    @Test
    @DisplayName("管理员（非带看人）可读不可发：403")
    void send_adminNotAssignee_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockUser(8L, "ADMIN", Set.of(1L))) {
            assertThat(service.listMessages(APPOINTMENT_ID)).hasSize(1);
            assertThatThrownBy(() -> service.sendMessage(APPOINTMENT_ID, dto("好的")))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("仅预约居民或带看人");
        }
    }

    @Test
    @DisplayName("预约居民发送成功：落库 + WS 推送 /topic/appointment/1（type=APPOINTMENT_MESSAGE）")
    void send_ownerResident_pushesWebSocket() {
        when(webSocketSessionService.isOnline(RESIDENT_ID)).thenReturn(true);
        try (MockedStatic<SecurityUtils> mocked = mockUser(RESIDENT_ID, "RESIDENT", null)) {
            ViewingMessageVO vo = service.sendMessage(APPOINTMENT_ID, dto("明天上午方便"));

            assertThat(vo.getSenderId()).isEqualTo(RESIDENT_ID);
            assertThat(vo.getSenderName()).isEqualTo("张居民");
            ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
            verify(messagingTemplate).convertAndSend(eq("/topic/appointment/" + APPOINTMENT_ID),
                    payload.capture());
            assertThat(payload.getValue()).isInstanceOf(ViewingPushVO.class);
            assertThat(((ViewingPushVO) payload.getValue()).getType()).isEqualTo("APPOINTMENT_MESSAGE");
            assertThat(((ViewingPushVO) payload.getValue()).getData()).isInstanceOf(ViewingMessageVO.class);
        }
    }

    @Test
    @DisplayName("带看人发送成功（ADMIN 账号被分配为带看人时同口径可发）")
    void send_assignedStaff_ok() {
        when(webSocketSessionService.isOnline(STAFF_ID)).thenReturn(true);
        try (MockedStatic<SecurityUtils> mocked = mockUser(STAFF_ID, "ADMIN", Set.of(1L))) {
            ViewingMessageVO vo = service.sendMessage(APPOINTMENT_ID, dto("下午三点可以"));

            assertThat(vo.getSenderId()).isEqualTo(STAFF_ID);
            assertThat(vo.getSenderName()).isEqualTo("李带看");
            verify(messagingTemplate).convertAndSend(eq("/topic/appointment/" + APPOINTMENT_ID),
                    any(Object.class));
        }
    }

    @Test
    @DisplayName("双方均离线：不推送（轮询兜底），消息照常落库")
    void send_bothOffline_noPush() {
        try (MockedStatic<SecurityUtils> mocked = mockUser(RESIDENT_ID, "RESIDENT", null)) {
            service.sendMessage(APPOINTMENT_ID, dto("留言"));

            verify(messageMapper).insert(any(ViewingMessage.class));
            verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        }
    }

    /* ---- 脚手架 ---- */

    private CreateViewingMessageDTO dto(String content) {
        CreateViewingMessageDTO dto = new CreateViewingMessageDTO();
        dto.setContent(content);
        return dto;
    }

    /** 以指定身份打开 SecurityUtils 静态桩（communityIds 仅 ADMIN 用） */
    private MockedStatic<SecurityUtils> mockUser(Long userId, String role, Set<Long> communityIds) {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(userId);
        mocked.when(() -> SecurityUtils.hasRole(role)).thenReturn(true);
        mocked.when(SecurityUtils::getCommunityIds)
                .thenReturn(communityIds != null ? communityIds : Set.of());
        return mocked;
    }
}
