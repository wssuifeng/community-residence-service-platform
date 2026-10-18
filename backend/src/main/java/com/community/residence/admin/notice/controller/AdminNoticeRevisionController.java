package com.community.residence.admin.notice.controller;
import com.community.residence.common.web.result.ApiResponse;
import com.community.residence.common.notice.dto.ReviseNoticeDTO;
import com.community.residence.common.notice.entity.NoticeRevision;
import com.community.residence.common.notice.service.NoticeRevisionService;
import com.community.residence.common.notice.vo.NoticeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.List;
@RestController @RequestMapping("/api/v1/notices/{id}/revisions") @RequiredArgsConstructor @Tag(name="公告修订")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class AdminNoticeRevisionController {
 private final NoticeRevisionService service;
 @GetMapping @Operation(summary="已发布公告版本历史，仅管辖管理端可读")
 public ApiResponse<List<NoticeRevision>> list(@PathVariable Long id){return ApiResponse.success(service.list(id));}
 @PostMapping @Operation(summary="保存已发布公告修订，不下线不重复群发")
 public ApiResponse<NoticeVO> revise(@PathVariable Long id,@RequestBody @Valid ReviseNoticeDTO dto){return ApiResponse.success(service.revise(id,dto));}
}
