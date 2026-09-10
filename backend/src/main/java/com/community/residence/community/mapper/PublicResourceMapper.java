package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.PublicResource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 公共资源数据访问（软删除） */
@Mapper
public interface PublicResourceMapper extends BaseMapper<PublicResource> {

    @Update("UPDATE public_resource SET is_deleted = 1, deleted_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /* 物理删除（社区级联删除 R1/R6 v1.1 专用：绕过 @TableLogic，消除 community FK 引用） */
    @Delete("DELETE FROM public_resource WHERE community_id = #{communityId}")
    int physicalDeleteByCommunityId(@Param("communityId") Long communityId);
}
