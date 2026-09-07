package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.PublicResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 公共资源数据访问（软删除） */
@Mapper
public interface PublicResourceMapper extends BaseMapper<PublicResource> {

    @Update("UPDATE public_resource SET is_deleted = 1, deleted_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id);
}
