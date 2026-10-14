package com.community.residence.community.service;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.auth.entity.SysAdminCommunity;
import com.community.residence.auth.mapper.*;
import com.community.residence.auth.service.*;
import com.community.residence.community.mapper.CommunityMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class CommunityResponsibleBindingTest {
 @Mock SysUserMapper sysUserMapper; @Mock CommunityMapper communityMapper;
 @Mock SysAdminCommunityMapper bindingMapper; @Mock AdminCommunityCacheService adminCommunityCacheService;
 @Mock TokenRevocationService tokenRevocationService;
 @InjectMocks CommunityResponsibleService service;
 @BeforeAll static void metadata(){TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"test"),SysAdminCommunity.class);}
 private SysAdminCommunity binding(int manual){var b=new SysAdminCommunity();b.setId(4L);b.setAdminId(7L);b.setCommunityId(2L);b.setManualGranted(manual);b.setResponsibleGranted(1);return b;}
 @Test void newResponsibleGetsOnlyCurrentCommunity(){when(bindingMapper.selectList(any())).thenReturn(List.of());service.synchronizeBinding(2L,7L);var c=ArgumentCaptor.forClass(SysAdminCommunity.class);verify(bindingMapper).insert(c.capture());assertThat(c.getValue().getCommunityId()).isEqualTo(2L);assertThat(c.getValue().getManualGranted()).isZero();assertThat(c.getValue().getResponsibleGranted()).isEqualTo(1);verify(tokenRevocationService).revokeAdminUser(7L);verify(adminCommunityCacheService).evict(7L);}
 @Test void replacementRetainsManualGrant(){var b=binding(1);when(bindingMapper.selectList(any())).thenReturn(List.of(b));service.synchronizeBinding(2L,null);assertThat(b.getResponsibleGranted()).isZero();verify(bindingMapper).updateById(b);verify(bindingMapper,never()).deleteById(any(Long.class));verifyNoInteractions(tokenRevocationService);}
 @Test void clearingRemovesOnlyAutomaticBinding(){var b=binding(0);when(bindingMapper.selectList(any())).thenReturn(List.of(b));service.synchronizeBinding(2L,null);verify(bindingMapper).deleteById(4L);verify(tokenRevocationService).revokeAdminUser(7L);}
 @Test void unchangedSelectionIsIdempotent(){when(bindingMapper.selectList(any())).thenReturn(List.of(binding(0)));service.synchronizeBinding(2L,7L);verify(bindingMapper,never()).insert(any(SysAdminCommunity.class));verify(bindingMapper,never()).updateById(any(SysAdminCommunity.class));verifyNoInteractions(tokenRevocationService,adminCommunityCacheService);}
}
