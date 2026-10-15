package com.community.residence.community.service;

import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.community.vo.CommunityDeletionImpactVO;
import com.community.residence.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommunityDeletionSafetyTest {
    @org.mockito.Mock private com.community.residence.conversation.mapper.ChatMessageImageMapper chatMessageImageMapper;
    @Mock CommunityMapper mapper;
    @InjectMocks CommunityService service;
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
    @Test void activeRelationsBlockEvenWithValidConfirmation() {
        var i=impact();i.setActiveRelations(1);when(mapper.selectDeletionImpact(2L)).thenReturn(i);
        when(mapper.lockForDeletion(2L)).thenReturn(2L);
        String version=service.deletionImpact(2L).getConfirmationVersion();
        assertThatThrownBy(()->service.deleteConfirmed(2L,version)).isInstanceOf(BusinessException.class).hasMessageContaining("不可删除");
        verify(mapper,never()).deleteById(anyLong());
    }
    @Test void pendingLeaseApplicationAndFundsIndependentlyBlock() {
        for(int n=0;n<4;n++) {
            var i=impact();if(n==0)i.setActiveLeases(1);if(n==1)i.setPendingApplications(1);if(n==2)i.setPayments(1);if(n==3)i.setRefunds(1);
            when(mapper.selectDeletionImpact(2L)).thenReturn(i);
            assertThat(service.deletionImpact(2L).isDeletable()).isFalse();
        }
    }
    @Test void missingOrStaleConfirmationNeverDeletes() {
        when(mapper.selectDeletionImpact(2L)).thenReturn(impact());when(mapper.lockForDeletion(2L)).thenReturn(2L);
        assertThatThrownBy(()->service.deleteConfirmed(2L,null)).isInstanceOf(BusinessException.class).hasMessageContaining("两次确认");
        assertThatThrownBy(()->service.deleteConfirmed(2L,"old")).isInstanceOf(BusinessException.class);
        verify(mapper,never()).deleteById(anyLong());
    }
    @Test void changedCountsInvalidatePreview() {
        var i=impact();when(mapper.selectDeletionImpact(2L)).thenReturn(i);
        String before=service.deletionImpact(2L).getConfirmationVersion();i.setRelations(3);
        assertThat(service.deletionImpact(2L).getConfirmationVersion()).isNotEqualTo(before);
    }
}
