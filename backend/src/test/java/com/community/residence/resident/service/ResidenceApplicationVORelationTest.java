package com.community.residence.resident.service;

import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.entity.House;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.conversation.service.ConversationService;
import com.community.residence.housing.mapper.HousingMapper;
import com.community.residence.lease.entity.LeaseRecord;
import com.community.residence.lease.mapper.LeaseRecordMapper;
import com.community.residence.lease.service.LeaseChangeLogService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.resident.entity.ResidenceApplication;
import com.community.residence.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.vo.ApplicationVO;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入住申请 → 租约 强关联（V26 / DEF-080）读路径回归：
 * `ApplicationVO.leaseId` 取申请上写死的记录列，**不再按"同一居民/社区/房屋取最新"反查**——
 * 同屋多租约（续租/重签/退租后重租）时旧口径会指向另一份租约（居民端跳错）。
 * 存量 NULL 行按裁决口径**返回空、不回退猜测**。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("入住申请 VO 租约强关联（V26/DEF-080）")
class ResidenceApplicationVORelationTest {

    private static final Long APPLICATION_ID = 5L;
    private static final Long RESIDENT_ID = 1L;
    private static final Long COMMUNITY_ID = 1L;
    private static final Long HOUSE_ID = 12L;
    private static final Long LINKED_LEASE_ID = 33L;
    /** 同屋另一份"更新的"租约：修复前弱关联反查会命中它（错指） */
    private static final Long NEWER_LEASE_ID = 90L;
    private static final Long CONVERSATION_ID = 44L;

    @Mock
    private ResidenceApplicationMapper applicationMapper;
    @Mock
    private ResidenceRelationMapper relationMapper;
    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private BuildingMapper buildingMapper;
    @Mock
    private LeaseRecordMapper leaseRecordMapper;
    @Mock
    private HousingMapper housingMapper;
    @Mock
    private com.community.residence.housing.mapper.ViewingAppointmentMapper viewingAppointmentMapper;
    @Mock
    private LeaseChangeLogService changeLogService;
    @Mock
    private CommunityService communityService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private ResidenceApplicationService applicationService;

    private ResidenceApplication application;

    @BeforeEach
    void setUp() {
        application = new ResidenceApplication();
        application.setId(APPLICATION_ID);
        application.setResidentId(RESIDENT_ID);
        application.setCommunityId(COMMUNITY_ID);
        application.setHouseId(HOUSE_ID);
        application.setRelationType("TENANT");
        application.setStatus("APPROVED");
        when(applicationMapper.selectById(APPLICATION_ID)).thenReturn(application);
        House house = new House();
        house.setId(HOUSE_ID);
        house.setHouseNumber("101");
        when(houseMapper.selectById(HOUSE_ID)).thenReturn(house);
        when(unitMapper.selectById(any())).thenReturn(null);
    }

