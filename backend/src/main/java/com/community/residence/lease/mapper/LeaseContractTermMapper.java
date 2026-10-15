package com.community.residence.lease.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.lease.entity.LeaseContractTerm;
import org.apache.ibatis.annotations.*;
@Mapper
public interface LeaseContractTermMapper extends BaseMapper<LeaseContractTerm> {
    @Select("SELECT * FROM lease_contract_term WHERE id=#{id} FOR UPDATE") LeaseContractTerm lock(@Param("id")Long id);
}
