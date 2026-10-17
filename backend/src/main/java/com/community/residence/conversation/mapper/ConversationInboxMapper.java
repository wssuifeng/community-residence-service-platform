package com.community.residence.conversation.mapper;
import com.community.residence.conversation.service.ConversationInboxService.Scope;
import com.community.residence.conversation.vo.ConversationInboxVO;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 显式授权后聚合；外层派生表不依赖隐式数据范围注入。 */
@Mapper
public interface ConversationInboxMapper {
 String ROWS = """
 SELECT c.id, 'CONVERSATION' source, c.type, c.title, c.status, c.community_id,
 cm.name community_name, c.related_id,
 CASE WHEN h.id IS NOT NULL THEN CONCAT_WS('-',cm.business_code,b.business_code,u.business_code,h.house_number) ELSE NULL END house_code,
 NULL resident_label,c.last_message,COALESCE(c.last_message_at,c.created_at) activity_at,
 (SELECT COUNT(*) FROM conversation_message m WHERE m.conversation_id=c.id
 AND m.id &gt; p.last_read_message_id AND NOT(m.sender_id=#{q.userId}
 AND (CASE WHEN m.sender_role='RESIDENT' THEN 'RESIDENT' ELSE 'USER' END)=#{q.ownerType})) unread_count
 FROM conversation c JOIN conversation_participant p ON p.conversation_id=c.id
 AND p.owner_type=#{q.ownerType} AND p.user_id=#{q.userId}
 LEFT JOIN community cm ON cm.id=c.community_id
 LEFT JOIN lease_record l ON c.type='LEASE_GROUP' AND l.id=c.related_id
 LEFT JOIN viewing_appointment v ON c.type='VIEWING_GROUP' AND v.id=c.related_id
 LEFT JOIN housing hs ON hs.id=v.housing_id
 LEFT JOIN house h ON h.id=COALESCE(l.house_id,hs.house_id)
 LEFT JOIN unit u ON u.id=h.unit_id LEFT JOIN building b ON b.id=u.building_id
 UNION ALL
 SELECT f.id,'FEEDBACK' source,'FEEDBACK' type,f.title,f.status,f.community_id,
 cm.name community_name,f.id related_id,NULL house_code,
 CASE WHEN f.is_anonymous=0 THEN r.real_name ELSE '匿名居民' END resident_label,
 COALESCE((SELECT m.content FROM feedback_message m WHERE m.feedback_id=f.id ORDER BY m.id DESC LIMIT 1),f.content) last_message,
 COALESCE((SELECT m.created_at FROM feedback_message m WHERE m.feedback_id=f.id ORDER BY m.id DESC LIMIT 1),f.created_at) activity_at,
 (CASE WHEN #{q.ownerType}='USER' AND COALESCE(rc.initial_read,0)=0 THEN 1 ELSE 0 END)
 + (SELECT COUNT(*) FROM feedback_message m WHERE m.feedback_id=f.id AND m.id &gt; COALESCE(rc.last_read_message_id,0)
 AND NOT(m.sender_id=#{q.userId} AND (CASE WHEN m.sender_type='RESIDENT' THEN 'RESIDENT' ELSE 'USER' END)=#{q.ownerType})) unread_count
 FROM feedback f LEFT JOIN community cm ON cm.id=f.community_id
 LEFT JOIN resident r ON r.id=f.resident_id AND f.is_anonymous=0
 LEFT JOIN feedback_read_cursor rc ON rc.feedback_id=f.id AND rc.owner_type=#{q.ownerType} AND rc.user_id=#{q.userId}
 WHERE
 <choose>
 <when test="q.role == 'RESIDENT'">f.resident_id=#{q.userId}</when>
 <when test="q.role == 'SUPER_ADMIN'">1=1</when>
 <when test="q.role == 'ADMIN' and !q.communityIds.isEmpty()">f.community_id IN
 <foreach collection="q.communityIds" item="cid" open="(" close=")" separator=",">#{cid}</foreach></when>
 <otherwise>1=0</otherwise>
 </choose>
 """;
 String FILTER = """
 <where>
 <if test="q.type != null">inbox.type=#{q.type}</if>
 <if test="q.unreadOnly">AND inbox.unread_count &gt; 0</if>
 <if test="q.keyword != null">AND (INSTR(inbox.title,#{q.keyword}) &gt; 0 OR INSTR(inbox.community_name,#{q.keyword}) &gt; 0
 OR INSTR(inbox.house_code,#{q.keyword}) &gt; 0 OR INSTR(inbox.resident_label,#{q.keyword}) &gt; 0)</if>
 </where>
 """;
 @Select("<script>SELECT * FROM ("+ROWS+") inbox "+FILTER+" ORDER BY activity_at DESC,source DESC,id DESC LIMIT #{size} OFFSET #{offset}</script>")
 List<ConversationInboxVO> page(@Param("q") Scope scope,@Param("offset") long offset,@Param("size") int size);
 @Select("<script>SELECT COUNT(*) FROM ("+ROWS+") inbox "+FILTER+"</script>")
 long count(@Param("q") Scope scope);
 /* 原子前移，0仅确认原始反馈；同号不同身份空间互不覆盖。 */
 @Insert("""
 INSERT INTO feedback_read_cursor(feedback_id,owner_type,user_id,last_read_message_id,initial_read)
 VALUES(#{id},#{ownerType},#{userId},#{cursor},1)
 ON DUPLICATE KEY UPDATE last_read_message_id=GREATEST(last_read_message_id,#{cursor}),initial_read=1
 """)
 int advanceFeedbackRead(@Param("id") Long id,@Param("ownerType") String ownerType,@Param("userId") Long userId,@Param("cursor") Long cursor);

