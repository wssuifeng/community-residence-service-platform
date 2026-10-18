package com.community.residence.common.lease.service;

import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class LeaseFirstPaymentGateTest {
    @org.mockito.Mock private com.community.residence.common.lease.service.LeaseContractTermService termService;
    @Mock LeaseRecordMapper leaseMapper;
    @Mock HouseMapper houseMapper;
    @Mock LeasePaymentMapper paymentMapper;
    @Mock LeaseChangeLogService changeLogService;
    @InjectMocks LeaseService service;

    @Test
    void signedButUnpaidCannotBecomeActive() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), LeaseRecord.class);
        LeaseRecord lease = new LeaseRecord();
        lease.setId(1L); lease.setHouseId(2L); lease.setStatus("TO_SIGN"); lease.setAgreementStatus("SIGNED");
        lenient().when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);
        assertThatThrownBy(() -> service.applyTransition(lease, "ACTIVE", "旁路生效"))
            .isInstanceOf(com.community.residence.common.web.exception.BusinessException.class);
        verify(leaseMapper, never()).update(any(LeaseRecord.class), any());
    }
}
