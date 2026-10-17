package com.community.residence.conversation.service;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.community.residence.interceptor.DataScopeInterceptor;
import com.community.residence.common.context.*;
import com.community.residence.conversation.mapper.ConversationInboxMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** 仅显式启用时在独立合成库运行，不连接业务库或渠道。 */
@EnabledIfEnvironmentVariable(named="CL_INBOX_MYSQL_TEST",matches="true")
class ConversationInboxDatabaseTest {
 @Test void realUnionPermissionsSearchUnreadAndAtomicCursor() throws Exception {
  String url="jdbc:mysql://127.0.0.1:3306/",db="cl_conversation_inbox_20261017";
  String user=System.getenv("DB_USERNAME"),password=System.getenv("DB_PASSWORD");
  try(var conn=DriverManager.getConnection(url,user,password);var statement=conn.createStatement()){
   statement.execute("CREATE DATABASE "+db+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
   try{
    statement.execute("USE "+db);
    for(String sql:List.of(
     "CREATE TABLE community(id BIGINT PRIMARY KEY,name VARCHAR(100),business_code VARCHAR(32))",
     "CREATE TABLE resident(id BIGINT PRIMARY KEY,real_name VARCHAR(100))",
     "CREATE TABLE conversation(id BIGINT PRIMARY KEY,type VARCHAR(30),title VARCHAR(100),status VARCHAR(20),community_id BIGINT,related_id BIGINT,last_message VARCHAR(500),last_message_at DATETIME,created_at DATETIME)",
     "CREATE TABLE conversation_participant(conversation_id BIGINT,owner_type VARCHAR(16),user_id BIGINT,last_read_message_id BIGINT)",
     "CREATE TABLE conversation_message(id BIGINT PRIMARY KEY,conversation_id BIGINT,sender_id BIGINT,sender_role VARCHAR(20))",
     "CREATE TABLE lease_record(id BIGINT PRIMARY KEY,house_id BIGINT)","CREATE TABLE viewing_appointment(id BIGINT PRIMARY KEY,housing_id BIGINT)",
     "CREATE TABLE housing(id BIGINT PRIMARY KEY,house_id BIGINT)","CREATE TABLE house(id BIGINT PRIMARY KEY,unit_id BIGINT,house_number VARCHAR(20))",
     "CREATE TABLE unit(id BIGINT PRIMARY KEY,building_id BIGINT,business_code VARCHAR(32))","CREATE TABLE building(id BIGINT PRIMARY KEY,business_code VARCHAR(32))",
     "CREATE TABLE feedback(id BIGINT UNSIGNED PRIMARY KEY,community_id BIGINT,resident_id BIGINT,is_anonymous TINYINT,title VARCHAR(100),status VARCHAR(20),content VARCHAR(500),created_at DATETIME)",
     "CREATE TABLE feedback_message(id BIGINT PRIMARY KEY,feedback_id BIGINT,sender_id BIGINT,sender_type VARCHAR(20),content VARCHAR(500),created_at DATETIME)"
    ))statement.execute(sql);
    statement.execute(java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V43__feedback_read_cursor.sql")));
    statement.execute("INSERT INTO community VALUES(2,'合成社区','C2'),(99,'不可见社区','C99')");
    statement.execute("INSERT INTO resident VALUES(8,'不可泄露实名'),(9,'实名居民')");
    statement.execute("INSERT INTO conversation VALUES(10,'WORK_ORDER_GROUP','工单甲','ACTIVE',2,11,'旧消息','2026-10-10 10:00:00','2026-10-09 10:00:00'),(20,'DIRECT','无权群','ACTIVE',99,NULL,NULL,NULL,'2026-10-17 10:00:00')");
    statement.execute("INSERT INTO conversation_participant VALUES(10,'USER',8,0),(20,'RESIDENT',8,0)");
    statement.execute("INSERT INTO conversation_message VALUES(1,10,8,'ADMIN'),(2,10,8,'RESIDENT')");
    statement.execute("INSERT INTO feedback VALUES(1,2,8,1,'匿名建议','PENDING','原始内容','2026-10-16 10:00:00'),(2,99,9,0,'隔离反馈','PENDING','隐藏','2026-10-17 10:00:00')");
    var c=new MybatisConfiguration();c.setMapUnderscoreToCamelCase(true);
    c.setEnvironment(new Environment("test",new JdbcTransactionFactory(),new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url+db,user,password)));
    var interceptor=new MybatisPlusInterceptor();interceptor.addInnerInterceptor(new DataScopeInterceptor());c.addInterceptor(interceptor);c.addMapper(ConversationInboxMapper.class);
    try(var session=new SqlSessionFactoryBuilder().build(c).openSession(true)){
     var mapper=session.getMapper(ConversationInboxMapper.class);
     SecurityUtils.setAuthentication(new UserContext(8L,"synthetic","ADMIN",Set.of(2L)));
     var admin=new ConversationInboxService.Scope(8L,"ADMIN","USER",Set.of(2L),null,null,false);
     var rows=mapper.page(admin,0,30);assertThat(rows).hasSize(2);assertThat(mapper.count(admin)).isEqualTo(2);
     assertThat(rows.get(0).getSource()).isEqualTo("FEEDBACK");assertThat(rows.get(0).getResidentLabel()).isEqualTo("匿名居民");
     assertThat(rows.get(1).getUnreadCount()).isEqualTo(1);assertThat(rows.get(1).getHouseCode()).isNull();
     var search=new ConversationInboxService.Scope(8L,"ADMIN","USER",Set.of(2L),null,"不可泄露实名",false);assertThat(mapper.count(search)).isZero();
     mapper.advanceFeedbackRead(1L,"USER",8L,0L);var unread=new ConversationInboxService.Scope(8L,"ADMIN","USER",Set.of(2L),null,null,true);assertThat(mapper.page(unread,0,30)).hasSize(1);
     mapper.advanceFeedbackRead(1L,"USER",8L,8L);mapper.advanceFeedbackRead(1L,"USER",8L,3L);
     try(var result=statement.executeQuery("SELECT last_read_message_id FROM feedback_read_cursor")){assertThat(result.next()).isTrue();assertThat(result.getLong(1)).isEqualTo(8L);}
     SecurityUtils.setAuthentication(new UserContext(8L,"synthetic","STAFF",Set.of()));
     var staff=new ConversationInboxService.Scope(8L,"STAFF","USER",Set.of(),null,null,false);assertThat(mapper.page(staff,0,30)).hasSize(1);
     SecurityUtils.setAuthentication(new UserContext(8L,"synthetic","RESIDENT",Set.of()));
     var resident=new ConversationInboxService.Scope(8L,"RESIDENT","RESIDENT",Set.of(),null,null,false);assertThat(mapper.page(resident,0,30)).extracting("source").containsExactly("CONVERSATION","FEEDBACK");
    }
   }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();statement.execute("DROP DATABASE "+db);}
  }
 }
}
