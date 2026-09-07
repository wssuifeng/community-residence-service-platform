package com.community.residence.auth.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具（jjwt 0.12.6）。
 * Claims 精简为 userId/username/role + jti（架构设计.md §3.2：绑定社区不进令牌，
 * 每次请求从 Redis 读绑定关系，避免令牌膨胀与权限变更后旧令牌范围失效问题）。
 * 有效期默认 2 小时（接口设计.md 9.10.1.1）。
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtUtil(@Value("${auth.jwt.secret}") String secret,
                   @Value("${auth.jwt.access-ttl:2h}") Duration accessTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = accessTtl;
    }

    /** 签发 Access Token：jti 全局唯一（UUID），登出黑名单以 jti 为键 */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expire = new Date(now.getTime() + accessTtl.toMillis());
        return Jwts.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expire)
                .signWith(key)
                .compact();
    }

    /**
     * 解析并验证令牌。
     *
     * @return Claims；令牌非法/过期返回 null（调用方决定 401 语义）
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            log.debug("令牌已过期：{}", e.getMessage());
            return null;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("令牌非法：{}", e.getMessage());
            return null;
        }
    }

    public String extractJti(String token) {
        Claims claims = parseToken(token);
        return claims != null ? claims.getId() : null;
    }

    public Long extractUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    public String extractUsername(Claims claims) {
        return claims.get("username", String.class);
    }

    public String extractRole(Claims claims) {
        return claims.get("role", String.class);
    }

    public Date extractExpiration(Claims claims) {
        return claims.getExpiration();
    }

    /** 令牌剩余有效毫秒数（黑名单 TTL 用）；已过期返回 0 */
    public long remainingMillis(Claims claims) {
        long remain = claims.getExpiration().getTime() - System.currentTimeMillis();
        return Math.max(remain, 0);
    }
}
