package com.community.residence.auth;
import com.community.residence.auth.util.JwtUtil;
import com.community.residence.auth.service.TokenRevocationService;
import com.community.residence.auth.mapper.AuthTokenBlacklistMapper;
import com.community.residence.auth.entity.AuthTokenBlacklist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class JwtLifetimeTest {
    @Mock AuthTokenBlacklistMapper mapper;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String,String> values;
    @InjectMocks TokenRevocationService revocations;
    @Test void jwtAndReturnedDurationShare36Hours() {
        var jwt=new JwtUtil("test-only-secret-with-at-least-32-characters",Duration.ofHours(36));
        var claims=jwt.parseToken(jwt.generateToken(1L,"用户","RESIDENT",0L));
        assertThat(jwt.accessTtlSeconds()).isEqualTo(129600L);
        assertThat(claims.getExpiration().getTime()-claims.getIssuedAt().getTime()).isEqualTo(129600000L);
        assertThat(jwt.remainingMillis(claims)).isGreaterThan(Duration.ofHours(35).toMillis());
    }
    @Test void revocationLivesForTheWholeLongTokenAndRevisionStillRejects() {
        when(redis.opsForValue()).thenReturn(values);
        revocations.revokeResident(1L);
        var row=ArgumentCaptor.forClass(AuthTokenBlacklist.class);verify(mapper).insert(row.capture());
        assertThat(Duration.between(LocalDateTime.now(),row.getValue().getExpireTime()).toHours()).isBetween(35L,36L);
        verify(values).set(anyString(),anyString(),eq(Duration.ofHours(36)));
        when(mapper.selectLatestRevocationId(anyString())).thenReturn(9L);
        assertThat(revocations.isTokenRevoked(1L,"RESIDENT",new java.util.Date(),8L)).isTrue();
        assertThat(revocations.isTokenRevoked(1L,"RESIDENT",new java.util.Date(),9L)).isFalse();
    }
}
