package com.community.residence.common.payment.service;

import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.service.DepositRefundService;
import com.community.residence.common.lease.service.LeaseChangeLogService;
import com.community.residence.common.lease.service.LeaseContractTermService;
import com.community.residence.common.lease.service.LeaseExtensionService;
import com.community.residence.common.lease.service.LeaseMoveOutApplicationService;
import com.community.residence.common.lease.service.LeasePaymentDeadlineService;
import com.community.residence.common.lease.service.LeaseService;
import com.community.residence.common.lease.service.LeaseTermRules;
import com.community.residence.common.lease.service.RentSchedule;
import com.community.residence.common.payment.entity.LeasePayment;
import com.community.residence.common.payment.mapper.LeasePaymentMapper;
import com.community.residence.common.payment.config.PaymentProperties;
import com.community.residence.common.payment.service.channel.AlipayChannelClient;
import com.community.residence.common.payment.service.channel.ChannelCallException;
import com.community.residence.common.payment.service.channel.ChannelRefundResult;
import com.community.residence.common.payment.service.channel.ChannelTradeResult;
import com.community.residence.common.payment.service.channel.DisabledChannelClient;
import com.community.residence.common.payment.service.channel.ManagedAlipayChannelClient;
import com.community.residence.common.payment.service.channel.PaymentChannelClient;
import com.community.residence.common.payment.service.channel.WechatChannelClient;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.BuildingMapper;
import com.community.residence.common.community.mapper.CommunityMapper;
import com.community.residence.common.community.mapper.HouseImageMapper;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.community.mapper.HouseStatusHistoryMapper;
import com.community.residence.common.community.mapper.PublicResourceMapper;
import com.community.residence.common.community.mapper.ResourceTimeslotMapper;
import com.community.residence.common.community.mapper.StructureBusinessCodeMapper;
import com.community.residence.common.community.mapper.UnitMapper;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidenceApplicationMapper;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.resident.mapper.ResidentMapper;
import com.community.residence.common.auth.mapper.SysConfigMapper;
import com.community.residence.common.agreement.mapper.LeaseAgreementMapper;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.messaging.service.NotificationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.math.BigDecimal;
import java.time.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

