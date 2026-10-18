package com.community.residence.common.resident.service;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.community.residence.common.resident.entity.ResidenceRelation;
import com.community.residence.common.resident.mapper.ResidenceRelationMapper;
import com.community.residence.common.web.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class ResidenceDependencyServiceTest {
 @Mock ResidenceRelationMapper relationMapper; @InjectMocks ResidenceDependencyService service;
 @BeforeAll static void metadata(){TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"test"),ResidenceRelation.class);}
 ResidenceRelation member(long id,int day){var r=new ResidenceRelation();r.setId(id);r.setAnchorRelationId(10L);r.setMoveInDate(LocalDate.of(2026,10,day));return r;}
 @Test void primaryEndRequiresExplicitWholeHouseConfirmation(){when(relationMapper.selectList(any())).thenReturn(List.of(member(11,1)));assertThatThrownBy(()->service.endMembers(10L,LocalDate.of(2026,10,14),false)).isInstanceOf(BusinessException.class).hasMessageContaining("确认");verify(relationMapper,never()).updateById(any(ResidenceRelation.class));}
 @Test void confirmedEndKeepsRowsAndClosesEveryDependency(){var a=member(11,1);var b=member(12,2);when(relationMapper.selectList(any())).thenReturn(List.of(a,b));var date=LocalDate.of(2026,10,14);assertThat(service.endMembers(10L,date,true)).hasSize(2);assertThat(a.getMoveOutDate()).isEqualTo(date);assertThat(b.getMoveOutDate()).isEqualTo(date);verify(relationMapper,times(2)).updateById(any(ResidenceRelation.class));verify(relationMapper,never()).deleteById(any(Long.class));}
 @Test void lateInvalidMemberCausesNoPartialWrites(){when(relationMapper.selectList(any())).thenReturn(List.of(member(11,1),member(12,14)));assertThatThrownBy(()->service.endMembers(10L,LocalDate.of(2026,10,10),true)).isInstanceOf(BusinessException.class);verify(relationMapper,never()).updateById(any(ResidenceRelation.class));}
 @Test void emptyDependenciesLeaveOtherHouseholdsUntouched(){when(relationMapper.selectList(any())).thenReturn(List.of());assertThat(service.endMembers(10L,LocalDate.of(2026,10,14),false)).isEmpty();verify(relationMapper,never()).updateById(any(ResidenceRelation.class));}
}
