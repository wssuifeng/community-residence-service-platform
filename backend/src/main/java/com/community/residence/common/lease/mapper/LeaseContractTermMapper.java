package com.community.residence.common.lease.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.common.lease.entity.LeaseContractTerm;
import org.apache.ibatis.annotations.*;
@Mapper
public interface LeaseContractTermMapper extends BaseMapper<LeaseContractTerm> {
    @Select("SELECT * FROM lease_contract_term WHERE id=#{id} FOR UPDATE") LeaseContractTerm lock(@Param("id")Long id);
}
