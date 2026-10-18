package com.community.residence.common.payment.service;

import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.LeaseService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

final class SettlementTestInfrastructure {
    private SettlementTestInfrastructure() { }
    static void install(PaymentService service, LeasePaymentMapper payments, LeaseRecordMapper leases) {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        LeaseService leaseService = mock(LeaseService.class);
        when(leaseService.lockForSettlement(anyLong())).thenAnswer(i -> leases.selectById(i.getArgument(0, Long.class)));
        lenient().when(payments.selectByIdForUpdate(anyLong())).thenAnswer(i -> {
            var value = payments.selectById(i.getArgument(0, Long.class));
            return value != null ? value : payments.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>());
        });
        service.setSettlementInfrastructure(manager, leaseService);
        var terms=mock(com.community.residence.common.lease.service.LeaseContractTermService.class);
        lenient().when(terms.canSettle(anyLong(),anyLong(),anyString())).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"termService",terms);
    }
}
