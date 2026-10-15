package com.community.residence.notice.service;
import com.community.residence.notice.entity.*;
import com.community.residence.notice.mapper.*;
import com.community.residence.notice.dto.ReviseNoticeDTO;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.community.service.ResourceImagePolicy;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.any;
@ExtendWith(MockitoExtension.class)
class NoticeRevisionServiceTest {
 @Mock NoticeMapper notices;@Mock NoticeRevisionMapper revisions;@Mock NoticeService noticeService;@Mock ResourceImagePolicy imagePolicy;
 @InjectMocks NoticeRevisionService service;Notice n;ReviseNoticeDTO dto;
 @BeforeEach void setup(){n=new Notice();n.setId(1L);n.setTitle("旧标题");n.setContent("旧正文");n.setStatus("PUBLISHED");n.setContentVersion(1);n.setPublishTime(LocalDateTime.now().minusDays(1));n.setEndTime(LocalDateTime.now().plusDays(30));n.setViewCount(8);when(notices.lock(1L)).thenReturn(n);dto=new ReviseNoticeDTO();dto.setTitle("新标题");dto.setExpectedVersion(1);dto.setBodyJson("{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"新正文\"}]}]}");}
 @Test void reviseKeepsPublishingWindowAndReadersAndSnapshotsOldAndNew(){try(var auth=mockStatic(SecurityUtils.class)){auth.when(SecurityUtils::getUserId).thenReturn(9L);var publish=n.getPublishTime();var end=n.getEndTime();service.revise(1L,dto);assertThat(n.getContentVersion()).isEqualTo(2);assertThat(n.getStatus()).isEqualTo("PUBLISHED");assertThat(n.getViewCount()).isEqualTo(8);assertThat(n.getPublishTime()).isEqualTo(publish);assertThat(n.getEndTime()).isEqualTo(end);var c=ArgumentCaptor.forClass(NoticeRevision.class);verify(revisions,times(2)).insert(c.capture());assertThat(c.getAllValues()).extracting(NoticeRevision::getContent).containsExactly("旧正文","新正文");verify(noticeService,never()).publish(any(),any());}}
 @Test void staleEditorDoesNotOverwriteOrCreateHistory(){n.setContentVersion(2);assertThatThrownBy(()->service.revise(1L,dto)).hasMessageContaining("他人修订");verify(notices,never()).updateById(any(Notice.class));verifyNoInteractions(revisions,imagePolicy);}
 @Test void draftsAndOutsideCommunityAreDenied(){n.setStatus("DRAFT");assertThatThrownBy(()->service.revise(1L,dto)).hasMessageContaining("仅修订已发布");n.setStatus("PUBLISHED");doThrow(new ForbiddenException("无权管理")).when(noticeService).checkManageAccess(n);assertThatThrownBy(()->service.revise(1L,dto)).isInstanceOf(ForbiddenException.class);verify(notices,never()).updateById(any(Notice.class));}
 @Test void invalidStructuredDocumentHasNoWriteSideEffects(){dto.setBodyJson("{\"type\":\"html\"}");assertThatThrownBy(()->service.revise(1L,dto)).hasMessageContaining("正文格式");verifyNoInteractions(revisions,imagePolicy);verify(notices,never()).updateById(any(Notice.class));}
}
