package com.community.residence.notice.service;

import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.mapper.BuildingMapper;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.community.service.CommunityService;
import com.community.residence.messaging.service.NotificationService;
import com.community.residence.notice.entity.Notice;
import com.community.residence.notice.entity.NoticeTarget;
import com.community.residence.notice.mapper.NoticeMapper;
import com.community.residence.notice.mapper.NoticeTargetMapper;
import com.community.residence.notice.mapper.NoticeViewRecordMapper;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DEF-174：阅读回执子接口不得绕过公告目标及管理归属。 */
@ExtendWith(MockitoExtension.class)
class NoticeReadReceiptAccessTest {
    @Mock NoticeMapper noticeMapper;
    @Mock NoticeTargetMapper noticeTargetMapper;
    @Mock NoticeViewRecordMapper viewRecordMapper;
    @Mock CommunityService communityService;
    @Mock SysUserMapper sysUserMapper;
    @Mock ResidentMapper residentMapper;
    @Mock NotificationService notificationService;
    @Mock ResidenceRelationMapper residenceRelationMapper;
    @Mock BuildingMapper buildingMapper;
    @Mock HouseMapper houseMapper;
    @Mock UnitMapper unitMapper;
    @InjectMocks NoticeService service;
    private Notice notice;

    @BeforeEach
    void fixture() {
        org.mockito.Mockito.lenient().when(noticeMapper.updateLifecycle(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
        notice = new Notice(); notice.setId(10L); notice.setStatus("PUBLISHED");
        notice.setEndTime(LocalDateTime.now().plusDays(1)); notice.setViewCount(0);
        when(noticeMapper.selectById(10L)).thenReturn(notice);
    }

    @Test
    void outsideResidentCannotWriteReadReceipt() {
        NoticeTarget target = new NoticeTarget(); target.setTargetType("COMMUNITY"); target.setTargetId(40L);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target));
        when(residenceRelationMapper.selectList(any())).thenReturn(List.of());
        try (MockedStatic<SecurityUtils> auth = mockStatic(SecurityUtils.class)) {
            auth.when(SecurityUtils::getUserId).thenReturn(20L);
            assertThatThrownBy(() -> service.recordView(10L)).isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(viewRecordMapper);
            verify(noticeMapper, never()).updateById(any(Notice.class));
        }
    }

    @Test
    void expiredPublishedNoticeCannotWriteReadReceipt() {
        notice.setEndTime(LocalDateTime.now().minusMinutes(1));
        try (MockedStatic<SecurityUtils> auth = mockStatic(SecurityUtils.class)) {
            auth.when(SecurityUtils::getUserId).thenReturn(20L);
            assertThatThrownBy(() -> service.recordView(10L)).isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(viewRecordMapper);
        }
    }

    @Test
    void outsideAdminCannotReadViewerIdentities() {
        NoticeTarget target = new NoticeTarget(); target.setTargetType("COMMUNITY"); target.setTargetId(40L);
        when(noticeTargetMapper.selectList(any())).thenReturn(List.of(target));
        try (MockedStatic<SecurityUtils> auth = mockStatic(SecurityUtils.class)) {
            auth.when(SecurityUtils::getCommunityIds).thenReturn(Set.of(41L));
            assertThatThrownBy(() -> service.viewers(10L, 1, 20)).isInstanceOf(ForbiddenException.class);
            verifyNoInteractions(viewRecordMapper, residentMapper);
        }
    }
}
