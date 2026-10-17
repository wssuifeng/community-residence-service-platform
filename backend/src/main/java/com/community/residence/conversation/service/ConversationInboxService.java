package com.community.residence.conversation.service;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.*;
import com.community.residence.common.result.PageVO;
import com.community.residence.conversation.mapper.ConversationInboxMapper;
import com.community.residence.conversation.vo.ConversationInboxVO;
import com.community.residence.feedback.mapper.FeedbackMessageMapper;
import com.community.residence.feedback.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** 列表聚合与来源适配，不创建会话或改变原受理/授权规则。 */
@Service
@RequiredArgsConstructor
public class ConversationInboxService {
 private final ConversationInboxMapper mapper;
 private final ConversationService conversations;
 private final FeedbackService feedbacks;
 private final FeedbackMessageMapper messages;
 public record Scope(Long userId,String role,String ownerType,Set<Long> communityIds,String type,String keyword,boolean unreadOnly){}
 public PageVO<ConversationInboxVO> page(long page,int size,String type,String keyword,boolean unreadOnly){
  if(page<1||page>1000000||size<1||size>100)throw invalid("分页参数错误");
  if(type!=null&&!Set.of("LEASE_GROUP","VIEWING_GROUP","WORK_ORDER_GROUP","DIRECT","FEEDBACK").contains(type))throw invalid("会话分类错误");
  keyword=keyword==null||keyword.isBlank()?null:keyword.trim();if(keyword!=null&&keyword.length()>100)throw invalid("搜索最多100字");
  var user=SecurityUtils.getUser();String owner="RESIDENT".equals(user.getRole())?"RESIDENT":"USER";
  var q=new Scope(user.getUserId(),user.getRole(),owner,user.getCommunityIds()==null?Set.of():user.getCommunityIds(),type,keyword,unreadOnly);
  return PageVO.of(mapper.page(q,(page-1)*size,size),mapper.count(q),page,size);
 }
 /* 每个窗口均回到原来源校验；双向游标互斥以免遗漏/越界。 */
 public List<?> messages(String source,Long id,Long beforeId,Long afterId,int size){
  if(size<1||size>100||beforeId!=null&&beforeId<=0||afterId!=null&&afterId<0||beforeId!=null&&afterId!=null)throw invalid("历史窗口参数错误");
  if("CONVERSATION".equals(source))return conversations.messageWindow(id,beforeId,afterId,size);
  if("FEEDBACK".equals(source)){requireFeedbackAccess(id);return feedbacks.messageWindow(id,beforeId,afterId,size);}
  throw invalid("未知会话来源");
 }
 /* 原始反馈与消息游标分开确认，读操作绝不触发受理。 */
 @Transactional(rollbackFor=Exception.class)
 public void markFeedbackRead(Long id,Long cursor){
  requireFeedbackAccess(id);if(cursor==null||cursor<0)throw invalid("已读游标错误");
  if(cursor>0){var row=messages.selectById(cursor);if(row==null||!Objects.equals(row.getFeedbackId(),id))throw invalid("已读消息不属于当前反馈");}
  mapper.advanceFeedbackRead(id,SecurityUtils.hasRole("RESIDENT")?"RESIDENT":"USER",SecurityUtils.getUserId(),cursor);
 }
 private void requireFeedbackAccess(Long id){
  if(SecurityUtils.hasRole("STAFF"))throw new ForbiddenException("服务人员无反馈会话权限");
  var feedback=feedbacks.getById(id);
  if(!SecurityUtils.hasRole("RESIDENT"))SecurityUtils.checkCommunityAccess(feedback.getCommunityId());
 }
 private BusinessException invalid(String message){return new BusinessException(ErrorCode.INVALID_PARAM,message);}
}
