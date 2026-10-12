package com.community.residence.community.service;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.service.FileUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 公共资源图片写入策略：仅新增平台上传，历史原引用可保留或移除。 */
@Service
@RequiredArgsConstructor
public class ResourceImagePolicy {
    private final FileUploadService fileUploadService;

    /* 全量覆盖图片引用；先归一空白，再限数/长度；旧URL只可原样保留，不放行新外部地址。 */
    public String normalize(String[] requested, String existing) {
        if (requested == null) return null;
        List<String> urls = Arrays.stream(requested).filter(StringUtils::hasText).map(String::trim).toList();
        if (urls.size() > 6) throw new BusinessException(ErrorCode.INVALID_PARAM, "公共资源最多6张图片");
        String joined = String.join(",", urls);
        if (joined.length() > 1000) throw new BusinessException(ErrorCode.INVALID_PARAM, "图片URL总长最多1000字符");
        Set<String> retained = existing == null ? Set.of() : Arrays.stream(existing.split(",")).map(String::trim).collect(Collectors.toSet());
        for (String url : urls) if (!retained.contains(url)) fileUploadService.requireUploadedImage(url);
        return joined.isEmpty() ? null : joined;
    }
}
