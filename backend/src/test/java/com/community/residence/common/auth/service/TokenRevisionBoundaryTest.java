package com.community.residence.common.auth.service;
import com.community.residence.common.auth.mapper.AuthTokenBlacklistMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.Date;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 权限变更同秒：存量令牌失效而变更后重新登录的令牌立即可用。 */
class TokenRevisionBoundaryTest {
    private final AuthTokenBlacklistMapper mapper=mock(AuthTokenBlacklistMapper.class);
    private final TokenRevocationService service=new TokenRevocationService(mapper,mock(StringRedisTemplate.class));
    private final Date sameSecond=new Date(1800000000000L);
    @Test void newLoginAtSameSecondUsesCurrentRevision() {
        when(mapper.selectLatestRevocationId("USER-A-7-%")).thenReturn(20L);
        assertThat(service.isTokenRevoked(7L,"ADMIN",sameSecond,20L)).isFalse();
    }
    @Test void preChangeTokenAtSameSecondIsRevoked() {
        when(mapper.selectLatestRevocationId("USER-A-7-%")).thenReturn(20L);
        assertThat(service.isTokenRevoked(7L,"ADMIN",sameSecond,19L)).isTrue();
    }
    @Test void residentAndAdminRevisionSpacesNeverMix() {
        when(mapper.selectLatestRevocationId("USER-R-7-%")).thenReturn(21L);
        when(mapper.selectLatestRevocationId("USER-A-7-%")).thenReturn(20L);
        assertThat(service.isTokenRevoked(7L,"RESIDENT",sameSecond,20L)).isTrue();
        assertThat(service.isTokenRevoked(7L,"ADMIN",sameSecond,20L)).isFalse();
    }
    @Test void noRemainingRevocationsDoesNotRevokeNewToken() {
        assertThat(service.isTokenRevoked(7L,"ADMIN",sameSecond,20L)).isFalse();
    }
    @Test void laterChangesRevokePreviouslyNewToken() {
        when(mapper.selectLatestRevocationId("USER-A-7-%")).thenReturn(21L);
        assertThat(service.isTokenRevoked(7L,"ADMIN",sameSecond,20L)).isTrue();
    }
    @Test void invalidRevisionOrMissingIssueTimeIsRejected() {
        assertThat(service.isTokenRevoked(7L,"ADMIN",sameSecond,-1L)).isTrue();
        assertThat(service.isTokenRevoked(7L,"ADMIN",null,0L)).isTrue();
    }
}
