package com.community.residence.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.community.entity.House;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 房屋数据访问（软删除） */
@Mapper
public interface HouseMapper extends BaseMapper<House> {

    /* 写入入住申请/租约前锁定房屋行，串行化同房屋的竞争性业务操作。 */
    @Select("SELECT * FROM house WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    House selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE house SET is_deleted = 1, deleted_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /* 物理删除（社区级联删除 R1/R6 v1.1 专用：绕过 @TableLogic，消除 unit/community FK 引用） */
    @Delete("DELETE FROM house WHERE community_id = #{communityId}")
    int physicalDeleteByCommunityId(@Param("communityId") Long communityId);

    /* 物理删除批量（楼栋级联删除 D-端点1：绕过 @TableLogic，消除 FK 引用） */
    @Delete("<script>DELETE FROM house WHERE id IN <foreach item='item' collection='ids' open='(' separator=',' close=')'>#{item}</foreach></script>")
    int physicalDeleteByIds(@Param("ids") java.util.List<Long> ids);
    /* 锁定读最高房屋楼层，避免父记录加锁前的快照影响降层判定。 */
    @Select("SELECT h.floor FROM house h JOIN unit u ON u.id=h.unit_id "
            + "WHERE u.building_id=#{buildingId} AND h.is_deleted=0 ORDER BY h.floor DESC, h.id LIMIT 1 FOR UPDATE")
    Integer selectHighestFloorByBuilding(@Param("buildingId") Long buildingId);
}
