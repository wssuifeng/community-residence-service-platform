package com.community.residence.auth.service;
import com.community.residence.auth.util.JwtUtil;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.filter.JwtAuthenticationFilter;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 签名后的权限代次需经过真实JWT解析与过滤器校验，不放宽旧令牌吊销。 */
class TokenRevisionFilterTest {
    private static final String SECRET="non-production-jwt-test-only-0123456789abcdef0123456789abcdef";
    private final JwtUtil jwt=new JwtUtil(SECRET,Duration.ofHours(2));
    private final TokenBlacklistService blacklist=mock(TokenBlacklistService.class);
    private final TokenRevocationService revocations=mock(TokenRevocationService.class);
    private final AdminCommunityCacheService communities=mock(AdminCommunityCacheService.class);
    private final JwtAuthenticationFilter filter=new JwtAuthenticationFilter(jwt,blacklist,revocations,communities);
    @AfterEach void clear(){SecurityUtils.clear();}
    private void run(String token)throws Exception{
        var request=new MockHttpServletRequest();request.addHeader("Authorization","Bearer "+token);
        filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{});
    }
    @Test void currentRevisionSignedTokenAuthenticates()throws Exception{
        when(communities.getCommunityIds(7L)).thenReturn(Set.of(9L));
        run(jwt.generateToken(7L,"test","ADMIN",20L));
        assertThat(SecurityUtils.getUser().getCommunityIds()).containsExactly(9L);
        verify(revocations).isTokenRevoked(eq(7L),eq("ADMIN"),any(Date.class),eq(20L));
    }
    @Test void preChangeRevisionIsRejected()throws Exception{
        when(revocations.isTokenRevoked(eq(7L),eq("ADMIN"),any(Date.class),eq(19L))).thenReturn(true);
        run(jwt.generateToken(7L,"test","ADMIN",19L));assertThat(SecurityUtils.getUser()).isNull();
    }
    @Test void legacyTokenUsesLegacyRevocationBranch()throws Exception{
        when(revocations.isTokenRevoked(eq(7L),eq("ADMIN"),any(Date.class),isNull())).thenReturn(true);
        run(jwt.generateToken(7L,"test","ADMIN"));assertThat(SecurityUtils.getUser()).isNull();
    }
    @Test void residentTokenKeepsResidentScope()throws Exception{
        run(jwt.generateToken(7L,"test","RESIDENT",20L));
        verify(revocations).isTokenRevoked(eq(7L),eq("RESIDENT"),any(Date.class),eq(20L));verifyNoInteractions(communities);
    }
    @Test void expiredSignedTokenCannotAuthenticate()throws Exception{
        run(new JwtUtil(SECRET,Duration.ofSeconds(-1)).generateToken(7L,"test","ADMIN",20L));
        assertThat(SecurityUtils.getUser()).isNull();verifyNoInteractions(revocations);
    }
    @Test void malformedSignedRevisionCannotAuthenticate()throws Exception{
        String token=Jwts.builder().subject("7").claim("role","ADMIN").claim("permissionRevision","20")
                .issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        run(token);assertThat(SecurityUtils.getUser()).isNull();verifyNoInteractions(revocations);
    }
    @Test void blacklistedTokenCannotAuthenticate()throws Exception{
        when(blacklist.isBlacklisted(anyString())).thenReturn(true);
        run(jwt.generateToken(7L,"test","ADMIN",20L));assertThat(SecurityUtils.getUser()).isNull();verifyNoInteractions(revocations);
    }
    @Test void unsignedForgeryCannotAuthenticate()throws Exception{
        run("invalid.jwt.signature");assertThat(SecurityUtils.getUser()).isNull();verifyNoInteractions(revocations);
    }
}
