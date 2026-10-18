package com.community.residence.common.auth.service;

import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.auth.util.JwtUtil;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.web.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OnlineRenewalTest {
    private final JwtUtil jwt = new JwtUtil("test-signing-key-must-have-at-least-32-characters", Duration.ofHours(36));
    private final ResidentMapper residents = mock(ResidentMapper.class);
    private final TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
    private final TokenRevocationService revocations = mock(TokenRevocationService.class);
    private final AuthService service = new AuthService(mock(SysUserMapper.class), residents, jwt, blacklist,
        mock(AdminCommunityCacheService.class), mock(org.springframework.security.crypto.password.PasswordEncoder.class), revocations);
    @Test void renewalKeepsSessionFamilyAndPermissionRevision() {
        var resident=new Resident(); resident.setId(1L); resident.setUsername("居民甲"); resident.setRealName("测试居民"); resident.setStatus("ACTIVE");
        when(residents.selectById(1L)).thenReturn(resident);
        String old=jwt.generateToken(1L,"居民甲","RESIDENT",0L);
        var next=service.renew(old); var before=jwt.parseToken(old); var after=jwt.parseToken(next.getToken());
        assertThat(next.getExpiresIn()).isEqualTo(129600);
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getIssuedAt()).isEqualTo(before.getIssuedAt());
        assertThat(after.get("permissionRevision",Integer.class)).isEqualTo(0);
    }
    @Test void blacklistedSessionCannotRenew() {
        String token=jwt.generateToken(1L,"居民甲","RESIDENT",0L);
        when(blacklist.isBlacklisted(jwt.parseToken(token).getId())).thenReturn(true);
        assertThatThrownBy(()->service.renew(token)).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(residents);
    }
    @Test void changedPermissionsCannotRenew() {
        String token=jwt.generateToken(1L,"居民甲","RESIDENT",0L);
        when(revocations.isTokenRevoked(eq(1L),eq("RESIDENT"),any(),eq(0L))).thenReturn(true);
        assertThatThrownBy(()->service.renew(token)).isInstanceOf(UnauthorizedException.class);
    }
    @Test void expiredOrMalformedTokenCannotRenew() {
        assertThatThrownBy(()->service.renew("invalid")).isInstanceOf(UnauthorizedException.class);
    }
}
