package com.community.residence.common.lease.service;
import com.community.residence.common.lease.entity.LeaseChangeLog;
import com.community.residence.common.lease.entity.LeaseContractTerm;
import com.community.residence.common.lease.entity.LeaseDepositRefund;
import com.community.residence.common.lease.entity.LeaseExtensionApplication;
import com.community.residence.common.lease.entity.LeaseMoveOutApplication;
import com.community.residence.common.lease.entity.LeaseRecord;
import com.community.residence.common.lease.entity.LeaseReminder;
import com.community.residence.common.lease.mapper.LeaseChangeLogMapper;
import com.community.residence.common.lease.mapper.LeaseContractTermMapper;
import com.community.residence.common.lease.mapper.LeaseDepositRefundMapper;
import com.community.residence.common.lease.mapper.LeaseExtensionApplicationMapper;
import com.community.residence.common.lease.mapper.LeaseMoveOutApplicationMapper;
import com.community.residence.common.lease.mapper.LeaseRecordMapper;
import com.community.residence.common.lease.mapper.LeaseReminderMapper;
import com.community.residence.common.lease.dto.ApproveMoveOutDTO;
import com.community.residence.common.lease.dto.CreateDepositRefundDTO;
import com.community.residence.common.lease.dto.CreateLeaseDTO;
import com.community.residence.common.lease.dto.CreateMoveOutApplicationDTO;
import com.community.residence.common.lease.dto.LeaseExtensionDTO;
import com.community.residence.common.lease.dto.RejectMoveOutDTO;
import com.community.residence.common.lease.dto.RenewLeaseDTO;
import com.community.residence.common.lease.dto.ReviewLeaseExtensionDTO;
import com.community.residence.common.lease.dto.UpdateLeaseStatusDTO;
import com.community.residence.common.security.context.IdentityPrincipal;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.security.context.UserContext;
import com.community.residence.common.web.exception.BusinessException;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.web.exception.GlobalExceptionHandler;
import com.community.residence.common.web.exception.ResourceNotFoundException;
import com.community.residence.common.web.exception.UnauthorizedException;
import com.community.residence.common.messaging.service.NotificationService;
import com.community.residence.common.conversation.service.ConversationService;
import com.community.residence.common.auth.mapper.SysAdminCommunityMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;import java.math.BigDecimal;import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.any;
@ExtendWith(MockitoExtension.class) @MockitoSettings(strictness=Strictness.LENIENT)
class LeaseExtensionServiceTest {
 @Mock LeaseRecordMapper leases;@Mock LeaseExtensionApplicationMapper applications;@Mock LeaseContractTermMapper terms;
 @Mock LeaseContractTermService termService;@Mock LeaseChangeLogService history;@Mock NotificationService notifications;@Mock ConversationService conversations;@Mock SysAdminCommunityMapper bindings;
 @InjectMocks LeaseExtensionService service;LeaseRecord lease;LeaseExtensionApplication application;
 @BeforeEach void setup(){
  SecurityUtils.setAuthentication(new UserContext(1L,"租客","RESIDENT",Set.of()));lease=new LeaseRecord();lease.setId(10L);lease.setTenantId(1L);lease.setCommunityId(2L);lease.setStatus("ACTIVE");lease.setStartDate(LocalDate.now());lease.setEndDate(lease.getStartDate().plusMonths(12).minusDays(1));lease.setMonthlyRent(new BigDecimal("3600"));lease.setDeposit(new BigDecimal("10000"));
  when(leases.selectByIdForUpdate(10L)).thenReturn(lease);when(leases.selectById(10L)).thenReturn(lease);
  var term=new LeaseContractTerm();term.setId(20L);term.setStatus("ACTIVE");term.setContractMonths(12);term.setMonthlyRent(lease.getMonthlyRent());term.setStartDate(lease.getStartDate());term.setEndDate(lease.getEndDate());when(termService.rows(10L)).thenReturn(List.of(term));
  application=new LeaseExtensionApplication();application.setId(30L);application.setLeaseId(10L);application.setStatus("PENDING");application.setResidentId(1L);application.setMonths(3);application.setOriginalEndDate(lease.getEndDate());application.setProposedEndDate(lease.getStartDate().plusMonths(15).minusDays(1));application.setMonthlyRent(lease.getMonthlyRent());application.setDeposit(lease.getDeposit());when(applications.lock(30L)).thenReturn(application);
 }
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 LeaseExtensionDTO request(){var d=new LeaseExtensionDTO();d.setMonths(3);d.setReason("继续居住");return d;}
 ReviewLeaseExtensionDTO approve(){var d=new ReviewLeaseExtensionDTO();d.setApprove(true);return d;}
 void admin(){SecurityUtils.setAuthentication(new UserContext(2L,"管理员","ADMIN",Set.of(2L)));}
 @Test void applySnapshotsAndDoesNotChangeLeaseOrCreatePayment(){var end=lease.getEndDate();var a=service.apply(10L,request());assertThat(a.getOriginalEndDate()).isEqualTo(end);assertThat(a.getProposedEndDate()).isEqualTo(lease.getStartDate().plusMonths(15).minusDays(1));assertThat(a.getStatus()).isEqualTo("PENDING");verify(leases,never()).updateById(any(LeaseRecord.class));verify(terms,never()).insert(any(LeaseContractTerm.class));}
 @Test void approvalImmediatelyExtendsOnceWithoutNewResidenceOrDeposit(){admin();service.review(10L,30L,approve());service.review(10L,30L,approve());assertThat(lease.getEndDate()).isEqualTo(application.getProposedEndDate());assertThat(lease.getDeposit()).isEqualByComparingTo("10000");assertThat(application.getStatus()).isEqualTo("APPROVED");verify(leases,times(1)).updateById(any(LeaseRecord.class));var c=ArgumentCaptor.forClass(LeaseContractTerm.class);verify(terms,times(1)).insert(c.capture());assertThat(c.getValue().getStatus()).isEqualTo("ACTIVE");assertThat(c.getValue().getDeposit()).isZero();}
 @Test void duplicatePendingRejected(){when(applications.selectCount(any())).thenReturn(1L);assertThatThrownBy(()->service.apply(10L,request())).hasMessageContaining("已有待审");verify(applications,never()).insert(any(LeaseExtensionApplication.class));}
 @Test void changedTermOrPriceCannotApproveOldSnapshot(){admin();lease.setEndDate(lease.getEndDate().plusDays(1));assertThatThrownBy(()->service.review(10L,30L,approve())).hasMessageContaining("已改变");verify(terms,never()).insert(any(LeaseContractTerm.class));}
 @Test void rejectNeedsReasonAndDoesNotExtend(){admin();var d=new ReviewLeaseExtensionDTO();d.setApprove(false);assertThatThrownBy(()->service.review(10L,30L,d)).hasMessageContaining("原因");d.setReason("暂不延长");var end=lease.getEndDate();service.review(10L,30L,d);assertThat(lease.getEndDate()).isEqualTo(end);assertThat(application.getStatus()).isEqualTo("REJECTED");}
 @Test void withdrawnOnlyAffectsOwnPending(){service.withdraw(10L,30L);assertThat(application.getStatus()).isEqualTo("WITHDRAWN");application.setStatus("APPROVED");assertThatThrownBy(()->service.withdraw(10L,30L)).hasMessageContaining("仅待审核");}
 @Test void otherResidentAndOutsideAdminDenied(){SecurityUtils.setAuthentication(new UserContext(9L,"别人","RESIDENT",Set.of()));assertThatThrownBy(()->service.apply(10L,request())).isInstanceOf(ForbiddenException.class);SecurityUtils.setAuthentication(new UserContext(9L,"外社区管理","ADMIN",Set.of(99L)));assertThatThrownBy(()->service.review(10L,30L,approve())).isInstanceOf(ForbiddenException.class);verify(terms,never()).insert(any(LeaseContractTerm.class));}
 @Test void expiredOrMovedOutCannotExtend(){lease.setStatus("MOVED_OUT");assertThatThrownBy(()->service.apply(10L,request())).hasMessageContaining("有效租约");lease.setStatus("ACTIVE");lease.setEndDate(LocalDate.now().minusDays(1));assertThatThrownBy(()->service.apply(10L,request())).hasMessageContaining("断档");}
}
