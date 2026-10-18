package com.community.residence.common.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.reservation.entity.ViolationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/** 违约处置记录数据访问 */
@Mapper
public interface ViolationRecordMapper extends BaseMapper<ViolationRecord> {

    /* 账号级统一计数，含资源预约与看房；锁定读不受事务此前一致性快照影响。 */
    @Select("SELECT id FROM violation_record WHERE user_id = #{userId} ORDER BY id FOR UPDATE")
    List<Long> selectIdsByUserIdForUpdate(@Param("userId") Long userId);

}
