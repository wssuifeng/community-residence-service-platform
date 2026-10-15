package com.community.residence.notice.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.notice.entity.*;
import com.community.residence.notice.mapper.*;
import com.community.residence.notice.dto.ReviseNoticeDTO;
import com.community.residence.notice.vo.NoticeVO;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.*;
import com.community.residence.community.service.ResourceImagePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service @RequiredArgsConstructor
public class NoticeRevisionService {
 private final NoticeMapper notices;
 private final NoticeRevisionMapper revisions;
 private final NoticeService noticeService;
 private final ResourceImagePolicy imagePolicy;
 public List<NoticeRevision> list(Long id) {
  noticeService.checkManageAccess(noticeService.requireNotice(id));
  return revisions.selectList(new LambdaQueryWrapper<NoticeRevision>().eq(NoticeRevision::getNoticeId,id).orderByDesc(NoticeRevision::getContentVersion).last("LIMIT 100"));
 }
 /* 锁内比较版本再整版更新；保留发布状态/投放/时效，不重复发送原发布通知。 */
 @Transactional(rollbackFor=Exception.class)
 @com.community.residence.log.annotation.OperationLog(operationType="UPDATE",targetType="NOTICE",targetId="#id",content="'修订已发布公告正文'")
 public NoticeVO revise(Long id,ReviseNoticeDTO dto) {
  var n=notices.lock(id);if(n==null)throw new ResourceNotFoundException("公告不存在");noticeService.checkManageAccess(n);
  if(!"PUBLISHED".equals(n.getStatus()))throw new BusinessException(ErrorCode.STATE_TRANSITION_INVALID,"本入口仅修订已发布公告，草稿请使用编辑保存");
  int version=n.getContentVersion()==null?1:n.getContentVersion();
  if(!Integer.valueOf(version).equals(dto.getExpectedVersion()))throw new BusinessException(ErrorCode.INVALID_PARAM,"公告已被他人修订，请重新读取后合并，不覆盖当前版本");
  var body=NoticeBodyPolicy.parse(dto.getBodyJson());String images=imagePolicy.normalize(body.images(),n.getImages());
  if(revisions.selectCount(new LambdaQueryWrapper<NoticeRevision>().eq(NoticeRevision::getNoticeId,id).eq(NoticeRevision::getContentVersion,version))==0)saveSnapshot(n,version);
  n.setTitle(dto.getTitle().strip());n.setBodyJson(body.json());n.setContent(body.text());n.setImages(images);n.setContentVersion(version+1);n.setEditedBy(SecurityUtils.getUserId());n.setEditedAt(LocalDateTime.now());notices.updateById(n);saveSnapshot(n,version+1);
  return noticeService.getById(id);
 }
 private void saveSnapshot(Notice n,int version) {
  var r=new NoticeRevision();r.setNoticeId(n.getId());r.setContentVersion(version);r.setTitle(n.getTitle());r.setContent(n.getContent());r.setBodyJson(n.getBodyJson());r.setImages(n.getImages());r.setEditorId(n.getEditedBy()==null?n.getPublisherId():n.getEditedBy());r.setEditedAt(n.getEditedAt()==null?LocalDateTime.now():n.getEditedAt());revisions.insert(r);
 }
}
