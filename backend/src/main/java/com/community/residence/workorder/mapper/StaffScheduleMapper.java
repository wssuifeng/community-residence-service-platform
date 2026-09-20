package com.community.residence.workorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.workorder.entity.StaffSchedule;
import org.apache.ibatis.annotations.Mapper;

/* 服务人员排班数据访问（有 community_id 列，ADMIN 数据级权限由拦截器注入；
   写操作的社区归属由业务层显式校验） */
@Mapper
public interface StaffScheduleMapper extends BaseMapper<StaffSchedule> {
}
