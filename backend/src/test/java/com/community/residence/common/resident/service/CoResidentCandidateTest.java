package com.community.residence.common.resident.service;
import com.community.residence.common.security.context.SecurityUtils;
import com.community.residence.common.web.exception.ForbiddenException;
import com.community.residence.common.community.entity.House;
import com.community.residence.common.community.mapper.HouseMapper;
import com.community.residence.common.resident.entity.Resident;
import com.community.residence.common.resident.mapper.ResidentMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class CoResidentCandidateTest {
 @Mock HouseMapper houseMapper;
 @Mock ResidentMapper residentMapper;
 @InjectMocks ResidentService service;
 @BeforeAll static void metadata(){var a=new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"");com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(a,Resident.class);}
 @BeforeEach void house(){var h=new House();h.setId(1L);h.setCommunityId(5L);h.setStatus("OWNER_OCCUPIED");when(houseMapper.selectById(1L)).thenReturn(h);}
 @Test void exactLookupOnlyReturnsSelectionFields(){var r=new Resident();r.setId(2L);r.setUsername("独立成员");r.setRealName("合成人员");r.setPhone("13900000011");when(residentMapper.selectList(any())).thenReturn(List.of(r));try(var s=mockStatic(SecurityUtils.class)){var v=service.coResidentCandidates(1L,"独立成员");assertThat(v).hasSize(1);assertThat(v.get(0).getUsername()).isEqualTo("独立成员");assertThat(v.get(0).getClass().getDeclaredFields()).extracting(java.lang.reflect.Field::getName).containsExactly("id","username","realName");}var c=ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);verify(residentMapper).selectList(c.capture());assertThat(c.getValue().getSqlSegment()).doesNotContain("LIKE").contains("username =","phone =","status =");}
 @Test void blankDoesNotEnumerateResidents(){try(var s=mockStatic(SecurityUtils.class)){assertThat(service.coResidentCandidates(1L," ")).isEmpty();}verifyNoInteractions(residentMapper);}
 @Test void outsideCommunityIsRejectedBeforeAccountQuery(){try(var s=mockStatic(SecurityUtils.class)){s.when(()->SecurityUtils.checkCommunityAccess(5L)).thenThrow(new ForbiddenException("拒绝"));assertThatThrownBy(()->service.coResidentCandidates(1L,"独立成员")).isInstanceOf(ForbiddenException.class);}verifyNoInteractions(residentMapper);}
}
