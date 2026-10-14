package com.community.residence.community.service;
import com.community.residence.community.mapper.StructureBusinessCodeMapper;
import com.community.residence.community.entity.House;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class StructureBusinessCodeServiceTest {
 @Mock StructureBusinessCodeMapper mapper; @InjectMocks StructureBusinessCodeService service;
 @Test void threeSegmentsNeverGuessUnit(){assertThatThrownBy(()->service.resolve("5-1-101")).isInstanceOf(BusinessException.class).hasMessageContaining("四段");verifyNoInteractions(mapper);}
 @Test void emptySegmentIsRejected(){assertThatThrownBy(()->service.resolve("5-1--101")).isInstanceOf(BusinessException.class);verifyNoInteractions(mapper);}
 @Test void exactPartsKeepLeadingZerosAndCheckScope(){House h=new House();h.setId(100L);h.setCommunityId(5L);when(mapper.resolve("05","01","001","0101")).thenReturn(h);try(var scope=mockStatic(SecurityUtils.class)){assertThat(service.resolve("05-01-001-0101")).isSameAs(h);scope.verify(()->SecurityUtils.checkCommunityAccess(5L));}}
 @Test void unknownCodeIsBusinessFailure(){when(mapper.resolve("5","1","1","101")).thenReturn(null);assertThatThrownBy(()->service.resolve("5-1-1-101")).isInstanceOf(BusinessException.class).hasMessageContaining("不存在");}
 @Test void namesProduceLocalNumbersNotGlobalIds(){assertThat(StructureBusinessCodeService.choose(null,"3号楼",List.of("1","2"))).isEqualTo("3");assertThat(StructureBusinessCodeService.choose(null,"自定义楼",List.of("1","3"))).isEqualTo("4");}
 @Test void explicitDuplicateRejected(){assertThatThrownBy(()->StructureBusinessCodeService.choose("01",null,List.of("01"))).isInstanceOf(BusinessException.class);}
 @Test void businessSegmentsCannotContainSeparator(){assertThatThrownBy(()->StructureBusinessCodeService.choose("A-B",null,List.of())).isInstanceOf(BusinessException.class);}
 @Test void legacyDoorHyphenIsEscapedWithoutLosingPrecision(){assertThat(StructureBusinessCodeService.join("5","1","1","A-101")).isEqualTo("5-1-1-A%2D101");House h=new House();h.setCommunityId(5L);when(mapper.resolve("5","1","1","A-101")).thenReturn(h);try(var scope=mockStatic(SecurityUtils.class)){assertThat(service.resolve("5-1-1-A%2D101")).isSameAs(h);}}
}
