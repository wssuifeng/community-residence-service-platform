package com.community.residence.resident.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 代建居民结果（初始密码明文仅本次返回，R8 v1.2） */
@Data
@Schema(description = "代建居民结果")
public class AdminCreateResidentVO {

    @Schema(description = "居民ID")
    private Long residentId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "初始密码（明文仅本次返回，居民首次登录后建议修改）")
    private String initialPassword;

    @Schema(description = "本次登记的居住关系ID；仅账号模式为空")
    private Long relationId;

    @Schema(description = "本次登记关系的房屋ID；仅账号模式为空")
    private Long houseId;

    public static AdminCreateResidentVO of(Long residentId, String username, String initialPassword) {
        AdminCreateResidentVO vo = new AdminCreateResidentVO();
        vo.setResidentId(residentId);
        vo.setUsername(username);
        vo.setInitialPassword(initialPassword);
        return vo;
    }
}
