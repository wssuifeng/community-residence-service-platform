package com.community.residence.common.feedback.service;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.GlobalExceptionHandler;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.exception.UnauthorizedException;
import com.community.residence.common.upload.service.FileUploadService;
import com.community.residence.common.conversation.service.ChatImageService;
import com.community.residence.common.conversation.vo.ChatImageVO;
import com.community.residence.common.feedback.entity.Feedback;
import com.community.residence.common.feedback.entity.FeedbackAttachment;
import com.community.residence.common.feedback.entity.FeedbackMessage;
import com.community.residence.common.feedback.dto.CreateFeedbackDTO;
import com.community.residence.common.feedback.mapper.FeedbackAttachmentMapper;
import com.community.residence.common.feedback.mapper.FeedbackMapper;
import com.community.residence.common.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.messaging.service.WebSocketSessionService;
import org.junit.jupiter.api.*;
import org.mockito.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class FeedbackPrivacyAndImageTest {
 private final FeedbackMapper feedback=mock(FeedbackMapper.class);
 private final FeedbackMessageMapper messages=mock(FeedbackMessageMapper.class);
 private final ResidentMapper residents=mock(ResidentMapper.class);
 private final ChatImageService images=mock(ChatImageService.class);
 private final WebSocketSessionService online=mock(WebSocketSessionService.class);
 private final SimpMessagingTemplate push=mock(SimpMessagingTemplate.class);
 private final NotificationService notifications=mock(NotificationService.class);
 private final FeedbackService service=new FeedbackService(images,feedback,messages,mock(FeedbackAttachmentMapper.class),residents,mock(SysUserMapper.class),notifications,mock(FileUploadService.class),push,online);
 private Feedback item;
 @BeforeEach void before(){SecurityUtils.setAuthentication(new UserContext(1L,"测试账号","RESIDENT",Set.of()));item=new Feedback();item.setId(3L);item.setResidentId(1L);item.setCommunityId(8L);item.setStatus("PENDING");item.setTitle("测试反馈");when(feedback.selectById(3L)).thenReturn(item);}
 @AfterEach void after(){SecurityContextHolder.clearContext();}
 @Test void anonymousDetailsNeverExposeNameOrAccount(){item.setIsAnonymous(true);var result=service.getById(3L);assertThat(result.getResidentId()).isNull();assertThat(result.getResidentName()).isEqualTo("匿名居民");verifyNoInteractions(residents);}
 @Test void legacyUnknownChoiceIsConservativelyAnonymous(){var result=service.getById(3L);assertThat(result.getIsAnonymous()).isTrue();assertThat(result.getResidentId()).isNull();}
 @Test void explicitNamedChoiceStillShowsName(){item.setIsAnonymous(false);var r=new Resident();r.setRealName("测试居民");when(residents.selectById(1L)).thenReturn(r);assertThat(service.getById(3L).getResidentName()).isEqualTo("测试居民");}
 @Test void createPersistsAnonymousChoice(){var dto=new CreateFeedbackDTO();dto.setCommunityId(8L);dto.setTitle("测试");dto.setContent("描述");dto.setCategory("SUGGESTION");dto.setIsAnonymous(true);service.create(dto);ArgumentCaptor<Feedback> saved=ArgumentCaptor.forClass(Feedback.class);verify(feedback).insert(saved.capture());assertThat(saved.getValue().getIsAnonymous()).isTrue();}
 @Test void imageIsARealMessageAndBroadcastHidesResidentIdentity(){item.setIsAnonymous(true);var file=new MockMultipartFile("file","test.png","image/png",new byte[]{1});var saved=new FileUploadService.UploadResult("a","test.png","/uploads/test.png",1,"IMAGE");when(images.upload(file)).thenReturn(saved);doAnswer(a->{((FeedbackMessage)a.getArgument(0)).setId(11L);return 1;}).when(messages).insert(any(FeedbackMessage.class));var image=new ChatImageVO();image.setFileUrl(saved.fileUrl());when(images.image(ChatImageService.FEEDBACK,11L)).thenReturn(image);when(online.isResidentOnline(1L)).thenReturn(true);
 var result=service.sendImage(3L,file);assertThat(result.getMessageType()).isEqualTo("IMAGE");assertThat(result.getImage().getFileUrl()).isEqualTo(saved.fileUrl());assertThat(result.getSenderId()).isNull();assertThat(result.getSenderName()).isEqualTo("匿名居民");verify(images).attach(ChatImageService.FEEDBACK,11L,8L,saved);
 ArgumentCaptor<Object> payload=ArgumentCaptor.forClass(Object.class);verify(push).convertAndSend(eq("/topic/feedback/3"),payload.capture());assertThat(((com.community.residence.common.feedback.vo.MessageVO)((com.community.residence.common.feedback.vo.FeedbackPushVO)payload.getValue()).getData()).getSenderId()).isNull();
 }
 @Test void closedFeedbackRejectsUploadBeforeFileWrite(){item.setStatus("CLOSED");assertThatThrownBy(()->service.sendImage(3L,new MockMultipartFile("file",new byte[]{1}))).isInstanceOf(BusinessException.class);verifyNoInteractions(images);}
 @Test void anotherResidentCannotSendImage(){item.setResidentId(2L);assertThatThrownBy(()->service.sendImage(3L,new MockMultipartFile("file",new byte[]{1}))).isInstanceOf(ForbiddenException.class);verifyNoInteractions(images);}
 @Test void imageListDoesOneBatchRead(){var one=new FeedbackMessage();one.setId(11L);one.setMessageType("IMAGE");one.setSenderId(1L);one.setSenderType("RESIDENT");when(messages.selectList(any())).thenReturn(List.of(one));when(images.images(ChatImageService.FEEDBACK,List.of(11L))).thenReturn(Map.of());service.messages(3L,null);verify(images).images(ChatImageService.FEEDBACK,List.of(11L));verify(images,never()).image(anyString(),anyLong());}
}
