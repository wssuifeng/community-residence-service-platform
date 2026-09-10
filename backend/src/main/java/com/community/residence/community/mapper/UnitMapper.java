package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.Unit;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 单元数据访问（软删除） */
@Mapper
public interface UnitMapper extends BaseMapper<Unit> {

    @Update("UPDATE unit SET is_deleted = 1, deleted_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /* 物理删除（社区级联删除 R1/R6 v1.1 专用：绕过 @TableLogic，消除 building/community FK 引用） */
    @Delete("DELETE FROM unit WHERE community_id = #{communityId}")
    int physicalDeleteByCommunityId(@Param("communityId") Long communityId);
}
