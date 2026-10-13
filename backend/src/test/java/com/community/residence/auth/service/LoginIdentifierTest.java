package com.community.residence.auth.service;
import com.community.residence.auth.dto.LoginDTO;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.auth.util.JwtUtil;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.common.exception.UnauthorizedException;
import com.community.residence.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 双标识登录仍按账号空间隔离；歧义不猜优先级，冻结不绕过。 */
@ExtendWith(MockitoExtension.class)
class LoginIdentifierTest {
    @Mock SysUserMapper sysUserMapper; @Mock ResidentMapper residentMapper;
    @Mock PasswordEncoder passwordEncoder; @Mock JwtUtil jwtUtil;
    @Mock AdminCommunityCacheService adminCommunityCacheService;
    @Mock TokenRevocationService tokenRevocationService;
    @InjectMocks AuthService service;
    private LoginDTO request(String identifier) { LoginDTO dto=new LoginDTO(); dto.setUsername(identifier); dto.setPassword("Synthetic123"); return dto; }
    private Resident resident() { Resident r=new Resident();r.setId(7L);r.setUsername("合成居民");r.setPasswordHash("hash");r.setStatus("ACTIVE");return r; }
    @Test void residentPhoneReturnsSameCanonicalChineseUsername() {
        when(residentMapper.selectLoginCandidates("13999991001")).thenReturn(List.of(resident()));
        when(passwordEncoder.matches("Synthetic123", "hash")).thenReturn(true);
        assertThat(service.residentLogin(request(" 13999991001 ")).getUser().getUsername()).isEqualTo("合成居民");
        verifyNoInteractions(sysUserMapper);
    }
    @Test void chineseUsernameAlsoAuthenticatesTheSameAccount() {
        when(residentMapper.selectLoginCandidates("合成居民")).thenReturn(List.of(resident()));
        when(passwordEncoder.matches("Synthetic123", "hash")).thenReturn(true);
        assertThat(service.residentLogin(request("合成居民")).getUser().getId()).isEqualTo(7L);
    }
    @Test void ambiguousLoginIdentifierDoesNotTryMatchingPasswords() {
        when(residentMapper.selectLoginCandidates(anyString())).thenReturn(List.of(resident(), resident()));
        assertThatThrownBy(()->service.residentLogin(request("13999991001"))).isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(passwordEncoder, jwtUtil);
    }
    @Test void frozenResidentCannotBypassWithPhone() {
        Resident r=resident();r.setStatus("FROZEN");when(residentMapper.selectLoginCandidates(anyString())).thenReturn(List.of(r));
        when(passwordEncoder.matches(anyString(),anyString())).thenReturn(true);
        assertThatThrownBy(()->service.residentLogin(request("13999991001"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jwtUtil);
    }
    @Test void wrongPasswordNeverGeneratesToken() {
        when(residentMapper.selectLoginCandidates(anyString())).thenReturn(List.of(resident()));
        assertThatThrownBy(()->service.residentLogin(request("合成居民"))).isInstanceOf(UnauthorizedException.class);verifyNoInteractions(jwtUtil);
    }
    @Test void adminPhoneKeepsItsOwnRoleAndCommunityScope() {
        SysUser u=new SysUser();u.setId(2L);u.setUsername("中文管理员");u.setRole("ADMIN");u.setStatus("ACTIVE");u.setPasswordHash("hash");
        when(sysUserMapper.selectLoginCandidates(anyString())).thenReturn(List.of(u));
        when(passwordEncoder.matches(anyString(),anyString())).thenReturn(true);when(adminCommunityCacheService.getCommunityIds(2L)).thenReturn(Set.of(5L));
        var login=service.adminLogin(request("13999991002"));assertThat(login.getUser().getUsername()).isEqualTo("中文管理员");assertThat(login.getUser().getBoundCommunities()).containsExactly(5L);
        verifyNoInteractions(residentMapper);
    }
    @Test void adminEndpointDoesNotFallbackToResidentPhone() {
        when(sysUserMapper.selectLoginCandidates(anyString())).thenReturn(List.of());
        assertThatThrownBy(()->service.adminLogin(request("13999991001"))).isInstanceOf(UnauthorizedException.class);verifyNoInteractions(residentMapper);
    }
}
