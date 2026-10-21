package com.community.residence.common.workorder.service;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.auth.entity.SysUser;
import com.community.residence.common.auth.mapper.SysUserMapper;
import com.community.residence.common.workorder.entity.StaffCommunity;
import com.community.residence.common.workorder.mapper.StaffCommunityMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class StaffCommunityAccessServiceTest {
 @Mock SysUserMapper users; @Mock StaffCommunityMapper bindings; @InjectMocks StaffCommunityAccessService service;
 SysUser staff;
 @BeforeEach void init() {
  TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), StaffCommunity.class);
  staff=new SysUser(); staff.setId(7L); staff.setRole("STAFF"); staff.setStatus("ACTIVE");
 }
 @Test void bindingIsCheckedUnderStaffLock() {
  when(users.selectByIdForUpdate(7L)).thenReturn(staff); when(bindings.selectList(any())).thenReturn(List.of(new StaffCommunity()));
  assertThat(service.requireBoundActiveForUpdate(7L,1L)).isSameAs(staff);
  var order=inOrder(users,bindings); order.verify(users).selectByIdForUpdate(7L); order.verify(bindings).selectList(any());
 }
 @Test void unboundStaffRejected() { when(users.selectByIdForUpdate(7L)).thenReturn(staff); when(bindings.selectList(any())).thenReturn(List.of());
  assertThatThrownBy(()->service.requireBoundActiveForUpdate(7L,1L)).hasMessageContaining("未绑定目标社区"); }
 @Test void frozenStaffRejected() { staff.setStatus("FROZEN"); when(users.selectByIdForUpdate(7L)).thenReturn(staff);
  assertThatThrownBy(()->service.requireBoundActiveForUpdate(7L,1L)).hasMessageContaining("冻结"); verifyNoInteractions(bindings); }
 @Test void adminCannotBeTreatedAsStaff() { staff.setRole("ADMIN"); when(users.selectByIdForUpdate(7L)).thenReturn(staff);
  assertThatThrownBy(()->service.requireBoundActiveForUpdate(7L,1L)).hasMessageContaining("不存在"); verifyNoInteractions(bindings); }
}
