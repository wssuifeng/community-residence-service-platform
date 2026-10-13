package com.community.residence.housing.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.housing.entity.Housing;
import org.apache.ibatis.annotations.Mapper;

/** 房源数据访问 */
@Mapper
public interface HousingMapper extends BaseMapper<Housing> {
    /* 外层仅挂牌主表，避免动态条件歧义；相关子查询取房屋权威户型。 */
    @org.apache.ibatis.annotations.Select("SELECT DISTINCT (SELECT hh.layout FROM house hh WHERE hh.id=housing.house_id AND hh.is_deleted=0) AS layout FROM housing ${ew.customSqlSegment}")
    java.util.List<String> selectHouseLayouts(@org.apache.ibatis.annotations.Param("ew")
            com.baomidou.mybatisplus.core.conditions.Wrapper<Housing> query);

    /* 源金额与管理修改共享挂牌锁；房屋锁必须先于此锁。 */
    @org.apache.ibatis.annotations.Select("SELECT id,community_id,house_id,title,description,monthly_rent,deposit,rent_type,layout,images,status,view_count,publish_time,created_at,updated_at FROM housing WHERE id=#{id} FOR UPDATE")
    Housing selectByIdForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM housing WHERE id=#{id} AND " + com.community.residence.housing.service.HousingOccupancyRules.PUBLIC)
    Long publicAvailabilityCount(@org.apache.ibatis.annotations.Param("id") Long id);

    /* 新预约与入住申请串行化同一真实房屋；不可只锁挂牌或日期槽。 */
    @org.apache.ibatis.annotations.Select("SELECT hh.id FROM house hh JOIN housing ON housing.house_id=hh.id WHERE housing.id=#{id} AND hh.is_deleted=0 FOR UPDATE")
    Long lockHouseForHousing(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM housing WHERE id=#{id} AND ("
        + "EXISTS (SELECT 1 FROM residence_application ra WHERE ra.housing_id=housing.id OR (ra.house_id=housing.house_id AND ra.relation_type='TENANT')) "
        + "OR EXISTS (SELECT 1 FROM lease_record lr WHERE lr.house_id=housing.house_id) "
        + "OR EXISTS (SELECT 1 FROM residence_relation rr WHERE rr.house_id=housing.house_id AND rr.relation_type='TENANT'))")
    Long rentalHistoryCount(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Select("<script>SELECT housing.id, CASE "
        + "WHEN housing.rent_type='SALE' THEN 'FREE' "
        + "WHEN EXISTS (SELECT 1 FROM lease_record l WHERE l.house_id=housing.house_id AND l.status='ACTIVE') "
        + "OR EXISTS (SELECT 1 FROM residence_relation r WHERE r.house_id=housing.house_id AND r.move_out_date IS NULL) "
        + "OR EXISTS (SELECT 1 FROM house h WHERE h.id=housing.house_id AND h.status='OCCUPIED') THEN 'OCCUPIED' "
        + "WHEN EXISTS (SELECT 1 FROM lease_record l WHERE l.house_id=housing.house_id AND l.status='TO_SIGN' AND l.agreement_status='SIGNED') THEN 'WAITING_FIRST_PAYMENT' "
        + "WHEN EXISTS (SELECT 1 FROM lease_record l WHERE l.house_id=housing.house_id AND l.status IN ('PENDING','TO_SIGN')) THEN 'TO_SIGN' "
        + "WHEN EXISTS (SELECT 1 FROM residence_application a WHERE a.house_id=housing.house_id AND a.status='PENDING') THEN 'APPLYING' ELSE 'FREE' END AS occupancy, "
        + "(SELECT IF(COUNT(*)=1,MIN(a.id),NULL) FROM residence_application a WHERE a.house_id=housing.house_id AND a.status='PENDING') AS application_id, "
        + "(SELECT IF(COUNT(*)=1,MIN(l.id),NULL) FROM lease_record l WHERE l.house_id=housing.house_id AND l.status IN ('PENDING','TO_SIGN','ACTIVE')) AS lease_id "
        + "FROM housing WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    java.util.List<com.community.residence.housing.vo.HousingOccupancyVO> selectOccupancies(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids);

}
