package com.community.residence.workorder.service;

import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.constant.ShiftType;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ResourceNotFoundException;
import com.community.residence.community.entity.Community;
import com.community.residence.community.mapper.CommunityMapper;
import com.community.residence.workorder.dto.BatchSaveScheduleDTO;
import com.community.residence.workorder.dto.ClearScheduleDTO;
import com.community.residence.workorder.entity.StaffSchedule;
import com.community.residence.workorder.mapper.StaffScheduleMapper;
import com.community.residence.workorder.vo.BatchSaveScheduleResultVO;
import com.community.residence.workorder.vo.StaffScheduleVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 服务人员排班测试：批量保存覆盖语义、REST 清空时间、班次时间默认/覆盖、
    日期范围与人员校验、清空范围、班次标签映射 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffScheduleService 单元测试")
class StaffScheduleServiceTest {

    @Mock
    private StaffScheduleMapper staffScheduleMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private CommunityMapper communityMapper;

    @InjectMocks
    private StaffScheduleService staffScheduleService;

    private BatchSaveScheduleDTO dto(String shiftType, List<Long> staffIds, List<String> dates) {
        BatchSaveScheduleDTO dto = new BatchSaveScheduleDTO();
        dto.setCommunityId(1L);
        dto.setStaffIds(staffIds);
        dto.setDates(dates);
        dto.setShiftType(shiftType);
        return dto;
    }

    private void givenCommunity() {
        Community community = new Community();
        community.setId(1L);
        community.setName("幸福社区");
        when(communityMapper.selectById(1L)).thenReturn(community);
    }

    private void givenStaff(Long... ids) {
        when(sysUserMapper.selectList(any())).thenReturn(
                Arrays.stream(ids).map(this::staffOf).toList());
    }

    private SysUser staffOf(Long id) {
        SysUser staff = new SysUser();
        staff.setId(id);
        staff.setRole("STAFF");
        staff.setRealName("张服务");
        return staff;
    }

    private StaffSchedule schedule(Long id, Long staffId, LocalDate date) {
        StaffSchedule row = new StaffSchedule();
        row.setId(id);
        row.setStaffId(staffId);
        row.setCommunityId(1L);
        row.setWorkDate(date);
        return row;
    }

    private MockedStatic<com.community.residence.common.context.SecurityUtils> mockSecurity() {
        return mockStatic(com.community.residence.common.context.SecurityUtils.class);
    }

    @Test
    @DisplayName("批量保存：同社区同人同日已存在 → 覆盖更新（不新增），saved 计一条")
    void batchSave_existingRow_overwritten() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenCommunity();
            givenStaff(7L);
            LocalDate date = LocalDate.of(2026, 9, 21);
            when(staffScheduleMapper.selectList(any())).thenReturn(List.of(schedule(100L, 7L, date)));

            BatchSaveScheduleResultVO result = staffScheduleService.batchSave(
                    dto(ShiftType.MORNING, List.of(7L), List.of("2026-09-21")));

