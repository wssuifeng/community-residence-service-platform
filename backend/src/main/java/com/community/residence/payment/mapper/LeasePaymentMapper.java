package com.community.residence.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.payment.entity.LeasePayment;
import org.apache.ibatis.annotations.Mapper;

/** 租约续约支付单数据访问 */
@Mapper
public interface LeasePaymentMapper extends BaseMapper<LeasePayment> {
    @org.apache.ibatis.annotations.Select("SELECT id,payment_no,lease_id,resident_id,payment_type,channel,months,amount,status,channel_trade_no,paid_at,created_at,updated_at FROM lease_payment WHERE id=#{id} FOR UPDATE")
    LeasePayment selectByIdForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);

}
