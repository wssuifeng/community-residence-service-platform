package com.community.residence.resident.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 居民账号（resident 表：居民端登录主体，与 sys_user 后台账号分表隔离） */
@Data
@TableName("resident")
@Schema(description = "居民账号")
public class Resident {

    @TableId(type = IdType.AUTO)
    @Schema(description = "居民ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "密码哈希（BCrypt）")
    private String passwordHash;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "身份证号（脱敏存储）")
    private String idCard;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "头像URL")
    private String avatarUrl;

    @Schema(description = "状态：ACTIVE-正常, FROZEN-冻结")
    private String status;

    @Schema(description = "注册时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;
}