            assertThat(result.getSaved()).isEqualTo(1);
            ArgumentCaptor<StaffSchedule> captor = ArgumentCaptor.forClass(StaffSchedule.class);
            verify(staffScheduleMapper).updateById(captor.capture());
            verify(staffScheduleMapper, never()).insert(any(StaffSchedule.class));
            assertThat(captor.getValue().getId()).isEqualTo(100L);
            assertThat(captor.getValue().getShiftType()).isEqualTo(ShiftType.MORNING);
            assertThat(captor.getValue().getStartTime()).isEqualTo(LocalTime.of(8, 0));
            assertThat(captor.getValue().getEndTime()).isEqualTo(LocalTime.of(12, 0));
        }
    }

    @Test
    @DisplayName("批量保存：人员×日期逐条插入（saved=人数×天数），FULL 落 08:00-18:00")
    void batchSave_cartesianInsert_withDefaultShiftTimes() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenCommunity();
            givenStaff(7L, 8L);
            when(staffScheduleMapper.selectList(any())).thenReturn(List.of());

            BatchSaveScheduleResultVO result = staffScheduleService.batchSave(
                    dto(ShiftType.FULL, List.of(7L, 8L), List.of("2026-09-21", "2026-09-22")));

            assertThat(result.getSaved()).isEqualTo(4);
            ArgumentCaptor<StaffSchedule> captor = ArgumentCaptor.forClass(StaffSchedule.class);
            verify(staffScheduleMapper, times(4)).insert(captor.capture());
            assertThat(captor.getAllValues()).allSatisfy(row -> {
                assertThat(row.getShiftType()).isEqualTo(ShiftType.FULL);
                assertThat(row.getStartTime()).isEqualTo(LocalTime.of(8, 0));
                assertThat(row.getEndTime()).isEqualTo(LocalTime.of(18, 0));
            });
        }
    }

    @Test
    @DisplayName("批量保存：REST（休息）强制清空起止时间，即便传了自定义时间")
    void batchSave_rest_clearsShiftTimes() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenCommunity();
            givenStaff(7L);
            when(staffScheduleMapper.selectList(any())).thenReturn(List.of());
            BatchSaveScheduleDTO dto = dto(ShiftType.REST, List.of(7L), List.of("2026-09-21"));
            dto.setStartTime(LocalTime.of(9, 0));
            dto.setEndTime(LocalTime.of(10, 0));

            staffScheduleService.batchSave(dto);

            ArgumentCaptor<StaffSchedule> captor = ArgumentCaptor.forClass(StaffSchedule.class);
            verify(staffScheduleMapper).insert(captor.capture());
            assertThat(captor.getValue().getShiftType()).isEqualTo(ShiftType.REST);
            assertThat(captor.getValue().getStartTime()).isNull();
            assertThat(captor.getValue().getEndTime()).isNull();
        }
    }

    @Test
    @DisplayName("批量保存：自定义起止时间覆盖班次默认值")
    void batchSave_customTimes_overrideDefaults() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenCommunity();
            givenStaff(7L);
            when(staffScheduleMapper.selectList(any())).thenReturn(List.of());
            BatchSaveScheduleDTO dto = dto(ShiftType.MORNING, List.of(7L), List.of("2026-09-21"));
            dto.setStartTime(LocalTime.of(7, 30));
            dto.setEndTime(LocalTime.of(11, 30));

            staffScheduleService.batchSave(dto);

            ArgumentCaptor<StaffSchedule> captor = ArgumentCaptor.forClass(StaffSchedule.class);
            verify(staffScheduleMapper).insert(captor.capture());
            assertThat(captor.getValue().getStartTime()).isEqualTo(LocalTime.of(7, 30));
            assertThat(captor.getValue().getEndTime()).isEqualTo(LocalTime.of(11, 30));
        }
    }

    @Test
    @DisplayName("批量保存：开始时间不早于结束时间 → INVALID_PARAM（400），不落库")
    void batchSave_startNotBeforeEnd_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenCommunity();
            givenStaff(7L);
            BatchSaveScheduleDTO dto = dto(ShiftType.MORNING, List.of(7L), List.of("2026-09-21"));
            dto.setStartTime(LocalTime.of(12, 0));
            dto.setEndTime(LocalTime.of(9, 0));

            assertThatThrownBy(() -> staffScheduleService.batchSave(dto))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM))
                    .hasMessageContaining("早于结束时间");
            verify(staffScheduleMapper, never()).insert(any(StaffSchedule.class));
        }
    }

    @Test
    @DisplayName("批量保存：非法班次取值 → INVALID_PARAM")
    void batchSave_unknownShiftType_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenCommunity();
            givenStaff(7L);

            assertThatThrownBy(() -> staffScheduleService.batchSave(
                    dto("NIGHT", List.of(7L), List.of("2026-09-21"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("班次取值");
        }
    }

    @Test
    @DisplayName("批量保存：非 STAFF/不存在的人员 → DATA_NOT_FOUND（整批拒绝）")
    void batchSave_illegalStaff_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenCommunity();
            when(sysUserMapper.selectList(any())).thenReturn(List.of());

            assertThatThrownBy(() -> staffScheduleService.batchSave(
                    dto(ShiftType.MORNING, List.of(7L), List.of("2026-09-21"))))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.DATA_NOT_FOUND))
                    .hasMessageContaining("服务人员不存在");
            verify(staffScheduleMapper, never()).insert(any(StaffSchedule.class));
        }
    }

    @Test
    @DisplayName("批量保存：日期格式非法 → INVALID_PARAM")
    void batchSave_badDateFormat_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            givenCommunity();
            givenStaff(7L);

            assertThatThrownBy(() -> staffScheduleService.batchSave(
                    dto(ShiftType.MORNING, List.of(7L), List.of("2026/09/21"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("yyyy-MM-dd");
        }
    }

    @Test
    @DisplayName("排班列表：日期范围必填、跨度上限 62 天（62 天放行、63 天报 INVALID_PARAM）")
    void list_dateRangeLimit() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            LocalDate start = LocalDate.of(2026, 9, 1);
            assertThatThrownBy(() -> staffScheduleService.list(1L, start, start.plusDays(62), null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_PARAM))
                    .hasMessageContaining("跨度");

            when(staffScheduleMapper.selectList(any())).thenReturn(List.of());
            assertThat(staffScheduleService.list(1L, start, start.plusDays(61), null)).isEmpty();

            assertThatThrownBy(() -> staffScheduleService.list(1L, null, start, null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不能为空");
        }
    }

    @Test
    @DisplayName("排班列表：装配人员姓名、社区名称与班次标签")
    void list_assemblesNamesAndShiftLabel() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            StaffSchedule row = schedule(1L, 7L, LocalDate.of(2026, 9, 21));
            row.setShiftType(ShiftType.EVENING);
            row.setStartTime(LocalTime.of(18, 0));
            row.setEndTime(LocalTime.of(22, 0));
            when(staffScheduleMapper.selectList(any())).thenReturn(List.of(row));
            when(sysUserMapper.selectList(any())).thenReturn(List.of(staffOf(7L)));
            Community community = new Community();
            community.setId(1L);
            community.setName("幸福社区");
            when(communityMapper.selectList(any())).thenReturn(List.of(community));

            List<StaffScheduleVO> records = staffScheduleService.list(1L,
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null);

            assertThat(records).hasSize(1);
            assertThat(records.get(0).getStaffName()).isEqualTo("张服务");
            assertThat(records.get(0).getCommunityName()).isEqualTo("幸福社区");
            assertThat(records.get(0).getShiftLabel()).isEqualTo("晚班");
        }
    }

    @Test
    @DisplayName("清空排班：按社区+日期范围（可带人员筛选）删除，返回清除条数")
    void clear_deletesRange() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(staffScheduleMapper.delete(any())).thenReturn(3);
            ClearScheduleDTO dto = new ClearScheduleDTO();
            dto.setCommunityId(1L);
            dto.setStaffIds(List.of(7L, 8L));
            dto.setStartDate(LocalDate.of(2026, 9, 1));
            dto.setEndDate(LocalDate.of(2026, 9, 30));

            BatchSaveScheduleResultVO result = staffScheduleService.clear(dto);

            assertThat(result.getSaved()).isEqualTo(3);
            verify(staffScheduleMapper).delete(any());
        }
    }

    @Test
    @DisplayName("清空排班：日期范围越界 → INVALID_PARAM，且不执行删除")
    void clear_rangeTooLong_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            ClearScheduleDTO dto = new ClearScheduleDTO();
            dto.setCommunityId(1L);
            dto.setStartDate(LocalDate.of(2026, 1, 1));
            dto.setEndDate(LocalDate.of(2026, 12, 31));

            assertThatThrownBy(() -> staffScheduleService.clear(dto))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("跨度");
            verify(staffScheduleMapper, never()).delete(any());
        }
    }

    @Test
    @DisplayName("删除单条排班：记录不存在 → 404")
    void delete_notFound_throws() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked = mockSecurity()) {
            when(staffScheduleMapper.selectById(9L)).thenReturn(null);

            assertThatThrownBy(() -> staffScheduleService.delete(9L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    @DisplayName("班次标签映射：批量取指定日期班次（未排班人员不出现在结果中）")
    void shiftLabelsOn_mapsLabel() {
        StaffSchedule morning = schedule(1L, 7L, LocalDate.of(2026, 9, 21));
        morning.setShiftType(ShiftType.MORNING);
        StaffSchedule rest = schedule(2L, 8L, LocalDate.of(2026, 9, 21));
        rest.setShiftType(ShiftType.REST);
        when(staffScheduleMapper.selectList(any())).thenReturn(List.of(morning, rest));

        var labels = staffScheduleService.shiftLabelsOn(List.of(7L, 8L, 9L), 1L,
                LocalDate.of(2026, 9, 21));

        assertThat(labels).hasSize(2).containsEntry(7L, "早班").containsEntry(8L, "休息");
    }
}
