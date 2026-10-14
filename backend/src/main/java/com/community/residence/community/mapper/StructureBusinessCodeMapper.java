package com.community.residence.community.mapper;
import com.community.residence.community.entity.House;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 编号分配与精确位置解析；所有关联列显式限定所属表。 */
@Mapper
public interface StructureBusinessCodeMapper {
 @Select("SELECT id FROM sys_config WHERE config_key='structure.code.sequence' FOR UPDATE") Long lockSequence();
 @Select("SELECT id FROM community WHERE id=#{id} FOR UPDATE") Long lockCommunity(Long id);
 @Select("SELECT id FROM building WHERE id=#{id} AND is_deleted=0 FOR UPDATE") Long lockBuilding(Long id);
 @Select("SELECT business_code FROM community WHERE business_code IS NOT NULL FOR UPDATE") List<String> communityCodes();
 @Select("SELECT business_code FROM community WHERE id != #{id} AND business_code IS NOT NULL FOR UPDATE") List<String> otherCommunityCodes(Long id);
 @Select("SELECT business_code FROM building WHERE community_id=#{communityId} AND (#{excludedId} IS NULL OR id != #{excludedId}) AND business_code IS NOT NULL FOR UPDATE") List<String> buildingCodes(@Param("communityId") Long communityId,@Param("excludedId") Long excludedId);
 @Select("SELECT business_code FROM unit WHERE building_id=#{buildingId} AND (#{excludedId} IS NULL OR id != #{excludedId}) AND business_code IS NOT NULL FOR UPDATE") List<String> unitCodes(@Param("buildingId") Long buildingId,@Param("excludedId") Long excludedId);
 @Select("SELECT h.* FROM house h JOIN unit u ON u.id=h.unit_id AND u.is_deleted=0 JOIN building b ON b.id=u.building_id AND b.is_deleted=0 JOIN community c ON c.id=h.community_id WHERE h.is_deleted=0 AND c.business_code=#{community} AND b.business_code=#{building} AND u.business_code=#{unit} AND h.house_number=#{room}")
 House resolve(@Param("community") String community,@Param("building") String building,@Param("unit") String unit,@Param("room") String room);
 @Select("SELECT CONCAT(c.business_code,'-',b.business_code,'-',u.business_code,'-',REPLACE(REPLACE(h.house_number,'%','%25'),'-','%2D')) FROM house h JOIN unit u ON u.id=h.unit_id AND u.is_deleted=0 JOIN building b ON b.id=u.building_id AND b.is_deleted=0 JOIN community c ON c.id=h.community_id WHERE h.id=#{houseId} AND h.is_deleted=0")
 String codeForHouse(Long houseId);
}