 @Select("""
 <script><choose><when test="source == 'CONVERSATION'">
 SELECT last_read_message_id FROM conversation_participant WHERE conversation_id=#{id} AND owner_type=#{ownerType} AND user_id=#{userId}
 </when><otherwise>
 SELECT COALESCE(MAX(last_read_message_id),0) FROM feedback_read_cursor WHERE feedback_id=#{id} AND owner_type=#{ownerType} AND user_id=#{userId}
 </otherwise></choose></script>
 """)
 Long readingCursor(@Param("source") String source,@Param("id") Long id,@Param("ownerType") String ownerType,@Param("userId") Long userId);
 String MESSAGE_SOURCE = """
 <choose><when test="source == 'CONVERSATION'">conversation_message WHERE conversation_id=#{id}</when><otherwise>feedback_message WHERE feedback_id=#{id}</otherwise></choose>
 """;
 @Select("""
 <script>SELECT id FROM
 """+MESSAGE_SOURCE+"""
 AND id &gt; #{cursor} AND NOT(sender_id=#{userId} AND
 <choose><when test="source == 'CONVERSATION'">(CASE WHEN sender_role='RESIDENT' THEN 'RESIDENT' ELSE 'USER' END)</when><otherwise>(CASE WHEN sender_type='RESIDENT' THEN 'RESIDENT' ELSE 'USER' END)</otherwise></choose>
 =#{ownerType}) ORDER BY id ASC LIMIT 1</script>
 """)
 Long firstUnread(@Param("source") String source,@Param("id") Long id,@Param("cursor") Long cursor,@Param("ownerType") String ownerType,@Param("userId") Long userId);
 @Select("<script>SELECT EXISTS(SELECT 1 FROM "+MESSAGE_SOURCE+" AND id <choose><when test='older'>&lt;</when><otherwise>&gt;</otherwise></choose> #{bound} LIMIT 1)</script>")
 boolean outsideWindow(@Param("source") String source,@Param("id") Long id,@Param("bound") Long bound,@Param("older") boolean older);
}
