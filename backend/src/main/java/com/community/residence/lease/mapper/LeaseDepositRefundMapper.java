package com.community.residence.lease.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.lease.entity.LeaseDepositRefund;
import org.apache.ibatis.annotations.Mapper;

/* 押金退还记录（R72）：幂等键与租约维度查询由 LambdaQueryWrapper 表达，
   社区级联删除按 leaseIds 批删（须排在物理删除 lease_record 之前，DEF-066 纪律） */
@Mapper
public interface LeaseDepositRefundMapper extends BaseMapper<LeaseDepositRefund> {
}
