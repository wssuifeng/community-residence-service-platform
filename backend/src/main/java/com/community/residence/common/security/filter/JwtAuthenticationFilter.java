package com.community.residence.common.security.filter;

import com.community.residence.common.auth.service.AdminCommunityCacheService;
import com.community.residence.common.auth.service.TokenBlacklistService;
import com.community.residence.common.auth.service.TokenRevocationService;
import com.community.residence.common.auth.util.JwtUtil;
import com.community.residence.common.security.constant.RoleConstants;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * JWT 认证过滤器：解析 Bearer 令牌 → 黑名单校验（Redis 优先，降级查 DB）→
 * 填充 UserContext（ADMIN 附绑定小区集合，供数据级权限过滤）。
 * 令牌缺失/非法/已拉黑时不设置认证信息（由 SecurityConfig 默认拒绝 401/403）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final TokenRevocationService tokenRevocationService;
    private final AdminCommunityCacheService adminCommunityCacheService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        Claims claims = jwtUtil.parseToken(token);
        if (claims != null && !tokenBlacklistService.isBlacklisted(claims.getId())) {
            Long userId = jwtUtil.extractUserId(claims);
            String role = jwtUtil.extractRole(claims);
            /* 新登录按DB权限代次吊销；旧令牌保留时间戳校验，两套身份空间独立。 */
            Object revisionClaim = claims.get("permissionRevision");
            boolean integralRevision = revisionClaim instanceof Integer || revisionClaim instanceof Long;
            Long revision = integralRevision ? ((Number) revisionClaim).longValue() : null;
            boolean invalidRevision = revisionClaim != null && !integralRevision;
            boolean revoked = invalidRevision || tokenRevocationService.isTokenRevoked(userId, role, claims.getIssuedAt(), revision);
            if (!revoked) {
                String username = jwtUtil.extractUsername(claims);

                Set<Long> communityIds = RoleConstants.ADMIN.equals(role)
                        ? adminCommunityCacheService.getCommunityIds(userId)
                        : Set.of();

                UserContext context = new UserContext(userId, username, role, communityIds);
                SecurityUtils.setAuthentication(context);
            }
        }

        filterChain.doFilter(request, response);
    }
}
