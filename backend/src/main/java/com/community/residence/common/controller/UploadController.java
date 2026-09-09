package com.community.residence.common.controller;

import com.community.residence.common.result.ApiResponse;
import com.community.residence.common.service.FileUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 通用文件上传（接口设计.md §5.1；附件经专用接口关联到工单/反馈） */
@Tag(name = "文件上传", description = "通用上传接口")
@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
public class UploadController {

    private final FileUploadService fileUploadService;

    @Operation(summary = "上传文件", description = "图片 ≤5MB(jpg/jpeg/png/gif)，文档 ≤10MB(pdf/doc/docx/txt)")
    @PreAuthorize("isAuthenticated()")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FileUploadService.UploadResult> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "IMAGE") String type) {
        return ApiResponse.success(fileUploadService.upload(file, type));
    }
}
