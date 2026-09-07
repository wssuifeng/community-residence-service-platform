package com.community.residence.notice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.notice.entity.Notice;
import org.apache.ibatis.annotations.Mapper;

/** 公告数据访问（无 community_id 列，ADMIN 范围过滤由 Service 经 notice_target 处理） */
@Mapper
public interface NoticeMapper extends BaseMapper<Notice> {
}
