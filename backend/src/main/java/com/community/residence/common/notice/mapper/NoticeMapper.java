package com.community.residence.common.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.notice.entity.Notice;
import org.apache.ibatis.annotations.Mapper;

/** 公告数据访问（无 community_id 列，ADMIN 范围过滤由 Service 经 notice_target 处理） */
@Mapper
public interface NoticeMapper extends BaseMapper<Notice> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM notice WHERE id=#{id} FOR UPDATE")
    Notice lock(@org.apache.ibatis.annotations.Param("id") Long id);
    @org.apache.ibatis.annotations.Update("UPDATE notice SET status=#{notice.status},publish_time=#{notice.publishTime},start_time=#{notice.startTime} WHERE id=#{notice.id} AND status=#{expected}")
    int updateLifecycle(@org.apache.ibatis.annotations.Param("notice") Notice notice,@org.apache.ibatis.annotations.Param("expected") String expected);
    @org.apache.ibatis.annotations.Update("UPDATE notice SET view_count=COALESCE(view_count,0)+1 WHERE id=#{id}")
    int incrementView(@org.apache.ibatis.annotations.Param("id") Long id);

}
