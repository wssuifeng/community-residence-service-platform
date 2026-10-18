package com.community.residence.common.resident.service;

import com.community.residence.common.auth.service.SysConfigService;

import com.community.residence.common.auth.entity.SysConfig;
import com.community.residence.common.auth.mapper.SysConfigMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** DEF-140：注册开关只能保存true/false，非法值不写库/不清缓存。 */
class SysConfigValueValidationTest {
    @ParameterizedTest @ValueSource(strings = {"invalid", "TRUE", " false ", "1", ""})
    void rejectsInvalidBooleanBeforePersisting(String value) {
        SysConfigMapper mapper = mock(SysConfigMapper.class); StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SysConfig row = new SysConfig(); row.setConfigKey("registration.enabled"); row.setConfigValue("true");
        lenient().when(mapper.selectOne(any())).thenReturn(row);
        SysConfigService service = new SysConfigService(mapper, redis);
        assertThrows(IllegalArgumentException.class, () -> service.update("registration.enabled", value));
        verify(mapper, never()).updateById(any(SysConfig.class)); verifyNoInteractions(redis);
        assertEquals("true", row.getConfigValue());
    }
    @Test void validFalsePersists() {
        SysConfigMapper mapper = mock(SysConfigMapper.class); StringRedisTemplate redis = mock(StringRedisTemplate.class);
        SysConfig row = new SysConfig(); row.setConfigKey("registration.enabled");
        when(mapper.selectOne(any())).thenReturn(row);
        new SysConfigService(mapper, redis).update("registration.enabled", "false");
        assertEquals("false", row.getConfigValue()); verify(mapper).updateById(row);
    }
}
