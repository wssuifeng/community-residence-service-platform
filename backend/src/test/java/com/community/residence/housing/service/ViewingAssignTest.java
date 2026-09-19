package com.community.residence.housing.service;

import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.housing.entity.Housing;
import com.community.residence.housing.entity.ViewingAppointment;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.housing.mapper.HousingTimeslotMapper;
import com.community.residence.housing.mapper.ViewingAppointmentMapper;
import com.community.residence.housing.vo.ViewingAppointmentVO;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.reservation.mapper.ViolationRecordMapper;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
 * R59 带看人分配回归：管理端将看房预约分配给启用状态的 STAFF/ADMIN
 * （禁超管/禁冻结/禁不存在账号，错误码 5805），重复分配允许覆盖；
 * 成功后带看人与预约居民均收到通知（游客预约无账号跳过居民侧通知）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("看房预约带看人分配回归（R59）")
class ViewingAssignTest {

    private static final String TITLE_ASSIGNEE = "您已被分配为看房带看人";
    private static final String TITLE_RESIDENT = "您的看房预约已安排带看人";

    @Mock
    private ViewingAppointmentMapper appointmentMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private HousingTimeslotMapper timeslotMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private com.community.residence.auth.mapper.SysAdminCommunityMapper sysAdminCommunityMapper;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private ViolationRecordMapper violationRecordMapper;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ViewingAppointmentService service;

    private ViewingAppointment appointment;
    private SysUser staff;

    @BeforeEach
    void setUp() {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        try {
            lenient().when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        appointment = new ViewingAppointment();
        appointment.setId(1L);
        appointment.setUserId(5L);
        appointment.setHousingId(1L);
        appointment.setCommunityId(1L);
        appointment.setAppointmentDate(LocalDate.now().plusDays(3));
        appointment.setStatus("RESERVED");
        lenient().when(appointmentMapper.selectById(1L)).thenReturn(appointment);
        lenient().when(appointmentMapper.updateById(any(ViewingAppointment.class))).thenReturn(1);

        staff = new SysUser();
        staff.setId(2L);
        staff.setRealName("李带看");
        staff.setRole("STAFF");
        staff.setStatus("ACTIVE");
        lenient().when(sysUserMapper.selectById(2L)).thenReturn(staff);

        Housing housing = new Housing();
        housing.setId(1L);
        housing.setCommunityId(1L);
        housing.setTitle("测试房源");
        lenient().when(housingMapper.selectById(1L)).thenReturn(housing);
        Resident resident = new Resident();
        resident.setRealName("张居民");
        lenient().when(residentMapper.selectById(5L)).thenReturn(resident);
    }

    @Test
    @DisplayName("分配成功：落库 assigned_staff_id + 双方通知 + VO 回带 assigneeId/assigneeName")
    void assign_success_notifiesBoth() {
        ViewingAppointmentVO vo = assignAsAdmin(1L, 2L);

        assertThat(appointment.getAssignedStaffId()).isEqualTo(2L);
        verify(appointmentMapper).updateById(appointment);
        verify(notificationService).create(eq(2L), eq(1L), eq(TITLE_ASSIGNEE),
                anyString(), anyString(), anyString(), eq(1L));
        verify(notificationService).create(eq(5L), eq(1L), eq(TITLE_RESIDENT),
                anyString(), anyString(), anyString(), eq(1L));
        assertThat(vo.getAssigneeId()).isEqualTo(2L);
        assertThat(vo.getAssigneeName()).isEqualTo("李带看");
    }

    @Test
    @DisplayName("重复分配覆盖：换带看人场景直接改写，不校验旧值")
    void assign_reassign_overrides() {
        appointment.setAssignedStaffId(3L);

        ViewingAppointmentVO vo = assignAsAdmin(1L, 2L);

        assertThat(appointment.getAssignedStaffId()).isEqualTo(2L);
        assertThat(vo.getAssigneeId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("SUPER_ADMIN 不可分配（平台管理不承担带看）：拒绝 5805")
    void assign_superAdmin_rejected() {
        staff.setRole("SUPER_ADMIN");

        assertThatThrownBy(() -> assignAsAdmin(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VIEWING_ASSIGNEE_INVALID))
                .hasMessageContaining("不可分配");
        assertThat(ErrorCode.VIEWING_ASSIGNEE_INVALID.getCode()).isEqualTo(5805);
        verify(appointmentMapper, never()).updateById(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("冻结账号不可分配：拒绝 5805")
    void assign_frozenAccount_rejected() {
        staff.setStatus("FROZEN");

        assertThatThrownBy(() -> assignAsAdmin(1L, 2L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VIEWING_ASSIGNEE_INVALID));
        verify(appointmentMapper, never()).updateById(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("目标账号不存在：404")
    void assign_missingAccount_rejected() {
        when(sysUserMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> assignAsAdmin(1L, 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("目标账号不存在");
    }

    @Test
    @DisplayName("游客预约（无账号）：仅通知带看人，居民侧跳过不报错")
    void assign_guestAppointment_onlyAssigneeNotified() {
        appointment.setUserId(null);

        assignAsAdmin(1L, 2L);

        verify(notificationService).create(eq(2L), eq(1L), eq(TITLE_ASSIGNEE),
                anyString(), anyString(), anyString(), eq(1L));
        verify(notificationService, never()).create(anyLong(), anyLong(),
                eq(TITLE_RESIDENT), anyString(), anyString(), anyString(), anyLong());
    }

    /* ---- 脚手架 ---- */

    private ViewingAppointmentVO assignAsAdmin(Long appointmentId, Long assigneeId) {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));
            mocked.when(SecurityUtils::getCommunityIds).thenReturn(Set.of(1L));
            return service.assign(appointmentId, assigneeId);
        }
    }

    @Test
    @DisplayName("ADMIN 带看人须管辖房源社区：未管辖拒绝 5805 且不落库")
    void assign_adminWithoutCommunity_rejected() {
        SysUser admin = new SysUser();
        admin.setId(9L);
        admin.setRealName("王管理");
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        lenient().when(sysUserMapper.selectById(9L)).thenReturn(admin);
        lenient().when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> assignAsAdmin(1L, 9L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.VIEWING_ASSIGNEE_INVALID))
                .hasMessageContaining("未管辖");
        verify(appointmentMapper, never()).updateById(any(ViewingAppointment.class));
    }

    @Test
    @DisplayName("ADMIN 带看人管辖该社区：分配成功并双方通知")
    void assign_adminWithCommunity_success() {
        SysUser admin = new SysUser();
        admin.setId(9L);
        admin.setRealName("王管理");
        admin.setRole("ADMIN");
        admin.setStatus("ACTIVE");
        lenient().when(sysUserMapper.selectById(9L)).thenReturn(admin);
        com.community.residence.auth.entity.SysAdminCommunity binding =
                new com.community.residence.auth.entity.SysAdminCommunity();
        binding.setAdminId(9L);
        binding.setCommunityId(1L);
        lenient().when(sysAdminCommunityMapper.selectList(any())).thenReturn(List.of(binding));
        lenient().when(sysAdminCommunityMapper.exists(any())).thenReturn(true);

        ViewingAppointmentVO vo = assignAsAdmin(1L, 9L);

        assertThat(appointment.getAssignedStaffId()).isEqualTo(9L);
        verify(notificationService).create(eq(9L), eq(1L), eq(TITLE_ASSIGNEE),
                anyString(), anyString(), anyString(), eq(1L));
        assertThat(vo.getAssigneeName()).isEqualTo("王管理");
    }
}
