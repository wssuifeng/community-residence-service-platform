package com.community.residence.lease.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.lease.entity.LeaseExtensionApplication;
import org.apache.ibatis.annotations.*;
@Mapper
public interface LeaseExtensionApplicationMapper extends BaseMapper<LeaseExtensionApplication> {
 @Select("SELECT * FROM lease_extension_application WHERE id=#{id} FOR UPDATE")
 LeaseExtensionApplication lock(@Param("id") Long id);
}
