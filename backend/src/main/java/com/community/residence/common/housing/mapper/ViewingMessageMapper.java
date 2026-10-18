package com.community.residence.common.housing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.housing.entity.ViewingMessage;
import org.apache.ibatis.annotations.Mapper;

/** 看房会话消息数据访问 */
@Mapper
public interface ViewingMessageMapper extends BaseMapper<ViewingMessage> {
}
