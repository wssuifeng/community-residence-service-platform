package com.community.residence.community.service;
import com.community.residence.auth.entity.SysUser;
import com.community.residence.auth.mapper.SysUserMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import com.community.residence.common.exception.ForbiddenException;
import com.community.residence.community.entity.Community;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class CommunityResponsibleServiceTest {
 @Mock com.community.residence.community.mapper.CommunityMapper communityMapper;
 @Mock SysUserMapper mapper; @InjectMocks CommunityResponsibleService service;
 private SysUser user(String role){SysUser u=new SysUser();u.setId(2L);u.setRole(role);u.setRealName("原管理员");u.setPhone("13800138000");return u;}
 @Test void administratorAccepted(){when(mapper.selectByIdForUpdate(2L)).thenReturn(user("ADMIN"));service.validate(2L);}
 @Test void nonAdministratorRejected(){for(String role:List.of("SUPER_ADMIN","STAFF","RESIDENT")){when(mapper.selectByIdForUpdate(2L)).thenReturn(user(role));assertThatThrownBy(()->service.validate(2L)).isInstanceOf(BusinessException.class);}}
 @Test void missingAccountRejected(){assertThatThrownBy(()->service.validate(2L)).isInstanceOf(BusinessException.class);}
 @Test void optionalEmptyBindingHasNoUserQuery(){Community c=new Community();c.setContactPerson("过期自由文本");service.validate(null);service.fillContacts(List.of(c));assertThat(c.getContactPerson()).isNull();verifyNoInteractions(mapper);}
 @Test void accountChangesSynchronizeWithoutCommunityWrite(){Community c=new Community();c.setResponsibleAdminId(2L);SysUser u=user("ADMIN");when(mapper.selectBatchIds(anyCollection())).thenReturn(List.of(u));service.fillContacts(List.of(c));assertThat(c.getContactPerson()).isEqualTo("原管理员");u.setRealName("更新姓名");u.setPhone("13900139000");service.fillContacts(List.of(c));assertThat(c.getContactPerson()).isEqualTo("更新姓名");assertThat(c.getContactPhone()).isEqualTo("13900139000");}
 @Test void candidateQueryRejectsUnprivilegedUser(){try(MockedStatic<SecurityUtils> auth=mockStatic(SecurityUtils.class)){assertThatThrownBy(()->service.candidates(1L,1,20,null)).isInstanceOf(ForbiddenException.class);verifyNoInteractions(mapper);}}
 @Test void administratorMustSpecifyManagedCommunity(){try(MockedStatic<SecurityUtils> auth=mockStatic(SecurityUtils.class)){auth.when(()->SecurityUtils.hasRole("ADMIN")).thenReturn(true);assertThatThrownBy(()->service.candidates(null,1,20,null)).isInstanceOf(ForbiddenException.class);}}
 @Test void boundAdministratorCannotBecomeStaff(){when(communityMapper.selectResponsibleReferenceForUpdate(2L)).thenReturn(1L);assertThatThrownBy(()->service.validateRoleChange(2L,"STAFF")).isInstanceOf(BusinessException.class);}
 @Test void unboundAccountCanChangeRole(){when(communityMapper.selectResponsibleReferenceForUpdate(2L)).thenReturn(null);service.validateRoleChange(2L,"STAFF");verify(communityMapper).selectResponsibleReferenceForUpdate(2L);}
}
