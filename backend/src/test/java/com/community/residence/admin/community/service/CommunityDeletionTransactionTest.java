package com.community.residence.admin.community.service;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.community.residence.common.community.entity.Community;
import com.community.residence.common.community.mapper.*;
import com.community.residence.common.security.context.*;
import java.sql.Connection;
import javax.sql.DataSource;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
/** 真实Spring事务代理＋替身连接；域清理失败时只能回滚整个小区事务。 */
class CommunityDeletionTransactionTest {
 @AfterEach void clear(){SecurityUtils.clear();}
 @Test void midwayFailureRollsBackAndNeverDeletesParentOrCommits() throws Exception {
  for(var file:java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/com/community/residence")).filter(p->p.toString().contains("entity")&&p.toString().endsWith(".java")).toList()){
   String fqn="com.community.residence."+java.nio.file.Path.of("src/main/java/com/community/residence").relativize(file).toString().replace('\\','.').replace('/','.').replace(".java","");
   var type=Class.forName(fqn);if(type.isAnnotationPresent(com.baomidou.mybatisplus.annotation.TableName.class))com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),""),type);
  }
  var objects=new HashMap<Class<?>,Object>();
  var mapper=mock(CommunityMapper.class);objects.put(CommunityMapper.class,mapper);
  var community=new Community();community.setId(9L);community.setName("事务小区");when(mapper.selectById(9L)).thenReturn(community);
  var houses=mock(HouseMapper.class);objects.put(HouseMapper.class,houses);when(houses.physicalDeleteByCommunityId(9L)).thenThrow(new IllegalStateException("synthetic failure"));
  var target=DeletionTestAssembly.get(CommunityDeletionService.class,objects);
  var source=mock(DataSource.class);var connection=mock(Connection.class);when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
  var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);
  factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
  var service=(CommunityDeletionService)factory.getProxy();
  SecurityUtils.setAuthentication(new UserContext(1L,"superadmin","SUPER_ADMIN",Set.of()));
  assertThatThrownBy(()->service.delete(9L)).isInstanceOf(IllegalStateException.class).hasMessage("synthetic failure");
  verify(connection).rollback();verify(connection,never()).commit();verify(mapper,never()).deleteById(9L);
  assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
 }
}
