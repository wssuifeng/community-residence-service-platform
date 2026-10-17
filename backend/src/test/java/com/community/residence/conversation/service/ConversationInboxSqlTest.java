package com.community.residence.conversation.service;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.community.residence.conversation.mapper.ConversationInboxMapper;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ConversationInboxSqlTest {
 @Test void renderedUnionHasIdentityAndAnonymousBoundariesAndStableOrder() throws Exception {
  var c=new MybatisConfiguration();c.addMapper(ConversationInboxMapper.class);
  var q=new ConversationInboxService.Scope(8L,"ADMIN","USER",Set.of(2L),"FEEDBACK","阳光",true);
  var sql=c.getMappedStatement(ConversationInboxMapper.class.getName()+".page").getBoundSql(Map.of("q",q,"size",30,"offset",0));
  String text=sql.getSql();CCJSqlParserUtil.parse(text);
  assertThat(text).contains("p.owner_type=", "p.user_id=", "f.community_id IN", "f.is_anonymous=0", "rc.owner_type=", "last_read_message_id", "inbox.unread_count", "ORDER BY activity_at DESC,source DESC,id DESC");
  assertThat(text).doesNotContain("阳光", "resident_id=8", "last_message_at DESC");
 }
 @Test void staffAndUnboundAdminCannotObtainFeedbackBranch(){
  for(var role:List.of("STAFF","ADMIN")){
   var c=new MybatisConfiguration();c.addMapper(ConversationInboxMapper.class);
   var q=new ConversationInboxService.Scope(8L,role,"USER",Set.of(),null,null,false);
   var sql=c.getMappedStatement(ConversationInboxMapper.class.getName()+".count").getBoundSql(Map.of("q",q)).getSql();
   assertThat(sql).contains("1=0");
  }
 }
 @Test void feedbackCursorUsesAtomicGreatestAndCompositeIdentity(){
  var c=new MybatisConfiguration();c.addMapper(ConversationInboxMapper.class);
  var sql=c.getMappedStatement(ConversationInboxMapper.class.getName()+".advanceFeedbackRead").getBoundSql(Map.of("id",1L,"ownerType","USER","userId",8L,"cursor",4L)).getSql();
  assertThat(sql).contains("GREATEST(last_read_message_id,", "feedback_id,owner_type,user_id");
 }
}