    private MockedStatic<SecurityUtils> loginAsResident() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(RESIDENT_ID);
        mocked.when(() -> SecurityUtils.hasRole(anyString())).thenReturn(false);
        mocked.when(() -> SecurityUtils.hasRole(RoleConstants.RESIDENT)).thenReturn(true);
        return mocked;
    }

    @Test
    @DisplayName("①同屋多租约：返回「本申请生成的那份」而不是同屋最新租约（对照修复前错指）")
    void detail_sameHouseMultipleLeases_returnsLinkedLease() {
        application.setLeaseId(LINKED_LEASE_ID);
        LeaseRecord linked = new LeaseRecord();
        linked.setId(LINKED_LEASE_ID);
        linked.setConversationId(CONVERSATION_ID);
        when(leaseRecordMapper.selectById(LINKED_LEASE_ID)).thenReturn(linked);

        try (MockedStatic<SecurityUtils> mocked = loginAsResident()) {
            ApplicationVO vo = applicationService.getById(APPLICATION_ID);

            assertThat(vo.getLeaseId()).isEqualTo(LINKED_LEASE_ID);
            assertThat(vo.getConversationId()).isEqualTo(CONVERSATION_ID);
            /* 关键：读路径不得走"同屋最新租约"弱关联反查 */
            verify(leaseRecordMapper, never()).selectOne(any());
        }
    }

    @Test
    @DisplayName("②存量 NULL 行：leaseId/conversationId 均返回空，且不触发弱关联反查（不回退猜测）")
    void detail_nullLink_returnsEmptyWithoutFallback() {
        application.setLeaseId(null);

        try (MockedStatic<SecurityUtils> mocked = loginAsResident()) {
            ApplicationVO vo = applicationService.getById(APPLICATION_ID);

            assertThat(vo.getLeaseId()).isNull();
            assertThat(vo.getConversationId()).isNull();
            assertThat(vo.getId()).isEqualTo(APPLICATION_ID);
            verify(leaseRecordMapper, never()).selectOne(any());
            verify(leaseRecordMapper, never()).selectById(any());
        }
    }

    @Test
    @DisplayName("③关联租约已删（悬空）：conversationId 为空且不抛异常（读路径健壮）")
    void detail_danglingLink_returnsLeaseIdButNoConversation() {
        application.setLeaseId(LINKED_LEASE_ID);
        when(leaseRecordMapper.selectById(LINKED_LEASE_ID)).thenReturn(null);

        try (MockedStatic<SecurityUtils> mocked = loginAsResident()) {
            ApplicationVO vo = applicationService.getById(APPLICATION_ID);

            assertThat(vo.getLeaseId()).isEqualTo(LINKED_LEASE_ID);
            assertThat(vo.getConversationId()).isNull();
        }
    }

    @Test
    @DisplayName("④列表页同口径：逐行按各自 lease_id 取会话，互不串号")
    void page_eachRowUsesOwnLink() {
        ResidenceApplication other = new ResidenceApplication();
        other.setId(6L);
        other.setResidentId(RESIDENT_ID);
        other.setCommunityId(COMMUNITY_ID);
        other.setHouseId(HOUSE_ID);
        other.setRelationType("TENANT");
        other.setStatus("APPROVED");
        other.setLeaseId(NEWER_LEASE_ID);
        application.setLeaseId(LINKED_LEASE_ID);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<ResidenceApplication> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        page.setRecords(List.of(application, other));
        page.setTotal(2);
        when(applicationMapper.selectPage(any(), any())).thenReturn(page);
        LeaseRecord linked = new LeaseRecord();
        linked.setId(LINKED_LEASE_ID);
        linked.setConversationId(CONVERSATION_ID);
        LeaseRecord newer = new LeaseRecord();
        newer.setId(NEWER_LEASE_ID);
        newer.setConversationId(77L);
        when(leaseRecordMapper.selectById(LINKED_LEASE_ID)).thenReturn(linked);
        when(leaseRecordMapper.selectById(NEWER_LEASE_ID)).thenReturn(newer);

        try (MockedStatic<SecurityUtils> mocked = loginAsResident()) {
            var result = applicationService.page(1, 10, null);

            assertThat(result.getRecords()).hasSize(2);
            assertThat(result.getRecords().get(0).getLeaseId()).isEqualTo(LINKED_LEASE_ID);
            assertThat(result.getRecords().get(0).getConversationId()).isEqualTo(CONVERSATION_ID);
            assertThat(result.getRecords().get(1).getLeaseId()).isEqualTo(NEWER_LEASE_ID);
            assertThat(result.getRecords().get(1).getConversationId()).isEqualTo(77L);
        }
    }

    @Test
    @DisplayName("⑤申请不存在：404（回归保护）")
    void detail_applicationNotFound() {
        when(applicationMapper.selectById(999L)).thenReturn(null);
        try (MockedStatic<SecurityUtils> mocked = loginAsResident()) {
            assertThatThrownBy(() -> applicationService.getById(999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("⑥VO.from 透传 lease_id（实体→VO 不丢关联）")
    void voFrom_copiesLeaseId() {
        application.setLeaseId(LINKED_LEASE_ID);
        assertThat(ApplicationVO.from(application).getLeaseId()).isEqualTo(LINKED_LEASE_ID);
    }
}
