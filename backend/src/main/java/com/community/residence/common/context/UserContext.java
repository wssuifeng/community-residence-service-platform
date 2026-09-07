package com.community.residence.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 请求级用户上下文：由 JwtAuthenticationFilter 解析令牌后填充，
 * 数据级权限拦截器与业务代码经 SecurityUtils 读取。
 * role 取值为 RoleConstants（RESIDENT/STAFF/ADMIN/SUPER_ADMIN）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    private Long userId;
    private String username;
    private String role;

    /** ADMIN 绑定社区 ID 集合（登录时按需加载，数据级权限过滤用）；其余角色为空 */
    private Set<Long> communityIds;

    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equals(role);
    }

    public boolean isCommunityAdmin() {
        return "ADMIN".equals(role);
    }
}
