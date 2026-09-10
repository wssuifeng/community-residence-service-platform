package com.community.residence.resident.service;

import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.auth.service.TokenRevocationService;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.resident.dto.AdminCreateResidentDTO;
import com.community.residence.resident.entity.Resident;
import com.community.residence.resident.mapper.ResidentMapper;
import com.community.residence.resident.vo.AdminCreateResidentVO;
import com.community.residence.resident.vo.ResidentImportVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 管理员代建与 CSV 批量导入测试（R8 v1.2）：代建成功/越权/重复、部分成功、坏格式 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResidentService 代建与导入单元测试")
class ResidentAdminCreateTest {

    @Mock
    private ResidentMapper residentMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenRevocationService tokenRevocationService;
    @Mock
    private SysConfigService sysConfigService;
    @Mock
    private SysOperationLogMapper sysOperationLogMapper;

    @InjectMocks
    private ResidentService residentService;

    private AdminCreateResidentDTO dto() {
        AdminCreateResidentDTO dto = new AdminCreateResidentDTO();
        dto.setCommunityId(3L);
        dto.setRealName("张三");
        dto.setPhone("13900000001");
        return dto;
    }

    private MockedStatic<SecurityUtils> mockSuperAdmin() {
        MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class);
        mocked.when(SecurityUtils::getUserId).thenReturn(1L);
        return mocked;
    }

    @Test
    @DisplayName("代建成功：用户名默认手机号生成、初始密码手机号后 6 位、操作日志留痕")
    void adminCreate_success() {
        when(residentMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(residentMapper.insert(org.mockito.ArgumentMatchers.<Resident>any())).thenAnswer(inv -> {
            inv.getArgument(0, Resident.class).setId(99L);
            return 1;
        });

        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            AdminCreateResidentVO vo = residentService.adminCreate(dto());

            assertThat(vo.getResidentId()).isEqualTo(99L);
            assertThat(vo.getUsername()).isEqualTo("r13900000001");
            assertThat(vo.getInitialPassword()).isEqualTo("000001");
        }
        ArgumentCaptor<Resident> captor = ArgumentCaptor.forClass(Resident.class);
        verify(residentMapper).insert(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hash");
        verify(sysOperationLogMapper).insert(any(com.community.residence.auth.entity.SysOperationLog.class));
    }

    @Test
    @DisplayName("越社区代建：ADMIN 绑定范围外 403")
    void adminCreate_outOfBoundCommunity_forbidden() {
        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            mocked.when(() -> SecurityUtils.checkCommunityAccess(3L))
                    .thenThrow(new ForbiddenException("无权操作未绑定社区的数据"));

            assertThatThrownBy(() -> residentService.adminCreate(dto()))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    @Test
    @DisplayName("手机号重复：409 语义（DATA_EXISTS 业务拒绝）")
    void adminCreate_duplicatePhone_rejected() {
        /* 用户名唯一性查 0 次（通过）→ 手机号唯一性查 1 次（冲突） */
        when(residentMapper.selectCount(any())).thenReturn(0L, 1L);
        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            assertThatThrownBy(() -> residentService.adminCreate(dto()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("手机号已存在");
        }
    }

    @Test
    @DisplayName("批量导入部分成功：3 行 2 成功 1 失败（重复手机号），失败行带行号与原因")
    void importCsv_partialSuccess() {
        /* selectCount 调用序列（每行成功创建调 2 次：用户名唯一 + 手机号唯一）：
           行1(0,0) → 行2(0,0) → 行3(0,1 手机号冲突) */
        when(residentMapper.selectCount(any())).thenReturn(0L, 0L, 0L, 0L, 0L, 1L);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(residentMapper.insert(org.mockito.ArgumentMatchers.<Resident>any()))
                .thenAnswer(inv -> {
                    inv.getArgument(0, Resident.class).setId(100L);
                    return 1;
                });
        String csv = "社区ID,姓名,手机号,证件号\n"
                + "3,张三,13900000011,\n"
                + "3,李四,13900000012,\n"
                + "3,王五,13900000012,\n";
        InputStream in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            ResidentImportVO result = residentService.importCsv(in);

            assertThat(result.getTotal()).isEqualTo(3);
            assertThat(result.getSuccess()).isEqualTo(2);
            assertThat(result.getFail()).isEqualTo(1);
            assertThat(result.getFailRows()).hasSize(1);
            assertThat(result.getFailRows().get(0).getRow()).isEqualTo(3);
            assertThat(result.getFailRows().get(0).getReason()).contains("手机号已存在");
            assertThat(result.getSuccessRows().get(0).getInitialPassword()).isEqualTo("000011");
        }
    }

    @Test
    @DisplayName("坏格式行：列数不足/手机号非法/社区ID非数字 → 该行失败不影响他行")
    void importCsv_badRows_isolated() {
        /* 唯一成功行（行1）调 2 次 selectCount；其余坏行在校验阶段即失败不触库 */
        when(residentMapper.selectCount(any())).thenReturn(0L, 0L);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(residentMapper.insert(org.mockito.ArgumentMatchers.<Resident>any())).thenAnswer(inv -> {
            inv.getArgument(0, Resident.class).setId(101L);
            return 1;
        });
        String csv = "社区ID,姓名,手机号,证件号\n"
                + "3,张三,13900000021,\n"
                + "3,李四,not-a-phone,\n"
                + "abc,王五,13900000023,\n"
                + "3,赵六\n";
        InputStream in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            ResidentImportVO result = residentService.importCsv(in);

            assertThat(result.getTotal()).isEqualTo(4);
            assertThat(result.getSuccess()).isEqualTo(1);
            assertThat(result.getFail()).isEqualTo(3);
            List<String> reasons = result.getFailRows().stream()
                    .map(ResidentImportVO.FailRow::getReason).toList();
            assertThat(reasons).anySatisfy(r -> assertThat(r).contains("手机号格式不正确"));
            assertThat(reasons).anySatisfy(r -> assertThat(r)
                    .containsAnyOf("列数不足", "For input string"));
        }
    }

    @Test
    @DisplayName("仅表头无数据行：400 拒绝")
    void importCsv_emptyData_rejected() {
        InputStream in = new ByteArrayInputStream(
                "社区ID,姓名,手机号,证件号\n".getBytes(StandardCharsets.UTF_8));
        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            assertThatThrownBy(() -> residentService.importCsv(in))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("无数据行");
        }
    }

    @Test
    @DisplayName("CSV 引号转义：含逗号引号的姓名正常解析")
    void importCsv_quotedField_parsed() {
        when(residentMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(residentMapper.insert(org.mockito.ArgumentMatchers.<Resident>any())).thenAnswer(inv -> {
            inv.getArgument(0, Resident.class).setId(102L);
            return 1;
        });
        String csv = "社区ID,姓名,手机号,证件号\n"
                + "3,\"张,三\",13900000031,\n";
        InputStream in = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));

        try (MockedStatic<SecurityUtils> mocked = mockSuperAdmin()) {
            ResidentImportVO result = residentService.importCsv(in);
            assertThat(result.getSuccess()).isEqualTo(1);
            ArgumentCaptor<Resident> captor = ArgumentCaptor.forClass(Resident.class);
            verify(residentMapper).insert(captor.capture());
            assertThat(captor.getValue().getRealName()).isEqualTo("张,三");
        }
    }
}
