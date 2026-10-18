package com.community.residence.common.conversation.service;
import com.community.residence.common.security.context.IdentityPrincipal;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.GlobalExceptionHandler;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.exception.UnauthorizedException;
import com.community.residence.common.conversation.mapper.ConversationInboxMapper;
import com.community.residence.common.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.common.feedback.service.FeedbackService;
import com.community.residence.common.feedback.entity.FeedbackMessage;
import com.community.residence.common.feedback.vo.FeedbackVO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class ConversationInboxServiceTest {
 @Mock ConversationInboxMapper mapper;
 @Mock ConversationService conversations;
 @Mock FeedbackService feedbacks;
 @Mock FeedbackMessageMapper messages;
 @InjectMocks ConversationInboxService service;
 @AfterEach void clear(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 void login(String role){SecurityUtils.setAuthentication(new UserContext(8L,"test",role,Set.of(2L)));}
 @Test void staffCannotReadFeedbackEvenWithSameNumericId(){login("STAFF");assertThatThrownBy(()->service.messages("FEEDBACK",1L,null,null,30)).isInstanceOf(ForbiddenException.class);verifyNoInteractions(feedbacks,mapper);}
 @Test void feedbackReadCannotAcknowledgeForeignMessage(){login("RESIDENT");when(feedbacks.getById(1L)).thenReturn(new FeedbackVO());FeedbackMessage f=new FeedbackMessage();f.setFeedbackId(2L);when(messages.selectById(9L)).thenReturn(f);assertThatThrownBy(()->service.markFeedbackRead(1L,9L)).isInstanceOf(BusinessException.class);verifyNoInteractions(mapper);}
 @Test void initialFeedbackReadHasSeparateIdentitySpace(){login("RESIDENT");when(feedbacks.getById(1L)).thenReturn(new FeedbackVO());service.markFeedbackRead(1L,0L);verify(mapper).advanceFeedbackRead(1L,"RESIDENT",8L,0L);verifyNoInteractions(messages);}
 @Test void managementReadChecksCommunityEvenIfParentMapperIsReplaced(){login("ADMIN");FeedbackVO f=new FeedbackVO();f.setCommunityId(99L);when(feedbacks.getById(1L)).thenReturn(f);assertThatThrownBy(()->service.markFeedbackRead(1L,0L)).isInstanceOf(ForbiddenException.class);verifyNoInteractions(mapper);}
 @Test void historyRejectsTwoDirectionsAndUnknownSources(){login("RESIDENT");assertThatThrownBy(()->service.messages("FEEDBACK",1L,3L,4L,20)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->service.messages("OTHER",1L,null,null,20)).isInstanceOf(BusinessException.class);}
 @Test void listPassesRoleAndBoundCommunitiesBeforePagination(){login("ADMIN");when(mapper.page(any(),eq(0L),eq(30))).thenReturn(List.of());when(mapper.count(any())).thenReturn(0L);service.page(1,30,null," 阳光 ",true);var c=ArgumentCaptor.forClass(ConversationInboxService.Scope.class);verify(mapper).page(c.capture(),eq(0L),eq(30));assertThat(c.getValue().ownerType()).isEqualTo("USER");assertThat(c.getValue().communityIds()).containsExactly(2L);assertThat(c.getValue().keyword()).isEqualTo("阳光");assertThat(c.getValue().unreadOnly()).isTrue();}

 @Test void initialWindowStartsAtFirstUnreadRatherThanSkippingToLatest(){
  login("RESIDENT");when(mapper.readingCursor("CONVERSATION",1L,"RESIDENT",8L)).thenReturn(5L);
  when(mapper.firstUnread("CONVERSATION",1L,5L,"RESIDENT",8L)).thenReturn(12L);
  var row=new com.community.residence.common.conversation.vo.ConversationMessageVO();row.setId(12L);
  when(conversations.messageWindow(1L,null,11L,40)).thenReturn(List.of(row));
  var window=service.messages("CONVERSATION",1L,null,null,40);
  assertThat(window.startAtUnread()).isTrue();assertThat(window.firstUnreadMessageId()).isEqualTo(12L);
  verify(conversations).messageWindow(1L,null,11L,40);
 }
 @Test void noUnreadUsesLatestWindowAndCursorPagesDoNotRelocate(){
  login("RESIDENT");when(mapper.firstUnread("CONVERSATION",1L,0L,"RESIDENT",8L)).thenReturn(null);when(conversations.messageWindow(1L,null,null,40)).thenReturn(List.of());
  var window=service.messages("CONVERSATION",1L,null,null,40);assertThat(window.startAtUnread()).isFalse();
  reset(mapper);when(conversations.messageWindow(1L,10L,null,40)).thenReturn(List.of());
  service.messages("CONVERSATION",1L,10L,null,40);verifyNoInteractions(mapper);
 }
}
