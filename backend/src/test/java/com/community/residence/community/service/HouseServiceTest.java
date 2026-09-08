package com.community.residence.community.service;

import com.community.residence.community.dto.UpdateHouseStatusDTO;
import com.community.residence.community.entity.House;
import com.community.residence.community.entity.HouseStatusHistory;
import com.community.residence.community.entity.Unit;
import com.community.residence.community.mapper.HouseMapper;
import com.community.residence.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.community.mapper.UnitMapper;
import com.community.residence.resident.mapper.ResidenceRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 房屋业务逻辑测试：状态变更留痕（house_status_history） */
@ExtendWith(MockitoExtension.class)
@DisplayName("HouseService 单元测试")
class HouseServiceTest {

    @Mock
    private HouseMapper houseMapper;
    @Mock
    private UnitMapper unitMapper;
    @Mock
    private HouseStatusHistoryMapper historyMapper;
    @Mock
    private ResidenceRelationMapper residenceRelationMapper;

    @InjectMocks
    private HouseService houseService;

    private House house;

    @BeforeEach
    void setUp() {
        house = new House();
        house.setId(1L);
        house.setUnitId(1L);
        house.setCommunityId(1L);
        house.setHouseNumber("101");
        house.setStatus("VACANT");
    }

    @Test
    @DisplayName("状态变更：主表更新 + 历史记录写入（old/new/操作人）")
    void updateStatus_writesHistory() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            mocked.when(com.community.residence.common.context.SecurityUtils::getUserId)
                    .thenReturn(42L);
            when(houseMapper.selectById(1L)).thenReturn(house);
            when(houseMapper.updateById(any(House.class))).thenReturn(1);

            UpdateHouseStatusDTO dto = new UpdateHouseStatusDTO();
            dto.setStatus("OCCUPIED");
            dto.setRemark("tenant moved in");
            houseService.updateStatus(1L, dto);

            assertThat(house.getStatus()).isEqualTo("OCCUPIED");
            ArgumentCaptor<HouseStatusHistory> captor =
                    ArgumentCaptor.forClass(HouseStatusHistory.class);
            verify(historyMapper).insert(captor.capture());
            HouseStatusHistory history = captor.getValue();
            assertThat(history.getOldStatus()).isEqualTo("VACANT");
            assertThat(history.getNewStatus()).isEqualTo("OCCUPIED");
            assertThat(history.getOperatorId()).isEqualTo(42L);
            assertThat(history.getRemark()).isEqualTo("tenant moved in");
        }
    }

    @Test
    @DisplayName("状态未变：跳过历史记录写入（幂等）")
    void updateStatus_sameStatus_noHistory() {
        try (MockedStatic<com.community.residence.common.context.SecurityUtils> mocked =
                     mockStatic(com.community.residence.common.context.SecurityUtils.class)) {
            when(houseMapper.selectById(1L)).thenReturn(house);

            UpdateHouseStatusDTO dto = new UpdateHouseStatusDTO();
            dto.setStatus("VACANT");
            houseService.updateStatus(1L, dto);

            verify(historyMapper, org.mockito.Mockito.never()).insert(any(HouseStatusHistory.class));
        }
    }
}
