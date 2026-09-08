package com.community.residence.filter;

import com.community.residence.auth.service.AdminCommunityCacheService;
import com.community.residence.auth.service.TokenBlacklistService;
import com.community.residence.auth.service.TokenRevocationService;
import com.community.residence.auth.util.JwtUtil;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
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
 * 填充 UserContext（ADMIN 附绑定社区集合，供数据级权限过滤）。
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
            /* 用户级吊销（区分账号体系，居民与后台账号 ID 空间隔离）：
               冻结/改密/权限变更后，存量令牌（iat 早于吊销时间）全部失效 */
            boolean revoked = RoleConstants.RESIDENT.equals(role)
                    ? tokenRevocationService.isResidentRevoked(userId, claims.getIssuedAt())
                    : tokenRevocationService.isAdminUserRevoked(userId, claims.getIssuedAt());
            if (claims.getIssuedAt() == null || !revoked) {
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
