package com.community.residence.common.conversation.service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.community.residence.common.upload.service.FileUploadService;
import com.community.residence.common.conversation.entity.ChatMessageImage;
import com.community.residence.common.conversation.mapper.ChatMessageImageMapper;
import com.community.residence.common.conversation.vo.ChatImageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 图片消息统一存储与批量投影；不提供绕过业务会话授权的独立读取接口。 */
@Service @RequiredArgsConstructor
public class ChatImageService {
 public static final String FEEDBACK="FEEDBACK", CONVERSATION="CONVERSATION", VIEWING="VIEWING";
 private final FileUploadService fileUploadService;
 private final ChatMessageImageMapper mapper;
 /** 调用方完成可发送权限/关闭态校验后上传，仅真实图片≤5MB。 */
 public FileUploadService.UploadResult upload(MultipartFile file) { return fileUploadService.upload(file,"IMAGE"); }
 /** 消息和图片关联处于调用方同一事务，不接受客户端任意URL。 */
 public void attach(String scope,Long messageId,Long communityId,FileUploadService.UploadResult file) {
  ChatMessageImage row=new ChatMessageImage();row.setMessageScope(scope);row.setMessageId(messageId);row.setCommunityId(communityId);
  row.setFileUrl(file.fileUrl());row.setFileName(file.fileName());row.setFileSize(file.fileSize());row.setCreatedAt(LocalDateTime.now());mapper.insert(row);
 }
 /** 仅IMAGE消息查询，一批只查询一次；不同业务空间的相同自增ID不混用。 */
 public Map<Long,ChatImageVO> images(String scope,List<Long> ids) {
  if(ids.isEmpty())return Map.of();
  return mapper.selectList(new LambdaQueryWrapper<ChatMessageImage>().eq(ChatMessageImage::getMessageScope,scope).in(ChatMessageImage::getMessageId,ids))
   .stream().collect(Collectors.toMap(ChatMessageImage::getMessageId, row->{ChatImageVO vo=new ChatImageVO();vo.setFileUrl(row.getFileUrl());vo.setFileName(row.getFileName());vo.setFileSize(row.getFileSize());return vo;}));
 }
 public ChatImageVO image(String scope,Long id) { return images(scope,List.of(id)).get(id); }
}
