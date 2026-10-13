package com.community.residence.resident.service;
import com.community.residence.resident.dto.RegisterResidentDTO;
import com.community.residence.resident.dto.AdminCreateResidentDTO;
import com.community.residence.auth.dto.CreateSysUserDTO;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

/** 三个录入入口使用同一文字/唯一用户名口径，字符规则不等同于真实姓名。 */
class ChineseUsernameContractTest {
    @ParameterizedTest @ValueSource(strings={"张三","张","住户A_12","normal_user"})
    void allAccountCreationDtosAcceptChineseAndShortNames(String value) {
        try (var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();var resident=new RegisterResidentDTO();resident.setUsername(value);
            var admin=new AdminCreateResidentDTO();admin.setUsername(value);var user=new CreateSysUserDTO();user.setUsername(value);
            assertThat(validator.validateProperty(resident,"username")).isEmpty();
            assertThat(validator.validateProperty(admin,"username")).isEmpty();
            assertThat(validator.validateProperty(user,"username")).isEmpty();
        }
    }
    @ParameterizedTest @ValueSource(strings={"bad name","名字<script>"," ","居民/甲"})
    void unsupportedWhitespaceAndSymbolsAreNotUsernames(String value) {
        try(var factory=Validation.buildDefaultValidatorFactory()) { var dto=new RegisterResidentDTO();dto.setUsername(value);assertThat(factory.getValidator().validateProperty(dto,"username")).isNotEmpty(); }
    }
}
