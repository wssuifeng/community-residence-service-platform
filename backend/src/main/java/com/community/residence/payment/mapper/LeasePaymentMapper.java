package com.community.residence.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.payment.entity.LeasePayment;
import org.apache.ibatis.annotations.Mapper;

/** 租约续约支付单数据访问 */
@Mapper
public interface LeasePaymentMapper extends BaseMapper<LeasePayment> {
}
