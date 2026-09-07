package com.community.residence.auth.vo;

import com.community.residence.auth.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 系统用户响应（不含密码哈希；绑定社区列表仅详情返回） */
@Data
@Schema(description = "系统用户信息")
public class SysUserVO {

    @Schema(description = "用户ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "真实姓名")
    private String realName;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "角色：SUPER_ADMIN-超级管理员, ADMIN-社区管理员, STAFF-服务人员")
    private String role;

    @Schema(description = "状态：ACTIVE-正常, FROZEN-冻结")
    private String status;

    @Schema(description = "绑定社区列表（仅 ADMIN 角色有值）")
    private List<BoundCommunityVO> boundCommunities;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public static SysUserVO from(SysUser entity) {
        SysUserVO vo = new SysUserVO();
        vo.setId(entity.getId());
        vo.setUsername(entity.getUsername());
        vo.setRealName(entity.getRealName());
        vo.setPhone(entity.getPhone());
        vo.setEmail(entity.getEmail());
        vo.setRole(entity.getRole());
        vo.setStatus(entity.getStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
