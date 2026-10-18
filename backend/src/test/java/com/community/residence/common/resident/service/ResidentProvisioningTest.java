package com.community.residence.common.resident.service;

import com.community.residence.common.auth.service.SysConfigService;

import com.community.residence.common.auth.mapper.SysOperationLogMapper;
import com.community.residence.common.auth.service.TokenRevocationService;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.service.CommunityService;
import com.community.residence.common.resident.dto.AdminCreateRelationDTO;
import com.community.residence.common.resident.dto.AdminCreateResidentDTO;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.resident.vo.RelationVO;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResidentProvisioningTest {
    @org.mockito.Mock private com.community.residence.common.community.service.StructureBusinessCodeService structureBusinessCodeService;
    @Mock ResidentMapper residentMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TokenRevocationService tokenRevocationService;
    @Mock SysConfigService sysConfigService;
    @Mock SysOperationLogMapper sysOperationLogMapper;
    @Mock HouseMapper houseMapper;
    @Mock CommunityService communityService;
    @Mock ResidenceRelationService residenceRelationService;
    @Mock PlatformTransactionManager transactionManager;
    @Spy Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    @InjectMocks ResidentService service;

    @BeforeEach void setup() {
        lenient().when(residentMapper.selectCount(any())).thenReturn(0L);
        lenient().when(passwordEncoder.encode(any())).thenReturn("hash");
        lenient().when(residentMapper.insert(any(Resident.class))).thenAnswer(inv -> { inv.getArgument(0, Resident.class).setId(99L); return 1; });
        House h = new House(); h.setId(5L); h.setCommunityId(3L); h.setStatus("VACANT");
        lenient().when(houseMapper.selectByIdForUpdate(5L)).thenReturn(h);
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        RelationVO r = new RelationVO(); r.setId(77L); r.setHouseId(5L);
        lenient().when(residenceRelationService.adminCreate(any())).thenReturn(r);
    }
    private AdminCreateResidentDTO dto() {
        AdminCreateResidentDTO d = new AdminCreateResidentDTO(); d.setCommunityId(3L); d.setRealName("测试居民"); d.setPhone("13900000011"); d.setEmail("resident@example.com"); d.setHouseId(5L); d.setRelationType("OWNER"); d.setMoveInDate(LocalDate.now()); return d;
    }
    private ByteArrayInputStream csv(String data) {
        return new ByteArrayInputStream(("社区ID,姓名,手机号,证件号,用户名,邮箱,房屋ID,关系类型,入住日期,备注\n"+data).getBytes(StandardCharsets.UTF_8));
    }
    @Test void accountAndRelationAndEmailUseOneProvisioningPath() {
        try (MockedStatic<SecurityUtils> ignored = mockStatic(SecurityUtils.class)) {
            var result=service.adminCreate(dto()); assertThat(result.getRelationId()).isEqualTo(77L); assertThat(result.getHouseId()).isEqualTo(5L);
        }
        ArgumentCaptor<Resident> account=ArgumentCaptor.forClass(Resident.class); verify(residentMapper).insert(account.capture()); assertThat(account.getValue().getEmail()).isEqualTo("resident@example.com");
        ArgumentCaptor<AdminCreateRelationDTO> relation=ArgumentCaptor.forClass(AdminCreateRelationDTO.class); verify(residenceRelationService).adminCreate(relation.capture()); assertThat(relation.getValue().getResidentId()).isEqualTo(99L);
    }
    @Test void crossCommunityHouseRejectedBeforeAccountInsert() {
        var d=dto(); d.setCommunityId(4L);
        try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(d)).isInstanceOf(BusinessException.class);}
        verify(residentMapper,never()).insert(any(Resident.class));
    }
    @Test void relationFieldsWithoutHouseRejected() { var d=dto(); d.setHouseId(null); try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(d)).isInstanceOf(BusinessException.class);} }
    @Test void missingRelationDateRejected() { var d=dto(); d.setMoveInDate(null); try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(d)).isInstanceOf(BusinessException.class);} }
    @Test void futureDateRejected() { var d=dto(); d.setMoveInDate(LocalDate.now().plusDays(1)); try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(d)).isInstanceOf(BusinessException.class);} }
    @Test void invalidEmailRejectedForCsvAsWell() { try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){var r=service.importCsv(csv("3,测试,13900000011,,,not-email,,,,\n"));assertThat(r.getSuccess()).isZero();assertThat(r.getFail()).isEqualTo(1);}verify(residentMapper,never()).insert(any(Resident.class)); }
    @Test void completeCsvRowReturnsRelation() { try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){var r=service.importCsv(csv("3,测试,13900000011,,custom_user,resident@example.com,5,OWNER,"+LocalDate.now()+",备注\n"));assertThat(r.getSuccess()).isEqualTo(1);assertThat(r.getSuccessRows().get(0).getRelationId()).isEqualTo(77L);assertThat(r.getSuccessRows().get(0).getUsername()).isEqualTo("custom_user");} }
    @Test void relationFailureRollsBackOnlyItsOwnRow() {
        when(residenceRelationService.adminCreate(any())).thenThrow(new BusinessException(com.community.residence.common.web.constant.ErrorCode.HOUSE_NOT_VACANT)).thenReturn(new RelationVO());
        try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){var r=service.importCsv(csv("3,甲,13900000011,,,,5,OWNER,"+LocalDate.now()+",\n3,乙,13900000012,,,,5,OWNER,"+LocalDate.now()+",\n"));assertThat(r.getFail()).isEqualTo(1);assertThat(r.getSuccess()).isEqualTo(1);assertThat(r.getSuccessRows().get(0).getRow()).isEqualTo(2);}
        verify(transactionManager).rollback(any()); verify(transactionManager).commit(any());
    }
    @Test void accountOnlyExplicitlyReturnsNoRelation() { var d=dto();d.setHouseId(null);d.setRelationType(null);d.setMoveInDate(null);try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){assertThat(service.adminCreate(d).getRelationId()).isNull();}verifyNoInteractions(residenceRelationService); }
    @Test void blankNameRejected() {var d=dto();d.setRealName(" ");try(MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.adminCreate(d)).isInstanceOf(BusinessException.class);}verify(residentMapper,never()).insert(any(Resident.class));}
    @Test void auditFailureRollsBackOnlyItsRowWithoutLeakingInternalError() {
        when(sysOperationLogMapper.insert(any(com.community.residence.common.auth.entity.SysOperationLog.class)))
                .thenThrow(new IllegalStateException("internal sensitive diagnostic")).thenReturn(1);
        try (MockedStatic<SecurityUtils> ignored = mockStatic(SecurityUtils.class)) {
            var result=service.importCsv(csv("3,甲,13900000011,,,,,,,\n3,乙,13900000012,,,,,,,\n"));
            assertThat(result.getFail()).isEqualTo(1);
            assertThat(result.getSuccess()).isEqualTo(1);
            assertThat(result.getFailRows().get(0).getReason()).doesNotContain("internal sensitive");
        }
        verify(transactionManager).rollback(any()); verify(transactionManager).commit(any());
    }
    @Test void malformedBusinessRowDoesNotPreventOtherRowsFromCommitting() {
        String header="房屋编号,姓名,手机号,证件号,用户名,邮箱,关系类型,入住日期,备注\n";
        String data=",合成居民,13900000011,,合成账户,,,,\n错误,少列\n";
        try (MockedStatic<SecurityUtils> ignored=mockStatic(SecurityUtils.class)) {
            var result=service.importCsv(new ByteArrayInputStream((header+data).getBytes(StandardCharsets.UTF_8)),3L);
            assertThat(result.getTotal()).isEqualTo(2);
            assertThat(result.getSuccess()).isEqualTo(1);
            assertThat(result.getFail()).isEqualTo(1);
            assertThat(result.getFailRows().get(0).getRow()).isEqualTo(2);
            assertThat(result.getFailRows().get(0).getReason()).contains("九列");
            verify(transactionManager,times(1)).commit(any());
        }
    }

}
