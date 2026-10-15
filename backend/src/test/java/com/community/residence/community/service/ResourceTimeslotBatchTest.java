package com.community.residence.community.service;

import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.community.dto.BatchCreateTimeslotDTO;
import com.community.residence.community.entity.*;
import com.community.residence.community.mapper.ResourceTimeslotMapper;
import com.community.residence.reservation.mapper.ResourceReservationMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ResourceTimeslotBatchTest {
 @Mock ResourceTimeslotMapper timeslotMapper;
 @Mock PublicResourceService publicResourceService;
 @Mock ResourceReservationMapper reservationMapper;
 @InjectMocks ResourceTimeslotService service;
 BatchCreateTimeslotDTO dto() { var d=new BatchCreateTimeslotDTO();d.setDaysOfWeek(List.of(1,2,3,4,5));d.setStartTime(LocalTime.of(9,0));d.setEndTime(LocalTime.of(12,0));d.setIsAvailable(1);return d; }
 PublicResource resource() { var r=new PublicResource();r.setId(8L);r.setCommunityId(2L);r.setSlotUnit(30);return r; }
 ResourceTimeslot slot(int day,int start,int end) {var t=new ResourceTimeslot();t.setId(20L);t.setDayOfWeek(day);t.setStartTime(LocalTime.of(start,0));t.setEndTime(LocalTime.of(end,0));t.setIsAvailable(0);return t;}
 @Test void workdaysCreatedInOneBatch() {try(var security=mockStatic(SecurityUtils.class)) {
  when(publicResourceService.requireResourceLocked(8L)).thenReturn(resource());when(timeslotMapper.selectList(any())).thenReturn(List.of());
  var result=service.createBatch(8L,dto());assertThat(result.createdDays()).containsExactly(1,2,3,4,5);verify(timeslotMapper,times(5)).insert(any(ResourceTimeslot.class));
 }}
 @Test void duplicateIncludingDisabledIsSkippedWithoutChangingState() {try(var security=mockStatic(SecurityUtils.class)) {
  when(publicResourceService.requireResourceLocked(8L)).thenReturn(resource());var old=slot(1,9,12);when(timeslotMapper.selectList(any())).thenReturn(List.of(old));
  var result=service.createBatch(8L,dto());assertThat(result.skippedDays()).containsExactly(1);assertThat(old.getIsAvailable()).isZero();verify(timeslotMapper,times(4)).insert(any(ResourceTimeslot.class));verify(timeslotMapper,never()).updateById(any(ResourceTimeslot.class));
 }}
 @Test void laterDayConflictDoesNotInsertEarlierDays() {try(var security=mockStatic(SecurityUtils.class)) {
  when(publicResourceService.requireResourceLocked(8L)).thenReturn(resource());when(timeslotMapper.selectList(any())).thenReturn(List.of(slot(5,10,13)));
  assertThatThrownBy(()->service.createBatch(8L,dto())).isInstanceOf(BusinessException.class).hasMessageContaining("周5");verify(timeslotMapper,never()).insert(any(ResourceTimeslot.class));
 }}
 @Test void misalignedTimeRejectsBeforeWrite() {try(var security=mockStatic(SecurityUtils.class)) {
  when(publicResourceService.requireResourceLocked(8L)).thenReturn(resource());var d=dto();d.setStartTime(LocalTime.of(9,10));
  assertThatThrownBy(()->service.createBatch(8L,d)).isInstanceOf(BusinessException.class);verify(timeslotMapper,never()).insert(any(ResourceTimeslot.class));
 }}
 @Test void outsideCommunityDeniedBeforeTimeslotRead() {try(var security=mockStatic(SecurityUtils.class)) {
  when(publicResourceService.requireResourceLocked(8L)).thenReturn(resource());security.when(()->SecurityUtils.checkCommunityAccess(2L)).thenThrow(new BusinessException(com.community.residence.common.constant.ErrorCode.FORBIDDEN));
  assertThatThrownBy(()->service.createBatch(8L,dto())).isInstanceOf(BusinessException.class);verifyNoInteractions(timeslotMapper);
 }}
 @Test void updateReadsCurrentRowAfterResourceLockAndDoesNotResurrectDeletedTemplate() {try(var security=mockStatic(SecurityUtils.class)) {
  var old=slot(1,9,12);old.setResourceId(8L);old.setCommunityId(2L);
  when(timeslotMapper.selectById(20L)).thenReturn(old);
  when(publicResourceService.requireResourceLocked(8L)).thenReturn(resource());
  when(timeslotMapper.selectOne(any())).thenReturn(null);
  var d=new com.community.residence.community.dto.CreateTimeSlotDTO();d.setDayOfWeek(1);d.setStartTime(LocalTime.of(9,0));d.setEndTime(LocalTime.of(12,0));
  assertThatThrownBy(()->service.update(20L,d)).isInstanceOf(com.community.residence.common.exception.ResourceNotFoundException.class);
  var order=inOrder(timeslotMapper,publicResourceService);order.verify(timeslotMapper).selectById(20L);order.verify(publicResourceService).requireResourceLocked(8L);order.verify(timeslotMapper).selectOne(any());
  verify(timeslotMapper,never()).updateById(any(ResourceTimeslot.class));
 }}
}
