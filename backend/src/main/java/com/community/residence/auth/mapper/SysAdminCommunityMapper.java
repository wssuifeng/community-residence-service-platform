package com.community.residence.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.auth.entity.SysAdminCommunity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 管理员-社区绑定表访问 */
@Mapper
public interface SysAdminCommunityMapper extends BaseMapper<SysAdminCommunity> {

    /* 查管理员绑定的全部社区 ID（数据级权限过滤条件来源） */
    @Select("SELECT community_id FROM sys_admin_community WHERE admin_id = #{adminId}")
    List<Long> selectCommunityIdsByAdminId(@Param("adminId") Long adminId);
}