/** 内存已确认结果；无真实渠道请求，不作为交易通过证据。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FirstPaymentActivationTest {
    @Mock LeasePaymentMapper payments;
    @Mock LeaseRecordMapper leaseMapper;
    @Mock ResidentMapper residentMapper;
    @Mock HouseMapper houseMapper;
    @Mock UnitMapper unitMapper;
    @Mock BuildingMapper buildingMapper;
    @Mock CommunityMapper communityMapper;
    @Mock ResidenceRelationMapper relationMapper;
    @Mock LeaseAgreementMapper agreementMapper;
    @Mock ConversationService conversationService;
    @Mock LeaseChangeLogService changeLogService;
    @Mock NotificationService notifications;
    @Mock SysAdminCommunityMapper admins;
    @Mock PlatformTransactionManager transactions;
    PaymentService service;
    LeaseRecord lease;
    LeasePayment payment;
    House house;
    @BeforeEach void setUp() {
        for (Class<?> type : new Class<?>[]{LeaseRecord.class, LeasePayment.class, ResidenceRelation.class}) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""), type);
        }
        LeaseService leases = new LeaseService(new com.community.residence.common.resident.service.ResidenceDependencyService(relationMapper), leaseMapper, residentMapper, houseMapper, unitMapper, buildingMapper,
            communityMapper, changeLogService, payments, relationMapper, agreementMapper, conversationService);
        service = new PaymentService(payments, leaseMapper, admins, notifications, new PaymentProperties(), mock(PaymentChannelClient.class), mock(PaymentChannelClient.class));
        service.setSettlementInfrastructure(transactions, leases);
        var terms=mock(com.community.residence.common.lease.service.LeaseContractTermService.class);
        when(terms.canSettle(anyLong(),anyLong(),anyString())).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"termService",terms);
        when(transactions.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        lease = new LeaseRecord(); lease.setId(7L); lease.setTenantId(8L); lease.setCommunityId(1L); lease.setHouseId(9L);
        lease.setStatus("TO_SIGN"); lease.setAgreementStatus("SIGNED"); lease.setStartDate(LocalDate.now()); lease.setEndDate(LocalDate.now().plusMonths(12)); lease.setMonthlyRent(new BigDecimal("3000")); lease.setDeposit(BigDecimal.ZERO);
        payment = new LeasePayment(); payment.setId(10L); payment.setTermId(4L); payment.setDepositAmount(BigDecimal.ZERO); payment.setLeaseId(7L); payment.setResidentId(8L); payment.setPaymentNo("MEMORY-FIRST"); payment.setPaymentType("FIRST"); payment.setStatus("PENDING"); payment.setAmount(new BigDecimal("3000")); payment.setMonths(1);
        house = new House(); house.setId(9L); house.setStatus("VACANT");
        when(payments.selectById(10L)).thenReturn(payment); when(payments.selectByIdForUpdate(10L)).thenReturn(payment);
        when(leaseMapper.selectById(7L)).thenReturn(lease); when(leaseMapper.selectByIdForUpdate(7L)).thenReturn(lease);
        when(leaseMapper.update(any(LeaseRecord.class), any())).thenReturn(1);
        when(houseMapper.selectByIdForUpdate(9L)).thenReturn(house); when(houseMapper.selectById(9L)).thenReturn(house);
        when(payments.selectCount(any())).thenAnswer(i -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.Wrapper<?>)i.getArgument(0);
            return wrapper.getSqlSegment().contains("<>") ? 0L : ("SUCCESS".equals(payment.getStatus()) ? 1L : 0L);
        });
    }
    private ChannelTradeResult confirmed() { return new ChannelTradeResult("MEMORY-ONLY", LocalDateTime.now()); }
    @Test void firstSuccessActivatesAndLinksRelationWithoutExtendingLease() {
        LocalDate end = lease.getEndDate(); service.settleSuccess(payment, confirmed());
        assertThat(payment.getStatus()).isEqualTo("SUCCESS"); assertThat(lease.getStatus()).isEqualTo("ACTIVE"); assertThat(house.getStatus()).isEqualTo("OCCUPIED"); assertThat(lease.getEndDate()).isEqualTo(end);
        ArgumentCaptor<ResidenceRelation> capture = ArgumentCaptor.forClass(ResidenceRelation.class); verify(relationMapper).insert(capture.capture()); assertThat(capture.getValue().getLeaseId()).isEqualTo(7L); assertThat(capture.getValue().getResidentId()).isEqualTo(8L);
        var order = inOrder(houseMapper, leaseMapper, payments); order.verify(houseMapper).selectByIdForUpdate(9L); order.verify(leaseMapper).selectByIdForUpdate(7L); order.verify(payments).selectByIdForUpdate(10L); verify(transactions).commit(any());
    }
    @Test void repeatedSuccessDoesNotDuplicateRelation() {
        service.settleSuccess(payment, confirmed()); service.settleSuccess(payment, confirmed()); verify(relationMapper, times(1)).insert(any(ResidenceRelation.class)); verify(changeLogService, times(1)).logStatus(eq(7L), eq("TO_SIGN"), eq("ACTIVE"), anyString());
    }
    @Test void noConfirmedResultDoesNotStartTransaction() {
        service.settleSuccess(payment, null); assertThat(lease.getStatus()).isEqualTo("TO_SIGN"); verifyNoInteractions(transactions); verify(relationMapper, never()).insert(any(ResidenceRelation.class));
    }
    @Test void closedOrderDoesNotActivate() {
        payment.setStatus("CLOSED"); service.settleSuccess(payment, confirmed()); assertThat(lease.getStatus()).isEqualTo("TO_SIGN"); verify(relationMapper, never()).insert(any(ResidenceRelation.class));
    }
    @Test void unsignedLeaseRollsBack() {
        lease.setAgreementStatus("PARTIAL"); assertThatThrownBy(() -> service.settleSuccess(payment, confirmed())).isInstanceOf(com.community.residence.common.web.exception.BusinessException.class); verify(transactions).rollback(any()); verify(transactions, never()).commit(any()); verify(relationMapper, never()).insert(any(ResidenceRelation.class)); assertThat(house.getStatus()).isEqualTo("VACANT");
    }
    @Test void relationFailureRollsBackAndNeverMarksHouseOccupied() {
        when(relationMapper.insert(any(ResidenceRelation.class))).thenThrow(new IllegalStateException("fixture failure")); assertThatThrownBy(() -> service.settleSuccess(payment, confirmed())).isInstanceOf(IllegalStateException.class); verify(transactions).rollback(any()); verify(transactions, never()).commit(any()); verify(houseMapper, never()).updateById(any(House.class));
    }
    @Test void legacyActiveFirstDoesNotCreateAnotherRelation() {
        lease.setStatus("ACTIVE"); service.settleSuccess(payment, confirmed()); verify(relationMapper, never()).insert(any(ResidenceRelation.class)); assertThat(payment.getStatus()).isEqualTo("SUCCESS");
    }
    @Test void cancelledLeaseRejectsActivation() {
        lease.setStatus("REJECTED"); service.settleSuccess(payment, confirmed()); assertThat(payment.getSettlementIssue()).isNotBlank(); assertThat(payment.getStatus()).isEqualTo("PENDING"); assertThat(house.getStatus()).isEqualTo("VACANT");
    }
}
