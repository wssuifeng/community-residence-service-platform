package com.community.residence.common.community.service;

import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.vo.CommunityDeletionImpactVO;
import com.community.residence.common.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommunityDeletionSafetyTest {
    @org.mockito.Mock private com.community.residence.common.conversation.mapper.ChatMessageImageMapper chatMessageImageMapper;
    @Mock CommunityMapper mapper;
    @Mock com.community.residence.common.auth.mapper.SysOperationLogMapper logs;
    @InjectMocks com.community.residence.admin.community.service.CommunityDeletionService service;
    private CommunityDeletionImpactVO impact() {
        var i=new CommunityDeletionImpactVO(); i.setCommunityId(2L);i.setCommunityName("删除安全社区");
        i.setHouses(5);i.setRelations(2);i.setLeases(1);return i;
    }
    @Test void previewIncludesHistoryWithoutDeleting() {
        var i=impact();when(mapper.selectDeletionImpact(2L)).thenReturn(i);
        var result=service.deletionImpact(2L);
        assertThat(result.isDeletable()).isTrue();assertThat(result.getRelations()).isEqualTo(2);
        assertThat(result.getConfirmationVersion()).isNotBlank();verify(mapper,never()).deleteById(anyLong());
    }
    @Test void activeRelationsRequireWarningButDoNotProhibitSuperAdminDeletion() {
        var i=impact(); i.setActiveRelations(1); i.setActiveLeases(1);
        i.setPayments(2); i.setRefunds(1); i.setPendingApplications(1);
        when(mapper.selectDeletionImpact(2L)).thenReturn(i);
        assertThat(service.deletionImpact(2L).isDeletable()).isTrue();
        assertThat(service.deletionImpact(2L).getBlockingReason()).isEmpty();
    }
    @Test void missingOrStaleConfirmationNeverDeletes() {
        when(mapper.selectDeletionImpact(2L)).thenReturn(impact());when(mapper.lockForDeletion(2L)).thenReturn(2L);
        assertThatThrownBy(()->service.deleteConfirmed(2L,null,"删除安全社区")).isInstanceOf(BusinessException.class).hasMessageContaining("两次确认");
        assertThatThrownBy(()->service.deleteConfirmed(2L,"old","删除安全社区")).isInstanceOf(BusinessException.class);
        verify(mapper,never()).deleteById(anyLong());
    }
    @Test void incorrectCommunityNameNeverDeletes() {
        when(mapper.selectDeletionImpact(2L)).thenReturn(impact());when(mapper.lockForDeletion(2L)).thenReturn(2L);
        String v=service.deletionImpact(2L).getConfirmationVersion();
        assertThatThrownBy(()->service.deleteConfirmed(2L,v,"错误名称")).isInstanceOf(BusinessException.class).hasMessageContaining("完整社区名称");
        verify(mapper,never()).deleteById(anyLong());
    }
    @Test void changedCountsInvalidatePreview() {
        var i=impact();when(mapper.selectDeletionImpact(2L)).thenReturn(i);
        String before=service.deletionImpact(2L).getConfirmationVersion();i.setRelations(3);
        assertThat(service.deletionImpact(2L).getConfirmationVersion()).isNotEqualTo(before);
    }
    @Test void activeLinksCanBeDeletedAfterNameAndVersionWithAudit() {
        var i=impact();i.setActiveRelations(1);i.setPayments(2);
        when(mapper.selectDeletionImpact(2L)).thenReturn(i);when(mapper.lockForDeletion(2L)).thenReturn(2L);
        var subject=spy(service);doNothing().when(subject).delete(2L);
        String version=subject.deletionImpact(2L).getConfirmationVersion();
        try(var security=mockStatic(com.community.residence.common.security.context.SecurityUtils.class)){security.when(com.community.residence.common.security.context.SecurityUtils::getUserId).thenReturn(1L);subject.deleteConfirmed(2L,version,"删除安全社区");}verify(subject).delete(2L);
        var capture=org.mockito.ArgumentCaptor.forClass(com.community.residence.common.auth.entity.SysOperationLog.class);
        verify(logs).insert(capture.capture());assertThat(capture.getValue().getContent()).contains("\"payments\":2","\"activeRelations\":1");
    }
}
