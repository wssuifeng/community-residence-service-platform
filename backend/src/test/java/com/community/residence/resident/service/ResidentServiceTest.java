package com.community.residence.resident.service;

import com.community.residence.auth.service.TokenRevocationService;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.resident.dto.RegisterResidentDTO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 居民业务逻辑测试：注册开关、唯一性、密码加密 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResidentService 单元测试")
class ResidentServiceTest {

    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenRevocationService tokenRevocationService;
    @Mock
    private SysConfigService sysConfigService;

    @InjectMocks
    private ResidentService residentService;

    private RegisterResidentDTO dto;

    @BeforeEach
    void setUp() {
        dto = new RegisterResidentDTO();
        dto.setUsername("newuser");
        dto.setPassword("PlainPass123");
        dto.setRealName("New User");
        dto.setPhone("13800001111");
    }

    @Test
    @DisplayName("注册开关关闭：拒绝注册（REGISTRATION_DISABLED 5204）")
    void register_disabled_throws() {
        when(sysConfigService.isRegistrationEnabled()).thenReturn(false);
        assertThatThrownBy(() -> residentService.register(dto))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REGISTRATION_DISABLED));
    }

    @Test
    @DisplayName("用户名重复：拒绝注册（DATA_EXISTS）")
    void register_duplicateUsername_throws() {
        when(sysConfigService.isRegistrationEnabled()).thenReturn(true);
        when(residentMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> residentService.register(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名已存在");
    }

    @Test
    @DisplayName("注册成功：密码 BCrypt 加密存储，状态 ACTIVE")
    void register_success_encodesPassword() {
        when(sysConfigService.isRegistrationEnabled()).thenReturn(true);
        when(residentMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("PlainPass123")).thenReturn("$2a$10$encoded");
        when(residentMapper.insert(any(Resident.class))).thenAnswer(inv -> {
            inv.getArgument(0, Resident.class).setId(9L);
            return 1;
        });

        var vo = residentService.register(dto);

        assertThat(vo.getStatus()).isEqualTo("ACTIVE");
        assertThat(vo.getUsername()).isEqualTo("newuser");
        org.mockito.Mockito.verify(residentMapper).insert(
                org.mockito.ArgumentMatchers.argThat((Resident r) ->
                        "$2a$10$encoded".equals(r.getPasswordHash())));
    }

    @Test
    @DisplayName("改密：旧密码错误拒绝（OLD_PASSWORD_MISMATCH 5203）")
    void changePassword_wrongOld_throws() {
        try (org.mockito.MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     org.mockito.Mockito.mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId).thenReturn(1L);
            Resident resident = new Resident();
            resident.setId(1L);
            resident.setPasswordHash("$2a$10$hash");
            when(residentMapper.selectById(1L)).thenReturn(resident);
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> residentService.changePassword("wrong", "NewPass12345"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.OLD_PASSWORD_MISMATCH));
        }
    }
}
