package com.community.residence.common.context;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

/**
 * 安全上下文工具：读写当前请求的 UserContext。
 * UserContext 存于 Authentication.principal，与 Spring Security 上下文同生命周期。
 */
public final class SecurityUtils {

    public static final String ROLE_PREFIX = "ROLE_";

    private SecurityUtils() {
    }

    /** 写入认证信息（JwtAuthenticationFilter 调用）；communityIds 为 ADMIN 绑定社区集合 */
    public static void setAuthentication(UserContext context) {
        List<SimpleGrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority(ROLE_PREFIX + context.getRole()));
        Authentication auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                context, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 当前登录用户上下文；未认证返回 null */
    public static UserContext getUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserContext context) {
            return context;
        }
        return null;
    }

    /** 当前登录用户 ID；未认证抛 IllegalStateException（应仅在受保护路径调用） */
    public static Long getUserId() {
        UserContext user = getUser();
        if (user == null) {
            throw new IllegalStateException("当前无认证用户");
        }
        return user.getUserId();
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (GrantedAuthority authority : auth.getAuthorities()) {
            if ((ROLE_PREFIX + role).equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** ADMIN 绑定社区集合（数据级权限过滤用）；非 ADMIN 返回空集合 */
    public static Set<Long> getCommunityIds() {
        UserContext user = getUser();
        return user != null && user.getCommunityIds() != null ? user.getCommunityIds() : Set.of();
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
