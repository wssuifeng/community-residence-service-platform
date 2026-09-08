package com.community.residence.resident.vo;

import com.community.residence.resident.entity.Resident;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 居民信息响应（不含密码哈希与完整身份证号） */
@Data
@Schema(description = "居民信息")
public class ResidentVO {

    @Schema(description = "居民ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "身份证号（脱敏）")
    private String idCardMasked;

    @Schema(description = "状态：ACTIVE-正常, FROZEN-冻结")
    private String status;

    @Schema(description = "注册时间")
    private LocalDateTime createdAt;

    public static ResidentVO from(Resident entity) {
        ResidentVO vo = new ResidentVO();
        vo.setId(entity.getId());
        vo.setUsername(entity.getUsername());
        vo.setRealName(entity.getRealName());
        vo.setPhone(entity.getPhone());
        vo.setEmail(entity.getEmail());
        vo.setIdCardMasked(maskIdCard(entity.getIdCard()));
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /** 身份证脱敏：保留前 4 后 2 位（个人资料返回本人完整号码由前端按需展示） */
    private static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 8) {
            return idCard;
        }
        return idCard.substring(0, 4) + "**********" + idCard.substring(idCard.length() - 2);
    }
}
