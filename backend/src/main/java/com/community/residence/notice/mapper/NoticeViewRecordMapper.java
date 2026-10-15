package com.community.residence.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.notice.entity.NoticeViewRecord;
import org.apache.ibatis.annotations.Mapper;

/** 公告查看记录数据访问 */
@Mapper
public interface NoticeViewRecordMapper extends BaseMapper<NoticeViewRecord> {
 @org.apache.ibatis.annotations.Insert("INSERT IGNORE INTO notice_version_view(notice_id,user_id,content_version) VALUES(#{noticeId},#{userId},#{version})")
 int recordVersion(@org.apache.ibatis.annotations.Param("noticeId") Long noticeId,@org.apache.ibatis.annotations.Param("userId") Long userId,@org.apache.ibatis.annotations.Param("version") int version);
 @org.apache.ibatis.annotations.Update("UPDATE notice_view_record SET content_version=#{version},view_time=NOW() WHERE notice_id=#{noticeId} AND user_id=#{userId} AND content_version<#{version}")
 int updateLatestVersion(@org.apache.ibatis.annotations.Param("noticeId") Long noticeId,@org.apache.ibatis.annotations.Param("userId") Long userId,@org.apache.ibatis.annotations.Param("version") int version);

}
