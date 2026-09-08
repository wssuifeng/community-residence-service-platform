package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.Building;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 楼栋数据访问（软删除，删除时间随软删除一并写入） */
@Mapper
public interface BuildingMapper extends BaseMapper<Building> {

    /* 软删除并记录删除时间（@TableLogic 的 deleteById 不写 deleted_at，故显式声明） */
    @Update("UPDATE building SET is_deleted = 1, deleted_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id);
}
