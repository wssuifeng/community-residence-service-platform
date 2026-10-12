package com.community.residence.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.residence.payment.entity.PaymentChannelConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaymentChannelConfigMapper extends BaseMapper<PaymentChannelConfig> {
    /* 单行配置的当前读，避免两个超管同时保存或清除互相覆盖。 */
    @Select("SELECT id, channel, environment, app_id, application_public_key, merchant_private_key_ciphertext, alipay_public_key, enabled, revision, updated_by, created_at, updated_at FROM payment_channel_config WHERE channel = 'ALIPAY' FOR UPDATE")
    PaymentChannelConfig lockAlipay();
}
