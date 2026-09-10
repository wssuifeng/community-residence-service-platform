package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.Building;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 楼栋数据访问（软删除，删除时间随软删除一并写入） */
@Mapper
public interface BuildingMapper extends BaseMapper<Building> {

    /* 软删除并记录删除时间（@TableLogic 的 deleteById 不写 deleted_at，故显式声明） */
    @Update("UPDATE building SET is_deleted = 1, deleted_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /* 物理删除（社区级联删除 R1/R6 v1.1 专用：绕过 @TableLogic，消除 community FK 引用） */
    @Delete("DELETE FROM building WHERE community_id = #{communityId}")
    int physicalDeleteByCommunityId(@Param("communityId") Long communityId);
}
